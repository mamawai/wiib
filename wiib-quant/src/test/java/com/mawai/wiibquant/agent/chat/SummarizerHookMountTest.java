package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.entity.UserLlmConfig;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.llm.ModelCallLimiter;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * summarizer 在<b>真图</b>上的装配：两个 hook 到底有没有执行，以及它有没有兜底模型。
 * <p>
 * 挂在子 StateGraph 上的 hook 会在 {@code addNode(id, StateGraph)} 内联时被框架整个丢掉
 * （只搬 nodes/edges），所以只能挂父图 + 按 id 过滤。这几条测试就是那套挂载的钉子：
 * 建的是生产的 {@link ChatAgentFactory#chatGraph} 并真跑，把 build() 里的注册删掉前两条立刻变红。
 * 自己搭图自己挂 hook 只能证明 hook 类本身好使（ModelCallLimiterTest/ConversationSummarizerTest
 * 已经证过了），证明不了生产装配里挂上了。
 */
class SummarizerHookMountTest {

    /** 压缩提示词的特征串：浅模型同时服务 router 与摘要两种请求，靠它区分是哪一种 */
    private static final String SUMMARY_PROMPT_MARK = "请把下面的对话历史压缩成一段要点记录";

    /**
     * 测试用的调用上限。取 3 只为跑得快——这几条验的是"hook 挂没挂"，不是上限值取多少：
     * 上限值本身按生产口径（L=8 + 跑满专家轮）在 {@link ChatIterationBudgetTest} 里验。
     * <p>
     * 父图的迭代账见 {@link ChatAgentFactory#PARENT_RECURSION_LIMIT}（{@code 3L+4R+4}，生产硬顶 40）。
     * L=3、R=0 吃 13 格，怎么都够。
     * <p>
     * 别和 {@link ExpertCallLimitTest} 那边的 {@code 2L+3} 对照着以为有一处是错的：
     * 两张图的预算本来就不同，专家图没有 {@code .streaming(true)}，非流式模型节点只占 1 格。
     */
    private static final int LIMIT = 3;

    /** 阈值给足 = 这一跑不碰压缩，别让它掺进保险丝那两条的因果里 */
    private static final int NO_COMPRESSION = 999_999;

    private final ChatModel deep = mock(ChatModel.class);
    private final ChatModel light = mock(ChatModel.class);
    private final ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
    private final ApprovalRegistry approvalRegistry = new ApprovalRegistry();

    /** 配置内容与这几条无关（模型来自打桩的 ChatModelFactory），它只用来算图的缓存键 */
    private static final UserLlmConfig CONFIG = new UserLlmConfig();

    /**
     * @param summarizeThresholdTokens 压缩阈值，调到 1 = 每次模型调用都触发
     * @param summarizeKeepMessages    保留最近几条，调小才有原文可压
     */
    private ChatAgentFactory factory(int summarizeThresholdTokens, int summarizeKeepMessages) {
        // ChatService 建请求时无条件读 getOptions() 挂工具，null 会 NPE
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, light));
        // run_deep_analysis 必须是能执行的真工具（ReAct 循环要真走到工具边）——
        // 工厂内部自己 new DeepAnalysisToolkit，天然就是真的，这里只喂它的两个依赖
        // 真 saver 而不是 mock：mock 的 put() 返回 null，而 CompiledGraph 会接着用这个返回值
        return new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisService.class), mock(WorkbenchRunRegistry.class),
                approvalRegistry, new MemorySaver(),
                new SpringAIJacksonStateSerializer<>(MessagesState::new),
                LIMIT, summarizeThresholdTokens, summarizeKeepMessages, "X");
    }

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static AssistantMessage toolCall(String id, String name, String args) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args))).build();
    }

    /** router 恒答 FINISH，让流程直达 summarizer（专家不参与这几条） */
    private void routerAlwaysFinishes() {
        when(light.call(any(Prompt.class))).thenAnswer(inv ->
                responseOf(toolCall("r", "route", "{\"next\":[\"FINISH\"]}")));
    }

    /**
     * 历史压缩至今一次都没跑过（hook 被内联丢了），长会话每轮都把全量历史喂深模型，直接烧钱。
     * 阈值拧到 1 逼它必压，三条断言各钉一件事：压缩跑了且只在模型节点跑、压缩结果进了 state、
     * 模型这一轮的答案没被压缩顶掉。
     */
    @Test
    void 压缩钩子在真图上真的执行() throws Exception {
        AtomicInteger compressions = new AtomicInteger();
        // 摘要是阻塞 call、router 也是阻塞 call，共用同一个浅模型 mock，只能靠提示词内容分辨
        when(light.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            if (prompt.getInstructions().getFirst().getText().contains(SUMMARY_PROMPT_MARK)) {
                compressions.incrementAndGet();
                return responseOf(new AssistantMessage("早前聊了行情"));
            }
            return responseOf(toolCall("r", "route", "{\"next\":[\"FINISH\"]}"));
        });
        // summarizer 是 .streaming(true) 的 → 走 model.stream 而不是 call
        when(deep.stream(any(Prompt.class)))
                .thenReturn(Flux.just(responseOf(new AssistantMessage("这是答案"))));
        // threshold=1 每次都超；keep=1 让切点落在最后一条之前，才有原文可压
        CompiledGraph<MessagesState<Message>> graph = factory(1, 1).chatGraph(CONFIG);

        List<Message> messages = graph.invoke(Map.of("messages", List.of(
                new UserMessage("上一轮问题"), new AssistantMessage("上一轮回答"),
                new UserMessage("这一轮问题")))).orElseThrow().messages();

        // 恰好 1 次而非"至少 1 次"：父图的全局 node hook 会命中每一个节点，
        // 漏了 onlyOnNode 过滤的话 router / 专家 / 工具节点上也会各压一遍，白烧浅模型的钱
        assertThat(compressions).hasValue(1);
        // 压缩结果得真的写回 state，否则同一轮里每次模型调用都要重压一遍
        assertThat(messages).anyMatch(m -> m instanceof SystemMessage && m.getText().contains("早前聊了行情"));
        // 而模型这一轮的答案不能被压缩顶掉：流式节点交回的是 token 生成器，
        // 按普通消息合并会把它整个丢掉——答案没了，工具节点还会当场报 no AssistantMessage provided
        assertThat(messages.getLast().getText()).isEqualTo("这是答案");
    }

    /**
     * 压缩开着的时候 token 必须照样逐帧到达前端——这是本次改动真正危及、也是用户唯一看得见的东西。
     * 上面那条只断言最终 state：把生成器抽干再交回，它照样绿而 SSE 直接哑掉（实测过）。
     * <p>
     * （"换个 key 把生成器塞回去"不在此列：框架是按 value 找生成器的，那条路帧照流、
     * 这条测试也照样绿；它被否掉的理由是别的——要往 state 塞 schema 外的合成键。）
     */
    @Test
    void 压缩开着时token仍逐帧推送() throws Exception {
        when(light.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            return prompt.getInstructions().getFirst().getText().contains(SUMMARY_PROMPT_MARK)
                    ? responseOf(new AssistantMessage("早前聊了行情"))
                    : responseOf(toolCall("r", "route", "{\"next\":[\"FINISH\"]}"));
        });
        when(deep.stream(any(Prompt.class))).thenReturn(Flux.just(
                responseOf(new AssistantMessage("这是")),
                responseOf(new AssistantMessage("答")),
                responseOf(new AssistantMessage("案"))));
        CompiledGraph<MessagesState<Message>> graph = factory(1, 1).chatGraph(CONFIG);

        // 与 ChatWorkbenchController.run() 同款消费：普通迭代（不是 forEachAsync）+ 只认 StreamingOutput
        List<String> chunks = new ArrayList<>();
        for (NodeOutput<MessagesState<Message>> output : graph.stream(Map.of("messages", List.of(
                new UserMessage("上一轮问题"), new AssistantMessage("上一轮回答"),
                new UserMessage("这一轮问题"))))) {
            if (output instanceof StreamingOutput<?> streaming
                    && streaming.chunk() != null && !streaming.chunk().isEmpty()) {
                chunks.add(streaming.chunk());
            }
        }

        // 逐帧而非"拼起来等于答案"：一次性吐完整句同样能满足后者，那正是要防的退化
        assertThat(chunks).containsExactly("这是", "答", "案");
    }

    /**
     * 模型永不收尾时保险丝必须按配置的上限收束；挂丢了就只能一路转到撞迭代硬顶抛异常。
     */
    @Test
    void 保险丝在真图上真的收束() throws Exception {
        routerAlwaysFinishes();
        AtomicInteger rounds = new AtomicInteger();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(responseOf(
                toolCall("c" + rounds.incrementAndGet(), "run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"))));
        CompiledGraph<MessagesState<Message>> graph = factory(NO_COMPRESSION, 6).chatGraph(CONFIG);

        assertThatCode(() -> graph.invoke(Map.of("messages", List.of(new UserMessage("深度研判 BTC")))))
                .doesNotThrowAnyException();

        // 恰好等于而非"不超过"：calls=已有+1、calls>=runLimit 才跳 END，触发那刻模型正好被调 runLimit 次。
        // 钉死这个数才验得到上限值确实是从构造参数来的
        assertThat(rounds.get()).isEqualTo(LIMIT);
    }

    /**
     * 父图的全局 hook 会命中 router 的条件边。ModelCallLimiter 短路时回 Command("end")，
     * 而 router 的 mapping 只有 {dispatch, summarize}——漏了 onlyOn 过滤这里会抛 cannot find edge mapping。
     * 预置计数到上限是为了让这一跑确定性地走到短路分支。
     */
    @Test
    void 过滤不误伤router的条件边() throws Exception {
        routerAlwaysFinishes();
        when(deep.stream(any(Prompt.class)))
                .thenReturn(Flux.just(responseOf(new AssistantMessage("这是答案"))));
        CompiledGraph<MessagesState<Message>> graph = factory(NO_COMPRESSION, 6).chatGraph(CONFIG);

        assertThatCode(() -> graph.invoke(Map.of(
                "messages", List.of(new UserMessage("随便问问")),
                ModelCallLimiter.CALL_COUNT_KEY, LIMIT)))
                .doesNotThrowAnyException();
    }

    /**
     * summarizer 不许有兜底模型：BYOK 只有一个端点，切到同端点的另一个模型没有意义
     *（端点挂了两个一起挂），还会把"你的 key 出问题了"这件事捂成一个更差的答案。
     * <p>
     * 把 {@code .fallbackModel(light)} 加回去这条就红：浅模型会顶上，答案照出、错误无声消失。
     * 用 NonTransientAiException 是为了跳过退避重试，这条只验兜底、不想等那几秒。
     */
    @Test
    void 深模型失败不会偷偷切到浅模型() throws Exception {
        routerAlwaysFinishes();
        when(deep.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new NonTransientAiException("端点挂了")));
        when(light.stream(any(Prompt.class)))
                .thenReturn(Flux.just(responseOf(new AssistantMessage("兜底答案"))));
        CompiledGraph<MessagesState<Message>> graph = factory(NO_COMPRESSION, 6).chatGraph(CONFIG);

        // 断到根因而不是"抛了就行"：要验的是上游那个错原样冒到用户面前，没被谁替换成别的失败
        assertThatThrownBy(() -> graph.invoke(Map.of("messages", List.of(new UserMessage("随便问问")))))
                .rootCause()
                .isInstanceOf(NonTransientAiException.class)
                .hasMessage("端点挂了");

        verify(light, never()).stream(any(Prompt.class));
    }
}
