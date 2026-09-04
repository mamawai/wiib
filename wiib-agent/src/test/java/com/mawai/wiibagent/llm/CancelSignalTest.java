package com.mawai.wiibagent.llm;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgentBuilder;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 中断信号从 RunnableConfig 走到模型流：hook 只在 action 执行期间绑定，建流那一刻接上 takeUntil；
 * 信号到达时掐断在途流并把取消传到上游；流正常结束不能反过来把信号 future 取消掉。
 */
class CancelSignalTest {

    private static final List<Message> ASK = List.of(new UserMessage("BTC 现在怎么样"));

    private final ChatModel primary = mock(ChatModel.class);

    private ReactAgent.ChatService service() {
        ReactAgentBuilder<?, ?> agentBuilder = mock(ReactAgentBuilder.class);
        when(agentBuilder.tools()).thenReturn(List.of());
        when(agentBuilder.systemMessage()).thenReturn(Optional.of("你是助手"));
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        return ResilientChatService.builder().model(primary).asFactory().apply(agentBuilder);
    }

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static RunnableConfig configWith(CompletableFuture<Void> signal) {
        return RunnableConfig.builder().addMetadata(CancelSignal.CONFIG_KEY, signal).build();
    }

    /** 在 hook 包住的调用栈里建流（CallModelAction 就是在 apply 里同步建流的） */
    private Flux<ChatResponse> streamBuiltUnderHook(RunnableConfig config) {
        ReactAgent.ChatService service = service();
        AtomicReference<Flux<ChatResponse>> built = new AtomicReference<>();
        CancelSignal.<MessagesState<Message>>hook().applyWrap("agent", new MessagesState<>(Map.of()), config,
                (state, cfg) -> {
                    built.set(service.streamingExecute(ASK));
                    return CompletableFuture.completedFuture(Map.of());
                }).join();
        return built.get();
    }

    @Test
    void 信号到达时掐断在途流并取消上游() {
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        when(primary.stream(any(Prompt.class))).thenReturn(
                Flux.<ChatResponse>never().doOnCancel(() -> upstreamCancelled.set(true)));
        CompletableFuture<Void> signal = new CompletableFuture<>();
        AtomicBoolean completed = new AtomicBoolean();

        streamBuiltUnderHook(configWith(signal)).subscribe(r -> { }, e -> { }, () -> completed.set(true));
        assertThat(upstreamCancelled).isFalse();

        signal.complete(null);

        assertThat(upstreamCancelled).isTrue();   // 取消传到了模型层：自研协议断连、openai 协议关 SDK 流
        assertThat(completed).isTrue();           // 下游看到的是正常结束，不是异常
    }

    /** Mono.fromFuture 会在流结束时反向 cancel 这个 future，专家等待期的 anyOf 就被误唤醒了 */
    @Test
    void 流正常结束不会反向取消信号() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.just(responseOf("答完了")));
        CompletableFuture<Void> signal = new CompletableFuture<>();

        List<ChatResponse> out = streamBuiltUnderHook(configWith(signal)).collectList().block();

        assertThat(out).hasSize(1);
        assertThat(signal.isDone()).isFalse();
        assertThat(signal.isCancelled()).isFalse();
    }

    @Test
    void 信号只在action执行期间可见() {
        CompletableFuture<Void> signal = new CompletableFuture<>();
        AtomicReference<Optional<CompletableFuture<Void>>> seen = new AtomicReference<>();

        CancelSignal.<MessagesState<Message>>hook().applyWrap("agent", new MessagesState<>(Map.of()),
                configWith(signal), (state, cfg) -> {
                    seen.set(CancelSignal.current());
                    return CompletableFuture.completedFuture(Map.of());
                }).join();

        assertThat(seen.get()).contains(signal);
        assertThat(CancelSignal.current()).isEmpty();   // 出了 action 就解绑
    }

    @Test
    void config没带信号时原样放行() {
        AtomicReference<Optional<CompletableFuture<Void>>> seen = new AtomicReference<>();

        CancelSignal.<MessagesState<Message>>hook().applyWrap("agent", new MessagesState<>(Map.of()),
                RunnableConfig.builder().build(), (state, cfg) -> {
                    seen.set(CancelSignal.current());
                    return CompletableFuture.completedFuture(Map.of("k", "v"));
                }).thenAccept(result -> assertThat(result).containsEntry("k", "v")).join();

        assertThat(seen.get()).isEmpty();
    }
}
