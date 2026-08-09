package com.mawai.wiibquant.agent.chat;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 会话级贵操作授权闸（HITL）：工具被调时登记 pending → SSE 弹确认卡 → 用户 approve 后
 * 授权带 TTL，同一工具+同一标的再被调时放行。
 * <p>
 * 授权键是三元组 {@code (sessionId, 工具名, 标的)} 而不是光一个 sessionId——
 * 用户批准的是一个**具体操作**，不是十分钟内的通用票。判断做在
 * {@link ApprovalGate}（executeTools hook）里，因为只有那一层同时看得到
 * sessionId（来自 RunnableConfig）和工具名/参数（来自 tool_call）。
 * <p>
 * 授权状态短 TTL、进程内存级即可，不持久化；断连恢复由 checkpoint 保证。
 */
@Component
public class ApprovalRegistry {

    /** 授权有效期：确认后 10 分钟内重调工具放行 */
    public static final long APPROVAL_TTL_MS = 10 * 60_000L;

    /** 墙钟注入点：过期清理要可测 */
    LongSupplier nowMs = System::currentTimeMillis;

    /**
     * 测试注入点：{@link #takePending} 里"比对已通过、还没摘掉"的那一瞬。
     * CAS 只在这个窗口内被并发写抢先时才起作用，不留落点就无法确定性地钉住它
     *（拿掉 CAS 全部用例照样绿——实测过）。
     */
    Runnable beforePendingRemoval = () -> {
    };

    /**
     * @param requestId  这张卡的唯一标识，前端原样回传用于比对"点的是哪张卡"
     * @param requestedAt 登记时刻，<b>只用于前端展示</b>——它不能当标识用：
     *                    两次登记之间是微秒级，同一毫秒内比对恒成立，等于没有比对
     */
    public record PendingRequest(String requestId, String toolName, String symbol,
                                 String reason, long requestedAt) {
    }

    private record ApprovalKey(String sessionId, String toolName, String symbol) {
    }

    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final Map<ApprovalKey, Long> approvedUntil = new ConcurrentHashMap<>();
    /** 一次性拒绝标记：让模型知道"用户拒了"，否则它下一轮会再弹一次卡 */
    private final Map<String, PendingRequest> rejected = new ConcurrentHashMap<>();

    /** 闸门侧：登记待确认请求（同 session 重复登记覆盖，一个会话同时只有一条待确认）。 */
    public void requestApproval(String sessionId, String toolName, String symbol, String reason) {
        pending.put(sessionId, new PendingRequest(UUID.randomUUID().toString(),
                toolName, symbol, reason, nowMs.getAsLong()));
    }

    /**
     * Controller 侧：本轮流结束后读 pending 发 hitl_request 事件。
     * <b>只读不删</b>——删除责任在 approve/reject，否则用户点同意时服务端手里就没有
     * 工具名和标的了，授权只能退化成绑 sessionId。
     */
    public Optional<PendingRequest> peekPending(String sessionId) {
        return Optional.ofNullable(pending.get(sessionId));
    }

    /**
     * 用户确认：授予三元组授权。
     *
     * @param requestId 前端从 hitl_request 事件原样回传，唯一标识"点的是哪张卡"。
     *                  <b>不用 requestedAt</b>：同一毫秒内两次登记会拿到同一个值，比对就失效了。
     *                  比对通过后授权内容仍全部取自服务端的 pending 记录，不信前端传的 symbol
     * @return false = 该确认请求已失效（用户点的是被新请求覆盖掉的旧卡片）
     */
    public boolean approve(String sessionId, String requestId) {
        Optional<PendingRequest> taken = takePending(sessionId, requestId);
        if (taken.isEmpty()) {
            return false;
        }
        PendingRequest req = taken.get();
        // 拒绝标记是一次性的，但残留时会盖住这次新授权，顺手清掉
        rejected.remove(sessionId);
        approvedUntil.put(new ApprovalKey(sessionId, req.toolName(), req.symbol()),
                nowMs.getAsLong() + APPROVAL_TTL_MS);
        return true;
    }

    /** 用户拒绝：清掉 pending 并留一条一次性标记。 */
    public boolean reject(String sessionId, String requestId) {
        Optional<PendingRequest> taken = takePending(sessionId, requestId);
        taken.ifPresent(req -> rejected.put(sessionId, req));
        return taken.isPresent();
    }

    /**
     * 摘走这张待确认卡——approve/reject 共用，两者的失效判据完全一致。
     * <p>
     * 两道关缺一不可：<br>
     * 1. requestId 对不上 = 用户点的是被新请求覆盖掉的旧卡片，不能拿旧卡的同意去授权新标的；<br>
     * 2. 比对通过后还得 CAS（{@code remove(key, expected)}）——先 get 再无条件 remove
     * 会把这中间新写入的 pending 一起抹掉，卡片素材没了用户再也点不出那张卡。
     * 第 2 关只在第 1 关放行之后才有意义，所以它<b>不是</b>第 1 关的重复。
     *
     * @return 空 = 没摘到（两道关任一没过），调用方一律当作"该请求已失效"
     */
    private Optional<PendingRequest> takePending(String sessionId, String requestId) {
        PendingRequest req = pending.get(sessionId);
        if (req == null || !req.requestId().equals(requestId)) {
            return Optional.empty();
        }
        beforePendingRemoval.run();
        return pending.remove(sessionId, req) ? Optional.of(req) : Optional.empty();
    }

    /**
     * 路由侧：只探不消费。续跑轮（用户刚确认贵操作）据此直通汇总不再派专家。
     * 顺手清理过期条目——用户批了但后续没触发工具的记录不清理会一直累积。
     */
    public boolean hasApproval(String sessionId) {
        long now = nowMs.getAsLong();
        approvedUntil.entrySet().removeIf(e -> e.getValue() < now);
        return approvedUntil.keySet().stream().anyMatch(k -> k.sessionId().equals(sessionId));
    }

    /** 闸门侧：按三元组精确消费（一次授权只放行一次执行）。 */
    public boolean consumeApproval(String sessionId, String toolName, String symbol) {
        Long until = approvedUntil.remove(new ApprovalKey(sessionId, toolName, symbol));
        return until != null && until >= nowMs.getAsLong();
    }

    /**
     * 丢弃该会话全部未消费授权。
     * <p>
     * 三元组化<b>新引入</b>的洞：旧的 {@code consumeApproval(sessionId)} 不带参数，必然消费掉；
     * 现在只要有一次对不上（模型改口换 symbol、或续跑轮压根没调工具），那条授权就一直躺到 TTL 结束，
     * 而 {@link #hasApproval} 为真时 route() 会跳过全部专家派发——这 10 分钟内该会话每一条新提问
     * 都不取数据、直接让 summarizer 凭空作答，且没有任何日志说明原因。
     * 所以"又要弹卡"就意味着上一条授权已经用不上了，弹卡前先丢掉它。
     */
    public void discardApprovals(String sessionId) {
        approvedUntil.keySet().removeIf(k -> k.sessionId().equals(sessionId));
    }

    /** 闸门侧：取走拒绝标记（一次性——用户改主意重新问时不该还被上一次的拒绝挡着）。 */
    public Optional<PendingRequest> consumeRejected(String sessionId) {
        return Optional.ofNullable(rejected.remove(sessionId));
    }

    /** 测试用：确认过期条目真被清掉了 */
    int approvedCount() {
        return approvedUntil.size();
    }
}
