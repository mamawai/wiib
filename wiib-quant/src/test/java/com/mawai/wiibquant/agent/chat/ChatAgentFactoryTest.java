package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.llm.ChatEndpoints;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import com.mawai.wiibquant.agent.trader.TraderChatService;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.bsc.langgraph4j.state.AppenderChannel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.io.NotSerializableException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 叶子工厂本身：建出来的东西齐不齐、缓存有没有生效、序列化器挂没挂对。
 * 编排（派发/去重/汇总/落库）在 {@link ChatTurnRunnerTest}，不在这里。
 */
class ChatAgentFactoryTest {

    private final ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
    private final StateSerializer<MessagesState<Message>> serializer =
            new SpringAIJacksonStateSerializer<>(MessagesState::new);

    private ChatAgentFactory factory() {
        ChatModel model = mock(ChatModel.class);
        // 建叶子时 ChatService 会读 getOptions() 挂工具，null 会 NPE
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(model, model));
        return new ChatAgentFactory(chatModelFactory,
                mock(MarketToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisService.class), mock(TraderChatService.class),
                mock(WorkbenchRunRegistry.class),
                new ApprovalRegistry(), serializer, 12, 32000, 6, "X");
    }

    private static ChatEndpoints config(String model) {
        return ChatTestEndpoints.eps(1L, model);
    }

    @Test
    void 建出三个专家和一个汇总叶子() {
        ChatAgentFactory.Leaves leaves = factory().leavesFor(config("gpt-5"));

        // 保序：派发顺序、结论进历史的顺序都跟着它
        assertThat(leaves.experts()).containsOnlyKeys("market_agent", "news_agent", "trader_agent");
        assertThat(leaves.experts().keySet())
                .containsExactly("market_agent", "news_agent", "trader_agent");
        // news 走预取（无参工具，不指望模型自己调）；market 的工具要按问题选 symbol 只能现取，
        // trader 的四个工具各答一类问题，取哪个也得看问题
        assertThat(leaves.experts().get("news_agent").preload()).isNotNull();
        assertThat(leaves.experts().get("market_agent").preload()).isNull();
        assertThat(leaves.experts().get("trader_agent").preload()).isNull();
        assertThat(leaves.summarizer()).isNotNull();
        assertThat(leaves.light()).isNotNull();
    }

    /**
     * 同一份配置反复取是同一套叶子（一个用户一份——userId 是指纹的第一个分量）；
     * 配置一变指纹就变、自然拿到新叶子——不需要任何显式 evict，也就不会有"改了配置还用旧模型"。
     */
    @Test
    void 同配置共享叶子改配置后重建() {
        ChatAgentFactory factory = factory();

        ChatAgentFactory.Leaves first = factory.leavesFor(config("gpt-5"));

        assertThat(factory.leavesFor(config("gpt-5"))).isSameAs(first);
        assertThat(factory.leavesFor(config("gpt-5.1"))).isNotSameAs(first);
        // 两条 verify 各管一件事，都是 isSameAs 抓不到的：
        // 1) 每份配置只建一次。把"先查缓存"那步删掉，第二次照样重建一整套、再被 putIfAbsent
        //    换回旧的——断言全绿而每轮对话都在白建（实测过）
        // 2) 取模型时用的就是调用方给的这份配置，而不是别处随便来的一份
        verify(chatModelFactory).modelsFor(config("gpt-5"));
        verify(chatModelFactory).modelsFor(config("gpt-5.1"));
    }

    // ===== 序列化：叶子与会话上下文表共用的序列化器必须是 Jackson 版，默认的 Java 对象流存不下 Spring AI Message =====

    @Test
    void defaultObjectStreamSerializerCannotCloneSpringAiMessages() {
        StateGraph<MessagesState<Message>> graph = new StateGraph<>(MessagesState.SCHEMA, MessagesState::new);

        assertThatThrownBy(() -> graph.getStateSerializer()
                .cloneObject(Map.of("messages", List.of(new UserMessage("x")))))
                .isInstanceOf(NotSerializableException.class);
    }

    /** 叶子拿到的确实是注入进来那个序列化器（漏传的话它会自己兜一个默认的，落库当场炸） */
    @Test
    void leafSerializerCanCloneStateWithSpringAiMessages() throws Exception {
        ChatAgentFactory.Leaves leaves = factory().leavesFor(config("gpt-5"));

        MessagesState<Message> cloned = leaves.summarizer().stateGraph.getStateSerializer()
                .cloneObject(Map.of("messages", List.of(
                        new UserMessage("我只关注 ETH"), new AssistantMessage("记住了"))));

        assertThat(cloned.messages()).hasSize(2);
        assertThat(cloned.messages().getFirst().getText()).isEqualTo("我只关注 ETH");
        assertThat(leaves.summarizer().stateGraph.getStateSerializer()).isSameAs(serializer);
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
