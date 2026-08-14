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
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 贵操作的 HITL 闸门，挂在工具执行边上。
 * <p>
 * <b>为什么在这一层而不在工具里</b>：授权要绑到"哪个会话、批准了哪个工具的哪个标的"。
 * 工具方法体看不到 sessionId（框架的 ChatService 签名里没有 RunnableConfig，
 * 而 ToolContext 是建图时算死的静态值），所以判断只能做在这里——
 * hook 同时拿得到 config.threadId() 和 tool_call 的 name/arguments。
 * <p>
 * <b>挂在哪</b>：summarizer 叶子的工具边，走 {@code ReactAgent.Builder.addExecuteToolsHook}
 * 直接注册（叶子是独立 {@code compile()} 的，官方挂载点真生效）。
 * <p>
 * <b>挂载顺序</b>：必须先于 {@link com.mawai.wiibquant.agent.llm.ModelCallLimiter} 注册。
 * langgraph4j 的 WrapCall 是 reduce 左折叠，<b>后注册的在外层先执行</b>，
 * 保险丝必须在外层——否则会出现"卡片弹了但模型没配额告诉用户"的窗口。
 * <p>
 * <b>拒绝标记跨轮活着，而且必须如此</b>：卡片是一轮结束时才发出去的，用户点拒绝必然发生在
 * 两轮之间，下一轮模型重提同一件事时才轮到这里回执。它是一次性的——被读走就没了，
 * 所以用户改主意重新问不会被上一次的拒绝挡住。
 */
@Slf4j
public class ApprovalGate implements EdgeHook.WrapCall<MessagesState<Message>> {

    /** Bull∥Bear + Judge 三次深模型调用 */
    public static final String DEEP_ANALYSIS_TOOL = "run_deep_analysis";
    /** 手动唤醒 trader：会真下单 */
    public static final String WAKE_TRADER_TOOL = "wake_trader";
    /** 点播复盘：烧一次深模型 */
    public static final String REVIEW_TRADER_TOOL = "review_trader_now";

    /**
     * 受管辖的贵操作。判据是"烧钱或动真钱"，不是"慢"——
     * {@code leave_note_to_trader} 只写一行字，拦它只会平白多一次点击。
     */
    static final Set<String> GUARDED_TOOLS =
            Set.of(DEEP_ANALYSIS_TOOL, WAKE_TRADER_TOOL, REVIEW_TRADER_TOOL);

    /**
     * 无标的的贵操作在授权三元组里占的第三格，同时也是确认卡上显示的"标的"。
     * 三元组不能缺格（缺了授权就退化成"十分钟内的通用票"），而这两个工具的作用域
     * 天然就是"这个用户的那一个 trader"——每人只有一个，用固定值足够精确。
     */
    static final String TRADER_SCOPE = "我的 trader";

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
        String symbol = approvalKey(call);

        // 用户上一轮拒绝过同一件事：如实告诉模型，别再弹一次卡（标记是一次性的，
        // 用户改主意重新问时不该还被挡着）
        Optional<ApprovalRegistry.PendingRequest> rejected = registry.consumeRejected(sessionId);
        if (rejected.isPresent() && sameRequest(rejected.get(), call.name(), symbol)) {
            log.info("[HITL] 用户已拒绝，回执告知模型 session={} tool={} symbol={}",
                    sessionId, call.name(), symbol);
            return CompletableFuture.completedFuture(shortCircuit(state, call.id(),
                    "用户已拒绝本次" + label(call.name()) + "，请如实告知并用现有数据作答，不要再次请求。"));
        }

        if (registry.consumeApproval(sessionId, call.name(), symbol)) {
            log.info("[HITL] 授权命中，放行 session={} tool={} symbol={}", sessionId, call.name(), symbol);
            return passThrough(sessionId, state, config, action);
        }

