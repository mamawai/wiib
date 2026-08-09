package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.config.AiAgentRuntime;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 父图的迭代预算：{@link ChatAgentFactory#PARENT_RECURSION_LIMIT} 那本账的钉子。
 * <p>
 * 保险丝把一轮 ReAct 收在 8 次模型调用，可框架的迭代硬顶抛在<b>结果交出去之前</b>——
 * 硬顶开小了，保险丝哪怕正常触发、日志正常打，用户拿到的还是异常而不是那半个截断回答。
 * 所以这两个数必须一起看，光验保险丝（{@link SummarizerHookMountTest}）不够。
 * <p>
 * 这里跑的是<b>生产口径</b>：真 {@link ChatAgentFactory#chatGraph()}、生产的调用上限 8、
 * 走真实可达的最坏路径。{@link SummarizerHookMountTest} 那边 LIMIT=3 是为了跑得快，
 * 验不到"生产值配生产硬顶够不够"。
 */
class ChatIterationBudgetTest {

    /** 与 application.yml 的 {@code quant.workbench.run-model-call-limit} 一致：本类专验生产取值 */
    private static final int PRODUCTION_LIMIT = 8;

    /**
     * 最坏可达路径的实测迭代消耗 = {@code 3L + 4R + 4} 在 L=8、R=2 时的值。
     * <p>
     * R 为什么只能到 2：{@link ChatAgentFactory#MAX_DISPATCH_ROUNDS} 允许 3 轮，但
     * {@link ChatAgentFactory#route} 会把已派过的专家去重掉，而专家只有两个——
     * 两轮之内必然派完，第三次 router 只剩 FINISH 可走。
     */
    private static final int WORST_PATH_ITERATIONS = 36;

    /** 阈值给足 = 这一跑不碰历史压缩，别让它掺进迭代账里 */
    private static final int NO_COMPRESSION = 999_999;

    /** 一次真跑的装配 + 三个计数器 */
    private record Rig(CompiledGraph<MessagesState<Message>> graph,
                       AtomicInteger summarizerCalls,
                       AtomicInteger routerCalls,
                       AtomicInteger expertCalls) {
    }

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static AssistantMessage toolCall(String id, String name, String args) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args))).build();
    }

    /**
     * 装出"真实可达的最坏一轮"：两轮串行派发（每轮一个专家）+ summarizer 吃满 8 次调用。
     * <p>
     * 第三次 route 故意再点名已派过的专家，走 {@link ChatAgentFactory#route} 的去重转 FINISH——
     * 这才是回环真正的收口方式，比让模型自觉说 FINISH 更接近线上。
     */
    private Rig worstPathRig() throws Exception {
        ChatModel deep = mock(ChatModel.class);
        ChatModel light = mock(ChatModel.class);
        // ChatService 建请求时无条件读 getOptions() 挂工具，null 会 NPE
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AiAgentRuntimeManager runtimeManager = mock(AiAgentRuntimeManager.class);
        // 位序是 (behavior, quant, quantLight, chat)：深模型进 quant，浅模型进 quantLight
        when(runtimeManager.current()).thenReturn(new AiAgentRuntime(light, deep, light, deep));

        AtomicInteger routerCalls = new AtomicInteger();
        AtomicInteger expertCalls = new AtomicInteger();
        // 浅模型同时服务 router 和两个专家，只能靠系统提示词首句分辨是哪一种请求
        when(light.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            if (!prompt.getInstructions().getFirst().getText().contains("你是研判工作台的调度器")) {
                // 两个专家的结论必须不一样：MessagesState.SCHEMA 的 reducer 是 ReducerDisallowDuplicate，
                // 按 Objects.hash 去重——回同一句话的话后一个专家的回复被静默丢掉，
                // 子图末条不再是 AssistantMessage，当场 no AssistantMessage provided、被专家节点的
                // catch 兜成"[xxx_agent 暂时不可用]"。迭代账不受影响，但这条测试就名不副实了
                return responseOf(new AssistantMessage("专家结论" + expertCalls.incrementAndGet()));
            }
            String next = switch (routerCalls.incrementAndGet()) {
                case 1 -> "[\"market_agent\"]";
                case 2 -> "[\"news_agent\"]";
                default -> "[\"market_agent\",\"news_agent\"]"; // 都派过了 → 代码去重转 FINISH
            };
            return responseOf(toolCall("r" + routerCalls.get(), "route", "{\"next\":" + next + "}"));
        });

        AtomicInteger summarizerCalls = new AtomicInteger();
        // summarizer 永不收尾（每轮都只想再调一次工具）→ 只能被保险丝按上限收束
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(responseOf(toolCall(
                "c" + summarizerCalls.incrementAndGet(), "run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"))));

        ApprovalRegistry approvalRegistry = new ApprovalRegistry();
        // 真 toolkit：没登记授权时它直接回 PENDING_APPROVAL，一次深模型都不烧
        DeepAnalysisToolkit toolkit = new DeepAnalysisToolkit(
                mock(DeepAnalysisService.class), approvalRegistry, mock(WorkbenchRunRegistry.class));
        // 真 saver 而不是 mock：mock 的 put() 返回 null，而 CompiledGraph 会接着用这个返回值
        ChatAgentFactory factory = new ChatAgentFactory(runtimeManager,
                mock(MarketToolkit.class), mock(NewsToolkit.class), toolkit, approvalRegistry,
                new MemorySaver(), new SpringAIJacksonStateSerializer<>(MessagesState::new),
                PRODUCTION_LIMIT, NO_COMPRESSION, 6, "X");
        return new Rig(factory.chatGraph(), summarizerCalls, routerCalls, expertCalls);
    }

    private static void run(CompiledGraph<MessagesState<Message>> graph) {
        graph.invoke(Map.of("messages", List.of(new UserMessage("看看 BTC 行情和新闻"))));
    }

    /**
     * 把生产装好的那张 StateGraph 换个硬顶重新编译一遍——量账用，不碰生产的编译点。
     * <p>
     * 走 {@code stateGraph}/{@code compileConfig} 这两个公开字段而不是
     * {@code setMaxIterations}：后者上游已标 deprecated-for-removal。
     */
    private static CompiledGraph<MessagesState<Message>> withLimit(Rig rig, int limit) throws Exception {
        return rig.graph().stateGraph.compile(
                CompileConfig.builder(rig.graph().compileConfig).recursionLimit(limit).build());
    }

    /**
     * 生产装配下跑最坏一轮不许撞硬顶。这是抬硬顶那一行的钉子：
     * 把 {@code .recursionLimit(PARENT_RECURSION_LIMIT)} 去掉退回框架默认 25，这条立刻抛
     * "Maximum number of iterations (25) reached!"。
     */
    @Test
    void 生产上限下跑满专家轮与模型调用不撞迭代硬顶() throws Exception {
        Rig rig = worstPathRig();

        assertThatCode(() -> run(rig.graph())).doesNotThrowAnyException();

        // 恰好等于而非"不超过"：calls=已有+1、calls>=runLimit 才跳 END，触发那刻模型正好被调 runLimit 次。
        // 钉死这个数才验得到上限确实是生产那个 8——写成"不超过"，取错值（比如 1）照样绿
        assertThat(rig.summarizerCalls().get()).isEqualTo(PRODUCTION_LIMIT);
        // 这一跑确实走完了两轮派发（而不是被谁提前收口，那样这条测试就名不副实了）
        assertThat(rig.routerCalls().get()).isEqualTo(3);
        assertThat(rig.expertCalls().get()).isEqualTo(2);
    }

    /**
     * 把最坏路径的真实消耗钉成一个数：36 格够、35 格不够。
     * <p>
     * 上面那条只证明"40 还够用"，改动图结构（往回环里加个节点、给 summarizer 再挂一层）时
     * 它可能仍然绿着，而账已经变了。这条专管账本身，跟着 {@code PARENT_RECURSION_LIMIT}
     * 的 javadoc 一起维护——注释和代码矛盾在这个仓库栽过不止一次。
     */
    @Test
    void 最坏路径的迭代消耗恰好是36格() throws Exception {
        CompiledGraph<MessagesState<Message>> enough = withLimit(worstPathRig(), WORST_PATH_ITERATIONS);
        assertThatCode(() -> run(enough)).doesNotThrowAnyException();

        CompiledGraph<MessagesState<Message>> oneShort =
                withLimit(worstPathRig(), WORST_PATH_ITERATIONS - 1);
        assertThatThrownBy(() -> run(oneShort))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Maximum number of iterations (%d) reached!", WORST_PATH_ITERATIONS - 1);
    }
}
