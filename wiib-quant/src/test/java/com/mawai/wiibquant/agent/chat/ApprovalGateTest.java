package com.mawai.wiibquant.agent.chat;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HITL 闸门的核心契约：判断必须发生在同时看得到 sessionId(RunnableConfig)
 * 和工具名/参数(tool_call) 的这一层。工具方法体两样都看不到——那正是
 * "卡片说 BTC、实际能跑 ETH"这个洞的根源。
 */
class ApprovalGateTest {

    private final ApprovalRegistry registry = new ApprovalRegistry();
    private final ApprovalGate gate = new ApprovalGate(registry);

    private static final String SESSION = "wb-1-abc";

    private static MessagesState<Message> stateWithToolCall(String toolName, String argsJson) {
        return new MessagesState<>(Map.of("messages", List.of(
                new UserMessage("深度研判一下"),
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", toolName, argsJson)))
                        .build())));
    }

    private static RunnableConfig config() {
        return RunnableConfig.builder().threadId(SESSION).build();
    }

    @SuppressWarnings("unchecked")
    private static ToolResponseMessage responseOf(Command command) {
        Object messages = command.update().get("messages");
        List<Message> list = messages instanceof List<?> l ? (List<Message>) l : List.of((Message) messages);
        return (ToolResponseMessage) list.getFirst();
    }

    /** 未授权时不许烧深模型：直接短路并回一条 PENDING_APPROVAL，让模型转述给用户 */
    @Test
    void 未授权时短路并登记待确认() {
        AtomicBoolean toolRan = new AtomicBoolean();

        Command command = gate.applyWrap("tools",
                stateWithToolCall("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"),
                config(), (s, c) -> {
                    toolRan.set(true);
                    return CompletableFuture.completedFuture(Command.emptyCommand());
                }).join();

        assertThat(toolRan).isFalse();
        // 短路后必须回到模型节点：gotoNode 为 null 会在 CompiledGraph.nextNodeId 里
        // Objects.requireNonNull 直接 NPE（实跑验证过），而 "end" 会让模型没机会
        // 把"卡片已弹出"说给用户
        assertThat(command.gotoNode()).isEqualTo("agent");
        assertThat(responseOf(command).getResponses()).singleElement()
                .satisfies(r -> assertThat(r.responseData()).contains("PENDING_APPROVAL"));
        assertThat(registry.peekPending(SESSION)).isPresent()
                .get().satisfies(p -> {
                    assertThat(p.toolName()).isEqualTo("run_deep_analysis");
                    assertThat(p.symbol()).isEqualTo("BTCUSDT");
                });
    }

    /** 批准 BTC 之后模型改口要 ETH：key 对不上，必须重新弹卡而不是放行 */
    @Test
    void 批准的标的之外不放行() {
        gate.applyWrap("tools", stateWithToolCall("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"),
                config(), (s, c) -> CompletableFuture.completedFuture(Command.emptyCommand())).join();
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());
        AtomicBoolean toolRan = new AtomicBoolean();

        Command command = gate.applyWrap("tools",
                stateWithToolCall("run_deep_analysis", "{\"symbol\":\"ETHUSDT\"}"),
                config(), (s, c) -> {
                    toolRan.set(true);
                    return CompletableFuture.completedFuture(Command.emptyCommand());
                }).join();