        // 又要弹卡 = 上一条授权已经用不上了（模型改口换了 symbol、或换了另一个贵操作）。留着它，
        // ChatTurnRunner 会在 TTL 内一直跳过专家派发，用户之后每问一句都拿不到真数据，
        // 且没有任何日志说明原因
        registry.discardApprovals(sessionId);
        registry.requestApproval(sessionId, call.name(), symbol, reason(call.name()));
        log.info("[HITL] 未授权，登记待确认 session={} tool={} symbol={}", sessionId, call.name(), symbol);
        JSONObject out = new JSONObject();
        out.put("status", "PENDING_APPROVAL");
        out.put("message", label(call.name()) + "是昂贵操作（" + reason(call.name())
                + "），已向用户请求确认。请告知用户等待确认卡片，确认后你会被再次调用。");
        return CompletableFuture.completedFuture(shortCircuit(state, call.id(), out.toJSONString()));
    }

    /** 确认卡与回执上的中文名。 */
    private static String label(String toolName) {
        return switch (toolName) {
            case WAKE_TRADER_TOOL -> "手动唤醒 trader";
            case REVIEW_TRADER_TOOL -> "点播复盘";
            default -> "深度研判";
        };
    }

    /** 卡片上给用户看的代价说明——用户要为"贵在哪"点头，笼统说一句"这很贵"等于没说。 */
    private static String reason(String toolName) {
        return switch (toolName) {
            case WAKE_TRADER_TOOL -> "将真实执行一次交易决策，可能开/平仓";
            case REVIEW_TRADER_TOOL -> "将消耗一次深模型复盘调用";
            default -> "深度研判需 3 次深模型调用（Bull/Bear 辩论 + Judge 裁决）";
        };
    }

    /** 授权三元组的第三格：深研判按标的归一，trader 动作用固定作用域（见 {@link #TRADER_SCOPE}）。 */
    private static String approvalKey(AssistantMessage.ToolCall call) {
        return DEEP_ANALYSIS_TOOL.equals(call.name()) ? normalizedSymbol(call) : TRADER_SCOPE;
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
    private static Command shortCircuit(MessagesState<Message> state, String guardedCallId, String body) {
        return new Command(Agent.AGENT_LABEL,
                Map.of("messages", List.of(reply(state, guardedCallId, body))));
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

    /** 本批 tool_call 里受管辖的那个（一批里最多处理一个贵操作，其余的连同它一起等下一轮）。 */
    private static Optional<AssistantMessage.ToolCall> guardedCall(MessagesState<Message> state) {
        return state.lastMessage()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(AssistantMessage::hasToolCalls)
                .flatMap(a -> a.getToolCalls().stream()
                        .filter(c -> GUARDED_TOOLS.contains(c.name()))
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
     * <b>{@link DeepAnalysisToolkit} 执行时共用这一个方法</b>，不是"顺手复用"：闸门用它算授权键、
     * 也用它写进确认卡的 symbol，工具要是另算一套（比如只 trim+大写），模型填 {@code btc} 时
     * 卡片写着 BTCUSDT、工具却拿 {@code BTC} 去打 Binance，一条数据都取不到。
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
    static String approvalSymbol(String raw) {
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
     * 这段历史被 {@link ChatContextStore} 持久化后，续聊重放时上游直接 400，会话只能删掉重开。
     * <p>
     * 按 id 而不是按工具名认领正主：一批里可能同时有两个受管辖的工具（比如既要唤醒又要复盘），
     * 按名字匹配会把只针对其中一个的说明同时发给两个，用户批的是 A、模型以为 B 也批了。
     */
    private static ToolResponseMessage reply(MessagesState<Message> state, String guardedCallId,
                                             String body) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        state.lastMessage()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(AssistantMessage::hasToolCalls)
                .ifPresent(a -> {
                    for (AssistantMessage.ToolCall c : a.getToolCalls()) {
                        responses.add(new ToolResponseMessage.ToolResponse(c.id(), c.name(),
                                c.id().equals(guardedCallId) ? body
                                        : "未执行：本轮存在待确认的贵操作。"));
                    }
                });
        return ToolResponseMessage.builder().responses(responses).build();
    }
}
