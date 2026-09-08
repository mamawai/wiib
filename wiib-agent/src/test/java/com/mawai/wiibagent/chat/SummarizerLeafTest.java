package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * summarizer 叶子上那三个 hook 的装配（压缩 + HITL 闸门 + 保险丝），以及它的迭代硬顶够不够用。
 * 闸门被挂上了没有由 {@code ApprovalGateOrderTest} 钉，这里只管压缩与预算。
 * <p>
 * 这些以前只能挂父图 + 按 id 过滤（子图 hook 在 {@code addNode(id, StateGraph)} 内联时会被框架
 * 整个丢掉），现在叶子是独立 {@code compile()} 的，走 ReactAgent 自己的挂载点。
 * <b>建的是生产的 {@link ChatAgentFactory#leavesFor} 并真跑</b>——自己搭个 agent 自己挂 hook
 * 只能证明 hook 类好使（那件事 ModelCallLimiterTest/ConversationSummarizerTest 已经证过），
 * 证明不了生产装配里挂上了。
 */
class SummarizerLeafTest {

    private static final String SESSION = "wb-1-summarizer-leaf";
    /** 压缩提示词的特征串：浅模型这里只服务摘要一种请求，留着当断言锚点 */
    private static final String SUMMARY_PROMPT_MARK = "请把下面的对话历史压缩成一段要点记录";
    private static final String SUMMARY_PREFIX = "## 早前对话摘要：";
    /** 阈值给足 = 这一跑不碰压缩 */
    private static final int NO_COMPRESSION = 999_999;

    private final ChatModel deep = mock(ChatModel.class);
    private final ChatModel light = mock(ChatModel.class);
    private final ApprovalRegistry registry = new ApprovalRegistry();
    private final DeepAnalysisService deepAnalysisService = mock(DeepAnalysisService.class);
    private final WorkbenchRunRegistry runRegistry = mock(WorkbenchRunRegistry.class);

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static AssistantMessage toolCall(String id, String name, String arguments) {
        return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                id, "function", name, arguments))).build();
    }

    private static AssistantMessage deepAnalysisCall(String id) {
        return toolCall(id, "run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}");
    }

    /**
     * @param threshold 压缩阈值（token），调小才触发
     * @param keep      保留最近几条，调小才有原文可压
     * @param limit     模型调用上限，也就是迭代账里的 L
     */
    private ChatAgentFactory.Leaves leaves(int threshold, int keep, int limit) {
        // ChatService 建请求时无条件读 getOptions() 挂工具，null 会 NPE
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, light));
        // run_deep_analysis 必须是能执行的真工具（要真走到工具边）——
        // 工厂内部自己 new DeepAnalysisToolkit，天然就是真的，这里只喂它的依赖
        ChatEndpoints llmConfig = ChatTestEndpoints.eps(1L, "gpt-5");   // 叶子指纹含 userId（trader 工具按它认人）
        return new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class), mock(NewsToolkit.class),
                deepAnalysisService, mock(BehaviorAnalysisService.class),
                mock(TraderChatService.class), runRegistry,
                registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS, limit, threshold, keep, "X")
                .leavesFor(llmConfig, AgentLang.ZH);
    }

    /** 与 {@code ChatTurnRunner} 同款消费：普通迭代 + threadId（闸门要拿会话号）+ state 里的会话号（工具要拿） */
    private List<String> consume(ChatAgentFactory.Leaves leaves, List<Message> input,
                                 List<NodeOutput<MessagesState<Message>>> outputs) {
        List<String> chunks = new ArrayList<>();
        for (NodeOutput<MessagesState<Message>> output : leaves.summarizer().stream(
                Map.of("messages", input, ToolRunContext.SESSION_KEY, SESSION),
                RunnableConfig.builder().threadId(SESSION).build())) {
            if (output instanceof StreamingOutput<?> streaming
                    && streaming.chunk() != null && !streaming.chunk().isEmpty()) {
                chunks.add(streaming.chunk());
            }
            outputs.add(output);
        }
        return chunks;
    }

    /** 深研判工具的回包：narrative 撑得够大，一条回执就把历史顶过压缩阈值 */
    private void deepAnalysisReturnsBigResult() {
        QuantDeepAnalysis analysis = new QuantDeepAnalysis();
        analysis.setNarrative("行情研判正文".repeat(200));
        analysis.setScenariosJson("{\"bullPct\":40,\"rangePct\":35,\"bearPct\":25}");
        analysis.setNoDirection(Boolean.FALSE);
        analysis.setInvalidation("跌破前低即失效");
        analysis.setJudgeReasoning("裁决理由");
        when(deepAnalysisService.buildNewsContext(any())).thenReturn("新闻上下文");
        when(deepAnalysisService.bullArgue(any(), anyString(), anyString(), any())).thenReturn("多方论证");
        when(deepAnalysisService.bearArgue(any(), anyString(), anyString(), any())).thenReturn("空方论证");
        when(deepAnalysisService.judge(any(), anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), any())).thenReturn(analysis);
    }

    private void approveDeepAnalysis() {
        registry.requestApproval(SESSION, "run_deep_analysis", "BTCUSDT", "贵操作");
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());
    }

    /** 工具方法体的会话号来自图 state：输入里带的 SESSION_KEY 经框架交给工具的 ToolContext 到达，不靠 ThreadLocal */
    @Test
    void 工具从图state里拿到会话号() {
        AtomicInteger round = new AtomicInteger();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(responseOf(round.incrementAndGet() == 1
                ? toolCall("c1", "wake_trader", "{}")
                : new AssistantMessage("表单已打开"))));
        when(runRegistry.publishForm(any(), any(), any())).thenReturn(true);

        consume(leaves(NO_COMPRESSION, 6, 8), List.of(new UserMessage("叫醒交易员")), new ArrayList<>());

        verify(runRegistry).publishForm(eq(SESSION), eq("wake"), isNull());
    }

    /**
     * <b>验收硬条件</b>：压缩必须在 tool_call/tool_response <b>配对完整</b>的前提下发生，
     * 而且压完之后这份历史还能原样发给上游。
     * <p>
     * 落单的回执（有 function_call_output 没有 function_call）会被 {@code ResponsesChatModel}
     * 无条件转进请求，OpenAI 自家 Responses 对此直接 400——会话只能删掉重开。
     * 所以这里逐条核对第二次模型调用收到的 Prompt。
     */
    @Test
    void 压缩发生且不切断工具调用配对() {
        approveDeepAnalysis();
        deepAnalysisReturnsBigResult();
        when(light.call(any(Prompt.class))).thenAnswer(inv -> {
            // 浅模型在这条链上只干一件事：出摘要
            assertThat(((Prompt) inv.getArgument(0)).getContents()).contains(SUMMARY_PROMPT_MARK);
            return responseOf(new AssistantMessage("早前聊了行情"));
        });
        List<Prompt> deepPrompts = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> {
            deepPrompts.add(inv.getArgument(0));
            // 第一轮要工具（制造一对 tool_call/回执 + 一大坨内容），第二轮出答案收尾
            return Flux.just(responseOf(round.incrementAndGet() == 1
                    ? deepAnalysisCall("c1")
                    : new AssistantMessage("这是答案")));
        });
        // keep=2：第二次调用时历史 5 条，理想切点落在 tool_call 之前，正好检验它切得安不安全
        ChatAgentFactory.Leaves leaves = leaves(200, 2, 8);

        List<NodeOutput<MessagesState<Message>>> outputs = new ArrayList<>();
        List<String> chunks = consume(leaves, List.of(
                new UserMessage("上轮问题"), new AssistantMessage("上轮回答"),
                new UserMessage("深度研判 BTC")), outputs);

        assertThat(deepPrompts).hasSize(2);
        List<Message> second = deepPrompts.getLast().getInstructions();
        // 压缩真发生了：老原文被换成了一条带固定前缀的摘要
        assertThat(second).anyMatch(m -> m instanceof SystemMessage
                && m.getText() != null && m.getText().startsWith(SUMMARY_PREFIX)
                && m.getText().contains("早前聊了行情"));
        // 而且压短了：第一次调用 4 条（含 agent 自己的 system），第二次的历史多了一对工具消息
        // 却没变长——不压的话是 6 条
        assertThat(second).hasSizeLessThan(6);
        // 配对完整：每个 tool_call 都能在后面找到自己的回执
        assertThat(orphanToolCalls(second)).isEmpty();
        assertThat(orphanToolResponses(second)).isEmpty();
        // 压缩开着的时候 token 照样逐帧到达前端——这是用户唯一看得见的东西
        assertThat(chunks).containsExactly("这是答案");
        // 终态也得是配对完整的：它会被整体落进会话上下文表，下一轮原样重放
        List<Message> finalMessages = outputs.getLast().state().messages();
        assertThat(orphanToolResponses(finalMessages)).isEmpty();
        assertThat(finalMessages.getLast().getText()).isEqualTo("这是答案");
    }

    /**
     * 迭代预算：模型永不收尾时，收束它的必须是保险丝而不是框架的迭代硬顶。
     * <p>
     * 硬顶抛在<b>结果交出去之前</b>——开小了，保险丝哪怕正常触发、日志正常打，
     * 用户拿到的还是异常而不是那半个截断回答。所以这两个数必须一起看。
     * <p>
     * 制造"永不收尾"的办法是不授权：闸门每次都短路回模型节点，等于一个纯净的循环，
     * 一次深模型工具都不真跑。
     */
    @Test
    void 模型永不收尾时被保险丝收束而不是撞硬顶() {
        int limit = 8;   // 生产口径
        AtomicInteger round = new AtomicInteger();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv ->
                Flux.just(responseOf(deepAnalysisCall("c" + round.incrementAndGet()))));
        ChatAgentFactory.Leaves leaves = leaves(NO_COMPRESSION, 6, limit);

        assertThatCode(() -> consume(leaves, List.of(new UserMessage("深度研判 BTC")), new ArrayList<>()))
                .doesNotThrowAnyException();

        // 恰好等于而非"不超过"：calls=已有+1、calls>=runLimit 才跳 END，触发那刻模型正好被调 limit 次。
        // 钉死这个数才验得到上限值确实是从构造参数来的
        assertThat(round.get()).isEqualTo(limit);
    }

    /** 每个 tool_call 的 id 是否都能找到配对回执 */
    private static Set<String> orphanToolCalls(List<Message> messages) {
        Set<String> ids = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                assistant.getToolCalls().forEach(call -> ids.add(call.id()));
            }
        }
        ids.removeAll(toolResponseIds(messages));
        return ids;
    }

    /** 反方向：回执找不到自己的调用（这个是上游真会 400 的那种） */
    private static Set<String> orphanToolResponses(List<Message> messages) {
        Set<String> ids = new HashSet<>(toolResponseIds(messages));
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                assistant.getToolCalls().forEach(call -> ids.remove(call.id()));
            }
        }
        return ids;
    }

    private static Set<String> toolResponseIds(List<Message> messages) {
        Set<String> ids = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage toolResponse) {
                toolResponse.getResponses().forEach(response -> ids.add(response.id()));
            }
        }
        return ids;
    }
}