        assertThat(toolRan).isFalse();
        assertThat(command.gotoNode()).isEqualTo("agent");   // 同上：不能是 null 也不能是 end
        assertThat(responseOf(command).getResponses()).singleElement()
                .satisfies(r -> assertThat(r.responseData()).contains("PENDING_APPROVAL"));
    }

    /**
     * symbol 是模型填的，可能是 btc / BTCUSDT / btcusdt。
     * 不归一化就会出现"用户点了同意却弹第二次卡"——比不修更糟。
     * <p>
     * 弹卡填 {@code btc}、续跑轮改口写 {@code btcusdt}：后缀写法和大小写全变了，
     * 仍须命中同一条授权（{@code BTCUSDT} 那种原样写法由"未授权时短路并登记待确认"覆盖）
     */
    @Test
    void 标的归一化后能对上授权() {
        gate.applyWrap("tools", stateWithToolCall("run_deep_analysis", "{\"symbol\":\"btc\"}"),
                config(), (s, c) -> CompletableFuture.completedFuture(Command.emptyCommand())).join();
        assertThat(registry.peekPending(SESSION).orElseThrow().symbol()).isEqualTo("BTCUSDT");
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());
        AtomicBoolean toolRan = new AtomicBoolean();

        gate.applyWrap("tools", stateWithToolCall("run_deep_analysis", "{\"symbol\":\"btcusdt\"}"),
                config(), (s, c) -> {
                    toolRan.set(true);
                    return CompletableFuture.completedFuture(Command.emptyCommand());
                }).join();

        assertThat(toolRan).isTrue();
    }

    /**
     * 白名单外的标的必须保留自己的键。归一化要收拢写法，但兜底一旦塌成 BTCUSDT，
     * "批了 BTC"的那条授权就会把一个 SOL 请求直接放行——比不归一化更危险
     */
    @Test
    void 白名单外的标的不蹭已有授权() {
        gate.applyWrap("tools", stateWithToolCall("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"),
                config(), (s, c) -> CompletableFuture.completedFuture(Command.emptyCommand())).join();
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());
        AtomicBoolean toolRan = new AtomicBoolean();

        gate.applyWrap("tools", stateWithToolCall("run_deep_analysis", "{\"symbol\":\"SOLUSDT\"}"),
                config(), (s, c) -> {
                    toolRan.set(true);
                    return CompletableFuture.completedFuture(Command.emptyCommand());
                }).join();

        assertThat(toolRan).isFalse();
        assertThat(registry.peekPending(SESSION).orElseThrow().symbol()).isEqualTo("SOLUSDT");
    }

    /** 用户拒绝后再问同样的问题，应该告诉模型"用户拒了"，而不是又弹一次卡 */
    @Test
    void 拒绝后回执说明原因不再弹卡() {
        gate.applyWrap("tools", stateWithToolCall("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"),
                config(), (s, c) -> CompletableFuture.completedFuture(Command.emptyCommand())).join();
        registry.reject(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());

        Command command = gate.applyWrap("tools",
                stateWithToolCall("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"),
                config(), (s, c) -> CompletableFuture.completedFuture(Command.emptyCommand())).join();

        assertThat(command.gotoNode()).isEqualTo("agent");   // 同上：不能是 null 也不能是 end
        assertThat(responseOf(command).getResponses()).singleElement()
                .satisfies(r -> assertThat(r.responseData()).contains("拒绝"));
        assertThat(registry.peekPending(SESSION)).isEmpty(); // 没有再登记新的待确认
    }

    /**
     * 又要弹卡说明上一条授权已经用不上了（模型改口换了 symbol）。留着它，
     * route() 十分钟内会一直"跳过专家派发直通汇总"，用户每问一句都拿不到真数据——
     * 静默降级，日志里一个字都不会提
     */
    @Test
    void 重新弹卡前丢弃用不上的旧授权() {
        gate.applyWrap("tools", stateWithToolCall("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"),
                config(), (s, c) -> CompletableFuture.completedFuture(Command.emptyCommand())).join();
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());

        // 模型改口要 ETH：授权对不上 → 重新弹卡，此时 BTC 那条授权必须被丢掉
        gate.applyWrap("tools", stateWithToolCall("run_deep_analysis", "{\"symbol\":\"ETHUSDT\"}"),
                config(), (s, c) -> CompletableFuture.completedFuture(Command.emptyCommand())).join();

        assertThat(registry.hasApproval(SESSION)).isFalse();
    }

    /**
     * 拒的是 BTC，模型改口问 ETH——那条拒绝管不着 ETH，必须照常弹新卡。
     * 不比对是哪件事就会出现"问什么都被上一次的拒绝挡回去"
     */
    @Test
    void 拒绝标记只挡被拒的那件事() {
        gate.applyWrap("tools", stateWithToolCall("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"),
                config(), (s, c) -> CompletableFuture.completedFuture(Command.emptyCommand())).join();
        registry.reject(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());

        Command command = gate.applyWrap("tools",
                stateWithToolCall("run_deep_analysis", "{\"symbol\":\"ETHUSDT\"}"),
                config(), (s, c) -> CompletableFuture.completedFuture(Command.emptyCommand())).join();

        assertThat(responseOf(command).getResponses()).singleElement()
                .satisfies(r -> assertThat(r.responseData()).contains("PENDING_APPROVAL"));
        assertThat(registry.peekPending(SESSION)).isPresent()
                .get().satisfies(p -> assertThat(p.symbol()).isEqualTo("ETHUSDT"));
    }

    /**
     * 短路时同批每个 tool_call 都得配一条回执。少回一条就留下孤儿 tool_call，
     * 这段历史落进 checkpoint 之后续聊重建，上游直接 400，会话只能删掉重开
     */
    @Test
    void 短路时同批每个toolcall都配回执() {
        MessagesState<Message> state = new MessagesState<>(Map.of("messages", List.of(
                new UserMessage("深度研判一下，顺便看眼行情"),
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("c1", "function",
                                        "run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"),
                                new AssistantMessage.ToolCall("c2", "function",
                                        "market_snapshot", "{\"symbol\":\"BTCUSDT\"}")))
                        .build())));

        Command command = gate.applyWrap("tools", state, config(),
                (s, c) -> CompletableFuture.completedFuture(Command.emptyCommand())).join();

        assertThat(responseOf(command).getResponses())
                .extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("c1", "c2");
    }

    /** 非贵操作的工具（专家那些）不该被闸门碰，原样放行 */
    @Test
    void 其他工具原样放行() {
        AtomicBoolean toolRan = new AtomicBoolean();

        gate.applyWrap("tools", stateWithToolCall("market_snapshot", "{\"symbol\":\"BTCUSDT\"}"),
                config(), (s, c) -> {
                    toolRan.set(true);
                    return CompletableFuture.completedFuture(Command.emptyCommand());
                }).join();

        assertThat(toolRan).isTrue();
    }

    /**
     * 工具方法体要靠 ThreadLocal 拿会话号推进度；放行时必须设上，执行完必须清掉。
     * <p>
     * <b>action 故意返回一个"稍后才 complete"的 future</b>：真正的约束是
     * "hook 必须在同步栈里直接调 action.apply，不能先 thenCompose"——
     * 若 action 用同步的 completedFuture，实现写成 {@code action.apply(s,c).thenCompose(...)}
     * 或 {@code .whenComplete((r,e) -> clear())} 测试照样绿，那个约束就没被钉住
     */
    @Test
    void 放行时在同步栈里就能读到会话号执行后清掉() {
        gate.applyWrap("tools", stateWithToolCall("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"),
                config(), (s, c) -> CompletableFuture.completedFuture(Command.emptyCommand())).join();
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());
        AtomicReference<String> seen = new AtomicReference<>();
        CompletableFuture<Command> pendingFuture = new CompletableFuture<>();

        gate.applyWrap("tools", stateWithToolCall("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"),
                config(), (s, c) -> {
                    seen.set(ToolRunContext.sessionId());   // 同步栈里必须已经读得到
                    return pendingFuture;
                });

        assertThat(seen.get()).isEqualTo(SESSION);
        // action 尚未完成，但 applyWrap 已经返回 → finally 已跑过，ThreadLocal 必须干净
        assertThat(ToolRunContext.sessionId()).isNull();
        pendingFuture.complete(Command.emptyCommand());
    }
}
