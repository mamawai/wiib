package com.mawai.wiibagent.llm;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 保险丝的核心契约：它挂在**工具执行边**上，跳 END 时 state 最后一条必然是带 toolCalls 的
 * AssistantMessage。只跳不补 = 留下永远等不到 tool_result 的孤儿 tool_call，
 * 这段历史进 Responses API 就是 400（工作台会持久化它 → 该会话彻底报废）。
 */
class ModelCallLimiterTest {

    /** 占位回执文案生产上按语言取词表传入（llm.callLimit.notExecuted）；这里只验"传进去的原样落到回执上" */
    private static final String NOT_EXECUTED = "未执行：本轮模型调用已达上限，工具被跳过。";

    /** 一条带 toolCalls 的助手消息 + 已有调用计数 = 保险丝触发那一刻的 state */
    private static MessagesState<Message> stateAtToolEdge(int alreadyCalled, String... toolCallIds) {
        AssistantMessage.Builder builder = AssistantMessage.builder().content("");
        builder.toolCalls(java.util.Arrays.stream(toolCallIds)
                .map(id -> new AssistantMessage.ToolCall(id, "function", "get_account", "{}"))
                .toList());
        return new MessagesState<>(Map.of(
                "messages", List.of(new UserMessage("看看账户"), builder.build()),
                ModelCallLimiter.CALL_COUNT_KEY, alreadyCalled));
    }

    @SuppressWarnings("unchecked")
    private static List<Message> appendedMessages(Command command) {
        Object messages = command.update().get("messages");
        return messages instanceof List<?> list ? (List<Message>) list : List.of((Message) messages);
    }

    @Test
    void 达上限跳END时给未执行的工具调用补齐占位回执() {
        AtomicBoolean toolsRan = new AtomicBoolean();

        Command command = new ModelCallLimiter(3, NOT_EXECUTED).applyWrap("tools", stateAtToolEdge(2, "call_a", "call_b"),
                null, (s, c) -> {
                    toolsRan.set(true);
                    return CompletableFuture.completedFuture(Command.emptyCommand());
                }).join();

        assertThat(toolsRan).isFalse(); // 到顶了就不许再执行工具
        assertThat(command.gotoNode()).isEqualTo("end");
        assertThat(command.update()).containsEntry(ModelCallLimiter.CALL_COUNT_KEY, 3);

        // 每个未执行的 tool_call 都要有配对的 tool_result，否则这段历史一送上游就是 400
        List<Message> appended = appendedMessages(command);
        assertThat(appended).hasSize(1).allMatch(ToolResponseMessage.class::isInstance);
        ToolResponseMessage placeholder = (ToolResponseMessage) appended.getFirst();
        assertThat(placeholder.getResponses()).extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("call_a", "call_b");
        // 占位内容要说清"没执行"，模型/复盘看得懂，不是伪造的成功结果——传进去的文案原样落回执
        assertThat(placeholder.getResponses()).allSatisfy(r ->
                assertThat(r.responseData()).isEqualTo(NOT_EXECUTED));
    }

    @Test
    void 没有待执行工具调用时不补空回执() {
        // 理论上走不到（工具边前必有 tool_call），但补一条空 TRM 反而是新的孤儿
        MessagesState<Message> state = new MessagesState<>(Map.of(
                "messages", List.of(new UserMessage("你好"), new AssistantMessage("好的")),
                ModelCallLimiter.CALL_COUNT_KEY, 2));

        Command command = new ModelCallLimiter(3, NOT_EXECUTED).applyWrap("tools", state, null,
                (s, c) -> CompletableFuture.completedFuture(Command.emptyCommand())).join();

        assertThat(command.gotoNode()).isEqualTo("end");
        assertThat(command.update()).doesNotContainKey("messages");
    }

    /** 图上真跑一遍：被保险丝收束的最终 state 是要被工作台持久化的，孤儿 tool_call 会让该会话彻底报废 */
    public static class EchoTools {
        @Tool(description = "回声")
        public String echo(String text) {
            return text;
        }
    }

    @Test
    void 收束后的最终state里不留孤儿toolCall() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AtomicInteger round = new AtomicInteger();
        // 永远只想再调一次工具、永不收尾 → 只能靠保险丝收束（每轮独立 call id，同真实模型口径）
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            int i = round.incrementAndGet();
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("c" + i, "function", "echo",
                            "{\"text\":\"hi\"}")))
                    .build())));
        });

        CompiledGraph<MessagesState<Message>> graph = ReactAgent.<MessagesState<Message>>builder()
                .chatModel(model)
                .stateSerializer(new SpringAIJacksonStateSerializer<>(MessagesState::new))
                .toolsFromObject(new EchoTools())
                .addExecuteToolsHook(new ModelCallLimiter(3, NOT_EXECUTED))
                .build()
                .compile();

        MessagesState<Message> state = graph
                .invoke(Map.of("messages", List.of(new UserMessage("说点什么")))).orElseThrow();

        Set<String> called = state.messages().stream()
                .filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
                .flatMap(a -> a.getToolCalls().stream()).map(AssistantMessage.ToolCall::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> answered = state.messages().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(t -> t.getResponses().stream()).map(ToolResponseMessage.ToolResponse::id)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(called).isNotEmpty();
        assertThat(answered).containsExactlyInAnyOrderElementsOf(called);
    }

    @Test
    void 未到上限照常执行工具并累加计数() {
        AtomicBoolean toolsRan = new AtomicBoolean();

        Command command = new ModelCallLimiter(8, NOT_EXECUTED).applyWrap("tools", stateAtToolEdge(2, "call_a"),
                null, (s, c) -> {
                    toolsRan.set(true);
                    return CompletableFuture.completedFuture(Command.emptyCommand());
                }).join();

        assertThat(toolsRan).isTrue();
        assertThat(command.update()).containsEntry(ModelCallLimiter.CALL_COUNT_KEY, 3)
                .doesNotContainKey("messages"); // 工具真跑了，回执由 ExecuteToolsAction 出，不该有占位
    }
}
