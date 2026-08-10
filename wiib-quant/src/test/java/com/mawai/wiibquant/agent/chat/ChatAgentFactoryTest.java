package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.entity.UserLlmConfig;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.bsc.langgraph4j.state.AppenderChannel;
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

import java.io.NotSerializableException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAgentFactoryTest {

    private final ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
    private final ApprovalRegistry approvalRegistry = new ApprovalRegistry();

    private ChatAgentFactory factory() {
        ChatModel model = mock(ChatModel.class);
        // 建图时 ChatService 会读 getOptions() 挂工具，null 会 NPE
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(model, model));
        return new ChatAgentFactory(chatModelFactory,
                mock(MarketToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisService.class), mock(WorkbenchRunRegistry.class),
                approvalRegistry, mock(BaseCheckpointSaver.class),
                new SpringAIJacksonStateSerializer<>(MessagesState::new), 12, 32000, 6, "X");
    }

    private static UserLlmConfig config(String model) {
        UserLlmConfig c = new UserLlmConfig();
        c.setUserId(1L);
        c.setApiProtocol("openai");
        c.setBaseUrl("https://api.example.com");
        c.setModel(model);
        // 固定密文而不是真加密：ApiKeyCrypto 是 AES-GCM 随机 IV，真加密的话同一份配置
        // 两次调用会算出不同指纹，下面那条 isSameAs 必挂
        c.setApiKeyEnc("enc-fixed");
        return c;
    }

    private static RunnableConfig runConfig() {
        return RunnableConfig.builder().threadId("wb-1-t").build();
    }

    /** 路由用的 tool_call：模型按 route 工具的 schema 结构化地给出去向 */
    private static AssistantMessage routeCall(String argumentsJson) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "route", argumentsJson)))
                .build();
    }

    @Test
    void buildsRouterDispatchExpertsAndSummarizer() {
        CompiledGraph<MessagesState<Message>> graph = factory().chatGraph(config("gpt-5"));

        assertThat(graph).isNotNull();
        String mermaid = graph.stateGraph
                .getGraph(GraphRepresentation.Type.MERMAID, "workbench").content();
        assertThat(mermaid).contains("market_agent").contains("news_agent").doesNotContain("quant_agent");
        // 路由与汇总拆成两个角色：router 只决定去向，summarizer 只写答案
        assertThat(mermaid).contains("router").contains("dispatch").contains("join").contains("summarizer");
    }

    /**
     * 同配置的用户共享同一张图（实际部署里大家多半用同一个中转 + 同一模型）；
     * 配置一变指纹就变、自然拿到新图——不需要任何显式 evict，也就不会有"改了配置还用旧模型"。
     */
    @Test
    void 同配置共享图改配置后重建() {
        ChatAgentFactory factory = factory();

        CompiledGraph<MessagesState<Message>> first = factory.chatGraph(config("gpt-5"));

        assertThat(factory.chatGraph(config("gpt-5"))).isSameAs(first);
        assertThat(factory.chatGraph(config("gpt-5.1"))).isNotSameAs(first);
        // 两条 verify 各管一件事，都是 isSameAs 抓不到的：
        // 1) 每份配置只建一次。把"先查缓存"那步删掉，第二次照样重建一整张图、再被 putIfAbsent
        //    换回旧图——断言全绿而每轮对话都在白建图（实测过）
        // 2) 取模型时用的就是调用方给的这份配置，而不是别处随便来的一份
        verify(chatModelFactory).modelsFor(config("gpt-5"));
        verify(chatModelFactory).modelsFor(config("gpt-5.1"));
    }

    // ===== 结构化路由：只认 tool_call 参数，绝不解析消息文本 =====

    @Test
    void parsesExpertNamesFromRouteToolCall() {
        List<String> next = ChatAgentFactory.parseRouteCall(
                routeCall("{\"next\":[\"news_agent\",\"market_agent\"]}"));

        assertThat(next).containsExactly("news_agent", "market_agent");
    }

    @Test
    void parsesFinishFromRouteToolCall() {
        assertThat(ChatAgentFactory.parseRouteCall(routeCall("{\"next\":[\"FINISH\"]}")))
                .containsExactly(ChatAgentFactory.FINISH);
    }

    @Test
    void dropsUnknownAgentNames() {
        assertThat(ChatAgentFactory.parseRouteCall(
                routeCall("{\"next\":[\"news_agent\",\"weather_agent\"]}")))
                .containsExactly("news_agent");
    }

    @Test
    void noToolCallMeansNoDispatch() {
        // 模型没调 route（理论上被 tool_choice=required 挡住，兜底也要安全）→ 空名单 → 转汇总
        assertThat(ChatAgentFactory.parseRouteCall(new AssistantMessage("我直接回答吧"))).isEmpty();
    }

    @Test
    void malformedArgumentsDoNotBlowUp() {
        assertThat(ChatAgentFactory.parseRouteCall(routeCall("{\"next\":[]}"))).isEmpty();
    }

    @Test
    void conditionalEdgeReadsStateNotMessages() {
        MessagesState<Message> dispatching = new MessagesState<>(
                Map.of(ChatAgentFactory.NEXT_KEY, "dispatch"));
        MessagesState<Message> finishing = new MessagesState<>(
                Map.of(ChatAgentFactory.NEXT_KEY, ChatAgentFactory.FINISH));

        assertThat(ChatAgentFactory.nextFromState(dispatching)).isEqualTo("dispatch");
        assertThat(ChatAgentFactory.nextFromState(finishing)).isEqualTo("summarize");
        // 没有路由结果时保守收尾，不能悬空
        assertThat(ChatAgentFactory.nextFromState(new MessagesState<>(Map.of()))).isEqualTo("summarize");
    }

    @Test
    void dispatchesExpertsWhenRouterSaysSo() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(routeCall("{\"next\":[\"news_agent\"]}")))));
        MessagesState<Message> state = new MessagesState<>(
                Map.of("messages", List.of(new UserMessage("帮我查最新快讯"))));

        Map<String, Object> update = factory().route(state, model, runConfig());

        assertThat(update)
                .containsEntry(ChatAgentFactory.NEXT_KEY, "dispatch")
                .containsEntry(ChatAgentFactory.DISPATCH_KEY, List.of("news_agent"))
                .containsEntry(ChatAgentFactory.DISPATCH_ROUND_KEY, 1);
        // 路由结果只进 state，不许混进对话历史——这正是之前被模型照抄导致反复派发的根源
        assertThat(update).doesNotContainKey("messages");
    }

    /** 同一专家取过的数不会变，重复派只会空转烧钱——死循环就是这么来的，靠代码收敛不指望模型自觉 */
    @Test
    void doesNotDispatchTheSameExpertTwice() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(routeCall("{\"next\":[\"news_agent\"]}")))));
        MessagesState<Message> state = new MessagesState<>(Map.of(
                "messages", List.of(new UserMessage("帮我查最新快讯")),
                ChatAgentFactory.DISPATCHED_KEY, List.of("news_agent")));

        assertThat(factory().route(state, model, runConfig()))
                .containsEntry(ChatAgentFactory.NEXT_KEY, ChatAgentFactory.FINISH);
    }

    @Test
    void accumulatesDispatchedExpertsAcrossRounds() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(routeCall("{\"next\":[\"news_agent\",\"market_agent\"]}")))));
        MessagesState<Message> state = new MessagesState<>(Map.of(
                "messages", List.of(new UserMessage("结合行情和新闻看看")),
                ChatAgentFactory.DISPATCHED_KEY, List.of("news_agent")));

        Map<String, Object> update = factory().route(state, model, runConfig());

        // 只派没派过的那个，但累积名单要含全部
        assertThat(update).containsEntry(ChatAgentFactory.DISPATCH_KEY, List.of("market_agent"));
        assertThat(update.get(ChatAgentFactory.DISPATCHED_KEY))
                .isEqualTo(List.of("news_agent", "market_agent"));
    }

    @Test
    void routerFailureDegradesToSummarize() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("上游挂了"));
        MessagesState<Message> state = new MessagesState<>(
                Map.of("messages", List.of(new UserMessage("帮我查最新快讯"))));

        // 路由失败不该把整轮对话拖死，退化成直接作答
        assertThat(factory().route(state, model, runConfig()))
                .containsEntry(ChatAgentFactory.NEXT_KEY, ChatAgentFactory.FINISH);
    }

    /**
     * 深研判确认后的续跑轮：存在未消费授权 → 直通汇总让 summarizer 重调工具。
     * 专家数据上一轮刚取过且深研判不消费它们，重派一遍纯浪费（真跑实证过会重派）。
     */
    @Test
    void unconsumedApprovalSkipsDispatchStraightToSummarizer() {
        ChatModel model = mock(ChatModel.class);
        // 与 runConfig() 的 threadId 同一会话
        approvalRegistry.requestApproval("wb-1-t", "run_deep_analysis", "BTCUSDT", "贵操作");
        approvalRegistry.approve("wb-1-t",
                approvalRegistry.peekPending("wb-1-t").orElseThrow().requestId());

        Map<String, Object> update = factory().route(new MessagesState<>(
                Map.of("messages", List.of(new UserMessage("已确认，请继续执行深度研判")))), model, runConfig());

        assertThat(update).containsEntry(ChatAgentFactory.NEXT_KEY, ChatAgentFactory.FINISH);
        verify(model, never()).call(any(Prompt.class)); // 直通不烧路由调用
    }

    /**
     * 主图回环的保险丝。ModelCallLimiter 挂在 agent 的工具边上，管不到
     * router → dispatch → 专家 → join → router 这条回环，只能自己数。
     */
    @Test
    void stopsDispatchingAtRoundLimitWithoutCallingModel() {
        ChatModel model = mock(ChatModel.class);
        MessagesState<Message> state = new MessagesState<>(Map.of(
                "messages", List.of(new UserMessage("帮我查最新快讯")),
                ChatAgentFactory.DISPATCH_ROUND_KEY, ChatAgentFactory.MAX_DISPATCH_ROUNDS));

        Map<String, Object> update = factory().route(state, model, runConfig());

        assertThat(update).containsEntry(ChatAgentFactory.NEXT_KEY, ChatAgentFactory.FINISH);
        verify(model, never()).call(any(Prompt.class)); // 到顶了就别再烧一次调用
    }

    // ===== checkpoint 序列化：图必须显式挂 Jackson 版，默认的 Java 对象流存不下 Spring AI Message =====

    @Test
    void defaultObjectStreamSerializerCannotCloneSpringAiMessages() {
        StateGraph<MessagesState<Message>> graph = new StateGraph<>(MessagesState.SCHEMA, MessagesState::new);

        assertThatThrownBy(() -> graph.getStateSerializer()
                .cloneObject(Map.of("messages", List.of(new UserMessage("x")))))
                .isInstanceOf(NotSerializableException.class);
    }

    @Test
    void mainGraphSerializerCanCloneStateWithSpringAiMessages() throws Exception {
        CompiledGraph<MessagesState<Message>> graph = factory().chatGraph(config("gpt-5"));

        MessagesState<Message> cloned = graph.stateGraph.getStateSerializer()
                .cloneObject(Map.of("messages", List.of(
                        new UserMessage("我只关注 ETH"), new AssistantMessage("记住了"))));

        assertThat(cloned.messages()).hasSize(2);
        assertThat(cloned.messages().getFirst().getText()).isEqualTo("我只关注 ETH");
    }

    // ===== 长对话压缩：压缩结果必须活着进 state，否则每次调用都要重压 =====

    @Test
    void compressionSurvivesMergeWithModelResult() {
        List<Message> compressed = List.of(new UserMessage("原始问题"), new SystemMessage("## 早前对话摘要：…"));
        Map<String, Object> compression = Map.of("messages", new AppenderChannel.ReplaceAllWith<>(compressed));
        Map<String, Object> modelResult = Map.of("messages", new AssistantMessage("本轮回答"));

        Map<String, Object> merged = ChatAgentFactory.mergeUpdates(compression, modelResult);

        assertThat(merged.get("messages")).isInstanceOf(AppenderChannel.ReplaceAllWith.class);
        List<?> values = ((AppenderChannel.ReplaceAllWith<?>) merged.get("messages")).newValues();
        assertThat(values).hasSize(3); // 压缩后 2 条 + 模型本轮 1 条
        assertThat(((Message) values.getLast()).getText()).isEqualTo("本轮回答");
    }

    @Test
    void modelResultPassesThroughWhenNoCompression() {
        Map<String, Object> merged = ChatAgentFactory.mergeUpdates(
                Map.of(), Map.of("messages", new AssistantMessage("直接回答")));

        assertThat(merged.get("messages")).isInstanceOf(AssistantMessage.class);
    }
}
