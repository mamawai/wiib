package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
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
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 专家子图的保险丝。专家也是 ReAct 循环，没有上限就能一路顶到框架 25 次迭代硬顶抛异常；
 * 而且 market 专家的工具（market_snapshot / orderbook_depth）每次调用都打真实上游，
 * 这是行情配额账里唯一没封顶的一项。
 * <p>
 * <b>必须调生产的 {@link ChatAgentFactory#expertGraph}</b>：测试自己搭图自己挂 hook 的话，
 * 验的只是"ModelCallLimiter 挂上之后好使"——那件事 ModelCallLimiterTest 已经验过了，
 * 而 ChatAgentFactory 里漏挂它照样绿。孤儿 tool_call 的补齐同理，不在这里重复断言。
 */
class ExpertCallLimitTest {

    /** 复刻 market 专家的工具形状：带参数、必须被真调 */
    public static class FakeMarketTools {
        @Tool(description = "行情快照")
        public String market_snapshot(String symbol) {
            return "{\"available\":true}";
        }
    }

    /**
     * 故意远小于生产默认的 8：框架把 START 也算一次迭代，ReAct 一轮 = 模型节点 + 工具节点，
     * 保险丝在第 2L+1 次迭代触发，收尾还要 2 次（吐 END、给 done），共 2L+3 次。
     * 25 的硬顶意味着 L 最大只能取 11——写 12 今天就直接抛
     * "Maximum number of iterations (25) reached!"，而且是 hook 挂得好好的情况下红，属假失败。
     * 取 3 留足余量，顺带跑得快。
     * <p>
     * 生产的 8 落在这条线里（实测吃 19 格），所以专家图的 {@code .compile()} 保持无参、不抬硬顶。
     * 父图那侧的账不一样（流式模型节点吃 2 格），见 {@link ChatAgentFactory#PARENT_RECURSION_LIMIT}。
     */
    private static final int LIMIT = 3;

    /** 与工厂的生产装配同款；runtimeManager 只在 chatGraph() 用得到，这条路不碰它，不必打桩 */
    private ChatAgentFactory factory() {
        return new ChatAgentFactory(mock(AiAgentRuntimeManager.class),
                mock(MarketToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisService.class), mock(WorkbenchRunRegistry.class),
                new ApprovalRegistry(), mock(BaseCheckpointSaver.class),
                new SpringAIJacksonStateSerializer<>(MessagesState::new), LIMIT, 32000, 6, "X");
    }

    /** 模型永不收尾（每轮都只想再调一次工具）时，必须被保险丝按配置的上限收束，而不是撞框架硬顶 */
    @Test
    void 带工具的专家在模型永不收尾时被保险丝收束() throws Exception {
        ChatModel model = mock(ChatModel.class);
        // ChatService 建请求时无条件读 getOptions() 挂工具，null 会 NPE
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AtomicInteger round = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            int i = round.incrementAndGet();
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("c" + i, "function",
                            "market_snapshot", "{\"symbol\":\"BTCUSDT\"}")))
                    .build())));
        });

        CompiledGraph<MessagesState<Message>> expert = factory()
                .expertGraph(model, new FakeMarketTools(), "required", "你是市场状态专家");
        expert.invoke(Map.of("messages", List.of(new UserMessage("看看行情")))).orElseThrow();

        // 恰好等于而非"不超过"：ModelCallLimiter 是 calls=已有+1、calls>=runLimit 跳 END，
        // 触发那一刻模型正好被调 runLimit 次。钉死这个数才验得到上限值是从构造参数来的——
        // 写成 new ModelCallLimiter(1) 这种取错值的写法，"不超过"照样绿。
        assertThat(round.get()).isEqualTo(LIMIT);
    }
}
