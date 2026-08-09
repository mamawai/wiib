package com.mawai.wiibquant.agent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.constant.QuantConstants;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.agent.Agent;
import org.bsc.langgraph4j.hook.EdgeHook;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 贵操作的 HITL 闸门，挂在工具执行边上。
 * <p>
 * <b>为什么在这一层而不在工具里</b>：授权要绑到"哪个会话、批准了哪个工具的哪个标的"。
 * 工具方法体看不到 sessionId（框架的 ChatService 签名里没有 RunnableConfig，
 * 而 ToolContext 是建图时算死的静态值），所以判断只能做在这里——
 * hook 同时拿得到 config.threadId() 和 tool_call 的 name/arguments。
 * <p>
 * <b>挂在哪</b>：不能挂在 summarizer 子图上（子图 hook 在内联进主图时会被框架丢掉，
 * 见 Task 5.6），而是挂在**父图**上并用 {@code ChatAgentFactory.onlyOnEdge} 收窄到
 * {@code "summarizer-action"} 这一条边。
 * <p>
 * <b>挂载顺序</b>：必须先于 {@link com.mawai.wiibquant.agent.llm.ModelCallLimiter} 注册。
 * langgraph4j 的 WrapCall 是 reduce 左折叠，<b>后注册的在外层先执行</b>，
 * 保险丝必须在外层——否则会出现"卡片弹了但模型没配额告诉用户"的窗口。
 * <p>
 * <b>拒绝标记的作用域是"本轮"</b>：它只该挡"用户拒绝后模型还想再调一次"那一瞬。
 * 用户重新发问 = 改主意，Controller 在每轮开跑时清掉它（见 Task 10），
 * 否则用户说"还是研判一下 BTC 吧"会被自己上一次的拒绝挡掉，得再说一遍才行。
 */
@Slf4j
public class ApprovalGate implements EdgeHook.WrapCall<MessagesState<Message>> {

    /** 唯一受管辖的贵操作：Bull∥Bear + Judge 三次深模型调用 */
    public static final String DEEP_ANALYSIS_TOOL = "run_deep_analysis";

    private static final String REASON = "深度研判需 3 次深模型调用（Bull/Bear 辩论 + Judge 裁决）";

    private final ApprovalRegistry registry;

    public ApprovalGate(ApprovalRegistry registry) {
        this.registry = registry;
    }

    @Override
    public CompletableFuture<Command> applyWrap(String nodeId, MessagesState<Message> state,
                                                RunnableConfig config,
                                                AsyncCommandAction<MessagesState<Message>> action) {
        String sessionId = config == null ? null : config.threadId().orElse(null);
        Optional<AssistantMessage.ToolCall> guarded = guardedCall(state);
        if (sessionId == null || guarded.isEmpty()) {
            return passThrough(sessionId, state, config, action);
        }
        AssistantMessage.ToolCall call = guarded.get();
        String symbol = normalizedSymbol(call);

        // 用户上一轮拒绝过同一件事：如实告诉模型，别再弹一次卡（标记是一次性的，
        // 用户改主意重新问时不该还被挡着）
        Optional<ApprovalRegistry.PendingRequest> rejected = registry.consumeRejected(sessionId);
        if (rejected.isPresent() && sameRequest(rejected.get(), call.name(), symbol)) {
            log.info("[HITL] 用户已拒绝，回执告知模型 session={} symbol={}", sessionId, symbol);
            return CompletableFuture.completedFuture(shortCircuit(state,
                    "用户已拒绝本次深度研判，请直接用现有专家数据作答，不要再次请求。"));
        }

        if (registry.consumeApproval(sessionId, call.name(), symbol)) {
            log.info("[HITL] 授权命中，放行 session={} tool={} symbol={}", sessionId, call.name(), symbol);
            return passThrough(sessionId, state, config, action);
        }

        // 又要弹卡 = 上一条授权已经用不上了（模型改口换了 symbol）。留着它，route() 会在
        // TTL 内一直跳过专家派发，用户之后每问一句都拿不到真数据，且没有任何日志说明原因
        registry.discardApprovals(sessionId);
        registry.requestApproval(sessionId, call.name(), symbol, REASON);
        log.info("[HITL] 未授权，登记待确认 session={} tool={} symbol={}", sessionId, call.name(), symbol);
        JSONObject out = new JSONObject();
        out.put("status", "PENDING_APPROVAL");
        out.put("message", "深度研判是昂贵操作（3 次深模型调用），已向用户请求确认。"
                + "请告知用户等待确认卡片，确认后你会被再次调用。");
        return CompletableFuture.completedFuture(shortCircuit(state, out.toJSONString()));
    }

