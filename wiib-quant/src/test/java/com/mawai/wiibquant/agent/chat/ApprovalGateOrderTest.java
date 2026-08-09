package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.config.AiAgentRuntime;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.llm.ModelCallLimiter;
import com.mawai.wiibquant.agent.llm.ResilientChatService;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.hook.EdgeHook;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
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
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HITL 闸门的挂载与顺序。
 * <p>
 * 顺序钉子：langgraph4j 的 WrapCall 是 reduce 左折叠，后注册的在最外层先执行，跟直觉相反。
 * 写反了代码照跑、什么都不报错，只在"ReAct 已逼近调用上限时用户要深研判"这一个场景下
 * 才暴露——卡片弹了，但模型没配额告诉用户发生了什么。
 */
class ApprovalGateOrderTest {

    private static final String SESSION = "wb-1-abc";

    public static class Probe {
        @Tool(name = "probe", description = "探针")
        public String probe() {
            return "ok";
        }
    }

    /**
     * 框架语义本身：两个 hook 注册到真的 ReactAgent 上跑一遍，看谁在外层。
     * 不用手写嵌套 lambda——那验的是 lambda 调用语义，框架哪天把 FIFO 改成 LIFO 照样绿
     */
    @Test
    void 框架把后注册的hook放在最外层() throws Exception {
        List<String> order = new ArrayList<>();
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "probe", "{}")))
                        .build()))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("好了")))));

        CompiledGraph<MessagesState<Message>> agent = ReactAgent.<MessagesState<Message>>builder()
                .chatModel(model)
                .stateSerializer(new SpringAIJacksonStateSerializer<>(MessagesState::new))
                .toolsFromObject(new Probe())
                .addExecuteToolsHook(hook(order, "first"))
                .addExecuteToolsHook(hook(order, "second"))
                .build(ResilientChatService.builder().model(model).asFactory())
                .compile();
        agent.invoke(Map.of("messages", List.of(new UserMessage("跑一下"))));

        // 后注册的 second 在最外层，所以它先被调到。
        // 两组而不是一组：hook 挂的是"模型节点出来那条边"，模型每被调一次就过一遍——
        // 第 1 次跳去工具、第 2 次（模型给了答案、没有 tool_call）跳去 END，两次都算。
        // 实测过：把剧本改成"工具、工具、答案"就变成三组（ModelCallLimiter 数的正是这个数）
        assertThat(order).containsExactly("second", "first", "second", "first");
    }

    /**
     * 生产代码的注册顺序：保险丝必须排在清单末尾（=最外层）。
     * 把 ChatAgentFactory 里那两个元素对调，这条立刻红
     */
    @Test
    void 生产清单里保险丝排在最外层() {
        List<EdgeHook.WrapCall<MessagesState<Message>>> hooks =
                ChatAgentFactory.summarizerToolHooks(new ApprovalRegistry(), 12);

        assertThat(hooks).hasSize(2);
        assertThat(hooks.getFirst()).isInstanceOf(ApprovalGate.class);          // 内层
        assertThat(hooks.getLast()).isInstanceOf(ModelCallLimiter.class);       // 最外层，先跑
    }

    /**
     * 闸门真的挂上了：真图跑一轮，模型要调深研判，未授权时必须被短路并留下待确认。
     * 这是唯一能抓住"忘了往 summarizerToolHooks 里放 ApprovalGate"的断言——
     * 前两条测试对它一无所知
     */
    @Test
    void 闸门在真图上拦下未授权的深研判() throws Exception {
        ApprovalRegistry registry = new ApprovalRegistry();
        ChatModel deep = mock(ChatModel.class);
        ChatModel light = mock(ChatModel.class);
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        // router 恒答 FINISH，直达 summarizer
        when(light.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("r", "function", "route", "{\"next\":[\"FINISH\"]}")))
                        .build()))));
        when(deep.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(List.of(
                                new AssistantMessage.ToolCall("c1", "function", "run_deep_analysis",
                                        "{\"symbol\":\"BTCUSDT\"}"))).build())))))
                .thenReturn(Flux.just(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("已请你确认"))))));

        CompiledGraph<MessagesState<Message>> graph = factory(deep, light, registry).chatGraph();
        graph.invoke(Map.of("messages", List.of(new UserMessage("深度研判 BTC"))),
                RunnableConfig.builder().threadId(SESSION).build());

        assertThat(registry.peekPending(SESSION)).isPresent()
                .get().satisfies(p -> assertThat(p.symbol()).isEqualTo("BTCUSDT"));
    }

    private static EdgeHook.WrapCall<MessagesState<Message>> hook(List<String> order, String name) {
        return (id, s, c, action) -> {
            order.add(name);
            return action.apply(s, c);
        };
    }

    /**
     * 构造签名是 Task 8 之后的形态（toolkit 换成 service+runRegistry，工厂内部自己 new toolkit，
     * 所以 run_deep_analysis 天然是真工具）。Task 14 换模型来源时这里还要再改一次
     */
    private ChatAgentFactory factory(ChatModel deep, ChatModel light, ApprovalRegistry registry) {
        AiAgentRuntimeManager runtimeManager = mock(AiAgentRuntimeManager.class);
        // 位序是 (behavior, quant, quantLight, chat)：深模型进 quant，浅模型进 quantLight
        when(runtimeManager.current()).thenReturn(new AiAgentRuntime(light, deep, light, deep));
        return new ChatAgentFactory(runtimeManager,
                mock(MarketToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisService.class), mock(WorkbenchRunRegistry.class),
                // 真 saver：mock 的 put() 返回 null，而 CompiledGraph 会接着用它的返回值
                registry, new MemorySaver(),
                // summarizeThresholdTokens 给足，别让历史压缩掺进来干扰
                new SpringAIJacksonStateSerializer<>(MessagesState::new), 12, 999_999, 6, "X");
    }
}
