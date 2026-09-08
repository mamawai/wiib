package com.mawai.wiibagent.llm;

import org.bsc.langgraph4j.hook.NodeHook;
import org.bsc.langgraph4j.state.AgentState;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 一次运行的中断信号，从 RunnableConfig 带到模型流上。
 * <p>
 * 调用方把 future 放进 config 的 metadata（{@link #CONFIG_KEY}）；{@link #hook()} 挂在模型节点上，
 * 在 action.apply 期间把它绑进 ScopedValue；{@link ResilientChatService} 建流时经 {@link #current()}
 * 读出来做 takeUntil。
 * <p>
 * 要绕这一趟是因为 ChatService 的签名只有 messages、拿不到 config，而流一建好就被 StreamingChatGenerator
 * 立刻订阅且不留 Disposable，只能在建流那一刻接上信号。ScopedValue 只在同线程的调用栈里可见：
 * CallModelAction 在 apply 里同步建流，所以这个 hook 必须直接调 action.apply，并注册在最内层
 * （先注册的在最内层）。没带信号的 config、不挂这个 hook 的图（trader/learning/专家）一切照旧。
 */
public final class CancelSignal {

    /** RunnableConfig metadata 键，值是 {@code CompletableFuture<Void>}，完成即中断 */
    public static final String CONFIG_KEY = "wiib_cancel";

    private static final ScopedValue<CompletableFuture<Void>> CURRENT = ScopedValue.newInstance();

    private CancelSignal() {
    }

    @SuppressWarnings("unchecked")
    public static <S extends AgentState> NodeHook.WrapCall<S> hook() {
        return (_, state, config, action) -> config.metadata(CONFIG_KEY)
                .map(signal -> ScopedValue.where(CURRENT, (CompletableFuture<Void>) signal)
                        .call(() -> action.apply(state, config)))
                .orElseGet(() -> action.apply(state, config));
    }

    /** 建流时读：不在带信号的调用栈里就是空 */
    static Optional<CompletableFuture<Void>> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }
}