    /**
     * 短路回模型节点。<b>gotoNode 必须是 {@code Agent.AGENT_LABEL}，不能是 null</b>：
     * {@code Command.gotoNode()} 是 {@code Objects.requireNonNull(gotoNode, "gotoNode cannot be null")}，
     * 而 {@code CompiledGraph.nextNodeId} 拿到 hook 返回值后第一件事就是调它——
     * 传 null 会在 HITL 第一次触发时当场 NPE，用户看到的是"研判失败"，卡片永远不弹。
     * <p>
     * 也不能像 {@link com.mawai.wiibquant.agent.llm.ModelCallLimiter} 那样跳 "end"：
     * 短路后要让模型看到回执并转述给用户。action 节点的 EdgeMappings 只有这两个合法值
     *（{@code Agent.Builder.build()}：{@code .to("agent").toEND("end")}）。
     */
    private static Command shortCircuit(MessagesState<Message> state, String body) {
        return new Command(Agent.AGENT_LABEL, Map.of("messages", List.of(reply(state, body))));
    }

    /**
     * 放行：把会话号放进 ThreadLocal 供工具推进度，执行完清掉。
     * <b>必须直接调 action.apply</b>——包一层 thenCompose 就换线程了，ThreadLocal 立刻失效。
     */
    private CompletableFuture<Command> passThrough(String sessionId, MessagesState<Message> state,
                                                   RunnableConfig config,
                                                   AsyncCommandAction<MessagesState<Message>> action) {
        if (sessionId != null) {
            ToolRunContext.set(sessionId);
        }
        try {
            return action.apply(state, config);
        } finally {
            ToolRunContext.clear();
        }
    }

    /** 本批 tool_call 里受管辖的那个（一批里最多关心一个贵操作）。 */
    private static Optional<AssistantMessage.ToolCall> guardedCall(MessagesState<Message> state) {
        return state.lastMessage()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(AssistantMessage::hasToolCalls)
                .flatMap(a -> a.getToolCalls().stream()
                        .filter(c -> DEEP_ANALYSIS_TOOL.equals(c.name()))
                        .findFirst());
    }

    /**
     * 标的必须归一化再参与授权键：模型可能填 btc / BTCUSDT / btcusdt，
     * 用原文组键会让"用户点了同意却弹第二次卡"，比不修更糟。
     */
    private static String normalizedSymbol(AssistantMessage.ToolCall call) {
        try {
            return approvalSymbol(JSON.parseObject(call.arguments()).getString("symbol"));
        } catch (Exception e) {
            // 参数解析不了也要有个确定的键，否则授权永远对不上
            return approvalSymbol(null);
        }
    }

    /**
     * 授权键专用归一：trim+大写之后把 USDT/USDC 后缀收掉再统一补 USDT，
     * 于是 btc / BTCUSDT / btcusdt 落到同一个键。
     * <p>
     * 没直接用 {@code QuantConstants} 的两个现成方法，各有原因：<br>
     * {@code normalizeSymbolLenient} 只 trim+大写，{@code btc} 归成 {@code BTC} 对不上
     * {@code BTCUSDT}，正是这里要防的那件事；<br>
     * {@code normalizeSymbol} 会对白名单外的标的抛错，而且它在算后缀时无条件
     * {@code substring(0, len-4)}，传 {@code BTC} 这种三字母当场
     * StringIndexOutOfBounds（实测）——闸门在工具执行边上，抛出去就是整轮对话失败。
     * <p>
     * 白名单外的标的（{@code SOL} 之类）保留自己的键、<b>不塌成 BTCUSDT</b>：
     * 塌了会让"批了 BTC"的授权把一个 SOL 请求放行进去，比不归一化更危险。
     */
    private static String approvalSymbol(String raw) {
        String upper = QuantConstants.normalizeSymbolLenient(raw);
        if (upper.endsWith("USDT") || upper.endsWith("USDC")) {
            upper = upper.substring(0, upper.length() - 4);
        }
        return upper.isBlank() ? "BTCUSDT" : upper + "USDT";
    }

    private static boolean sameRequest(ApprovalRegistry.PendingRequest req, String toolName, String symbol) {
        return req.toolName().equals(toolName) && req.symbol().equals(symbol);
    }

    /**
     * 短路时必须给**这一批**每个 tool_call 都配对回执。只回一条 = 留下孤儿 tool_call，
     * 这段历史被 checkpoint 持久化后，续聊重建时上游直接 400，会话只能删掉重开。
     */
    private static ToolResponseMessage reply(MessagesState<Message> state, String body) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        state.lastMessage()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(AssistantMessage::hasToolCalls)
                .ifPresent(a -> {
                    for (AssistantMessage.ToolCall c : a.getToolCalls()) {
                        responses.add(new ToolResponseMessage.ToolResponse(c.id(), c.name(),
                                DEEP_ANALYSIS_TOOL.equals(c.name()) ? body
                                        : "未执行：本轮存在待确认的贵操作。"));
                    }
                });
        return ToolResponseMessage.builder().responses(responses).build();
    }
}
