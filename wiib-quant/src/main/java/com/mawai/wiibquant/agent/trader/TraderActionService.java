package com.mawai.wiibquant.agent.trader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibquant.agent.learning.ReviewRunner;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * trader 三个动作（留言 / 手动唤醒 / 点播复盘）的唯一实现。
 * <p>
 * 执行入口只有动作面板这一条 REST 路径。对话轨的工具只把表单推给用户看，
 * 碰不到这里——所以模型说不出"我已经唤醒了"，它确实没有那个能力。
 * <p>
 * 返回一律带 message：三个动作里有"做了"、"被拦下"、"没素材所以跳过且没花钱"三种结局，
 * 最后一种是成功的省钱决定，压进 ok/fail 两态会让它在界面上变成一次报错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraderActionService {

    /** 留言长度上限：原样进下一轮系统提示词，太长会挤掉真正的交易上下文 */
    public static final int MAX_NOTE_CHARS = 500;
    /** 留言轮次上限：15m 档 24 轮≈6 小时。更长的交代属于常驻规则，该写进配置页的自定义提示词 */
    public static final int MAX_NOTE_ROUNDS = 24;

    private final TraderService traderService;
    private final TraderScheduler scheduler;
    private final ReviewRunner reviewRunner;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;

    /** 动作结果：ok=这次请求被正常处理；message 一律直接给用户看 */
    public record ActionResult(boolean ok, String message) {
    }

    /**
     * 动作面板的一次性状态：三张卡要显示的东西全在这里，前端一个请求填满面板。
     *
     * @param wakeBlockedReason   null=可唤醒，否则是不能唤醒的原话
     * @param reviewBlockedReason null=可复盘（有没有素材另看 hasReviewMaterial）
     * @param noteRounds          当前留言剩余轮次，0=无待读留言
     */
    public record ActionPanel(boolean hasTrader, String name, String status, String pausedReason,
                              Long lastWakeAt, Long nextWakeAt, String wakeBlockedReason,
                              Long lastReviewAt, String lastReviewStatus,
                              boolean hasReviewMaterial, String reviewBlockedReason,
                              String note, int noteRounds, int noteMaxRounds, int noteMaxChars) {
    }

    // ========== 留言 ==========

    /** 写留言：覆盖式，同时只有一条待读。rounds 钳在 1~24 */
    public ActionResult saveNote(long userId, String note, Integer rounds) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return new ActionResult(false, "尚未创建 AI Trader");
        }
        if (note == null || note.isBlank()) {
            return new ActionResult(false, "留言内容不能为空");
        }
        String text = note.strip();
        boolean truncated = text.length() > MAX_NOTE_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_NOTE_CHARS);
        }
        int n = rounds == null ? 1 : Math.clamp(rounds, 1, MAX_NOTE_ROUNDS);
        boolean covered = t.getOwnerNote() != null;
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getOwnerNote, text)
                .set(AiTrader::getOwnerNoteRounds, n)
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        log.info("[TraderAction] 留言已记下 traderId={} 长度={} 轮次={}", t.getId(), text.length(), n);
        return new ActionResult(true, "留言已记下，接下来 " + n + " 次唤醒都会带上"
                + (covered ? "；覆盖了上一条还没被读走的留言" : "")
                + (truncated ? "；超出 " + MAX_NOTE_CHARS + " 字的部分已截掉" : ""));
    }

    /** 撤回未读留言 */
    public ActionResult clearNote(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return new ActionResult(false, "尚未创建 AI Trader");
        }
        if (t.getOwnerNote() == null) {
            return new ActionResult(true, "当前没有待读留言");
        }
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getOwnerNote, null)
                .set(AiTrader::getOwnerNoteRounds, 0)
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        log.info("[TraderAction] 留言已撤回 traderId={}", t.getId());
        return new ActionResult(true, "留言已撤回，trader 不会再看到它");
    }

    // ========== 手动唤醒 ==========

    /**
     * 手动唤醒：治理与准入全归调度器，这里只判"这个 trader 现在该不该被叫醒"。
     * <p>
     * 暂停状态不放行——手动唤醒要是能绕过暂停，"暂停"就成了摆设；连败自动暂停的
     * trader 更不该被一句话叫起来接着亏。
     */
    public ActionResult wake(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return new ActionResult(false, "尚未创建 AI Trader");
        }
        String blocked = wakeBlockedReason(t);
        if (blocked != null) {
            return new ActionResult(false, blocked);
        }
        String why = scheduler.tryManualWake(t);
        return why == null
                ? new ActionResult(true, "已触发一次唤醒，trader 正在后台做决策；结果稍后出现在竞技场的决策时间线上")
                : new ActionResult(false, why);
    }

    /** 不能唤醒的原因，null=可以。面板与真执行共用，显示的拒因就是点下去会拿到的那一句 */
    public String wakeBlockedReason(AiTrader t) {
        if (AiTrader.STATUS_LIQUIDATED.equals(t.getStatus())) {
            return "本局已爆仓终局，要先在配置页重置开新一局才能继续交易";
        }
        if (!AiTrader.STATUS_RUNNING.equals(t.getStatus())) {
            return "trader 当前是暂停状态"
                    + (t.getPausedReason() == null ? "" : "（" + t.getPausedReason() + "）")
                    + "，手动唤醒不绕过暂停：请先去「我的 Trader」页启动它";
        }
        return scheduler.manualWakeBlockedReason(t);
    }

    // ========== 点播复盘 ==========

    /**
     * 点播复盘：准入同步、执行异步。
     * <p>
     * 复盘预算 {@link ReviewRunner#REVIEW_TIMEOUT_SECONDS} 与整条工作台 SSE 同为 600s，
     * 同步跑满就一秒不剩给回答。但"有没有新素材"留在同步侧当场答：
     * 那是用户唯一关心的"会不会白花钱"，推给时间线等于让他等几分钟再去扑空。
     */
    public ActionResult review(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return new ActionResult(false, "尚未创建 AI Trader");
        }
        String blocked = reviewBlockedReason(t);
        if (blocked != null) {
            return new ActionResult(false, blocked);
        }
        long at = System.currentTimeMillis();
        if (!reviewRunner.hasMaterial(t, at)) {
            // 成功而不是失败：跳过是一次省下模型调用的正确决定，界面上不该是红的
            return new ActionResult(true, "自上次复盘以来没有新的已了结交易，已跳过（没有消耗模型调用）");
        }
        // 占的是调度侧同一个位子：只挡点播与点播之间的话，日线交接阶段1 会对同一个 trader
        // 再排一篇复盘，两条都落 REVIEW 行、ai_trader.memory 被覆盖写两次，后完成的赢
        if (!scheduler.tryOccupy(t.getId())) {
            return new ActionResult(false, REVIEW_BUSY_REASON);
        }
        Thread.startVirtualThread(() -> {
            try {
                reviewRunner.review(t, at);
            } catch (Exception e) {
                // review() 自己兜住模型调用的失败并留 ERROR 行；这里兜的是它之前那几步库查询，
                // 异步之后没人接得住，不打日志就彻底无声
                log.warn("[TraderAction] 点播复盘异常逃逸 userId={} msg={}", userId, e.getMessage());
            } finally {
                scheduler.release(t.getId());
            }
        });
        return new ActionResult(true, "已开始复盘，几分钟后会在竞技场的决策时间线上出现一篇 REVIEW；"
                + "失败也会留一条 ERROR 记录，不会没有下文");
    }

    private static final String REVIEW_BUSY_REASON = "这个 trader 手上还有活（唤醒或复盘在跑），等它跑完再点";

    /** 不能复盘的原因，null=可以。素材有无另看 {@link ReviewRunner#hasMaterial} */
    public String reviewBlockedReason(AiTrader t) {
        if (scheduler.isHandoverActive()) {
            // 三阶段交接期间旁路写复盘，会让 learner 读到"半天"的复盘并脏读进记忆
            return "全体复盘与学习进行中（日线交接），几分钟后窗口关闭再试";
        }
        if (scheduler.isBusy(t.getId())) {
            return REVIEW_BUSY_REASON;
        }
        return null;
    }

    // ========== 面板状态 ==========

    /** 三张卡的状态一次取齐 */
    public ActionPanel panel(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return new ActionPanel(false, null, null, null, null, null, null, null, null,
                    false, null, null, 0, MAX_NOTE_ROUNDS, MAX_NOTE_CHARS);
        }
        // 时刻取 created_at 而不是 wake_time：后者是 K 线边界，1h 档在 10:37 手动唤醒会显示 10:00
        AiTraderDecision lastWake = latestDecision(t, AiTraderDecision.KIND_TRADE,
                AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL);
        AiTraderDecision lastReview = latestDecision(t, AiTraderDecision.KIND_REVIEW);
        String reviewBlocked = reviewBlockedReason(t);
        return new ActionPanel(
                true, t.getName(), t.getStatus(), t.getPausedReason(),
                lastWake == null ? null : epochMs(lastWake.getCreatedAt()),
                scheduler.nextRoutineWakeAt(t),
                wakeBlockedReason(t),
                lastReview == null ? null : epochMs(lastReview.getCreatedAt()),
                lastReview == null ? null : lastReview.getStatus(),
                reviewRunner.hasMaterial(t, System.currentTimeMillis()),
                reviewBlocked,
                t.getOwnerNote(),
                t.getOwnerNoteRounds() == null ? 0 : t.getOwnerNoteRounds(),
                MAX_NOTE_ROUNDS, MAX_NOTE_CHARS);
    }

    /** 本局最新的一条指定类型决策行；含 ERROR，面板要说得清"上次复盘失败了" */
    private AiTraderDecision latestDecision(AiTrader t, String... kinds) {
        return decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .in(AiTraderDecision::getKind, (Object[]) kinds)
                .orderByDesc(AiTraderDecision::getCreatedAt)
                .last("LIMIT 1"));
    }

    private static Long epochMs(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
