package com.mawai.wiibquant.agent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibquant.agent.learning.ReviewRunner;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 对话轨访问 trader 的唯一入口：查询读库，动作进程内调 runner。
 * <p>
 * <b>两个 agent 的解耦纪律在这里落地</b>：chat 与 trader 从不互相对话——查询只读 trader 写下的表，
 * 动作只是"扣一次扳机"（唤醒/复盘/留言），扣完就走，不等 trader 回话、也没有任何回调。
 * trader 依旧是那个"醒来读库→决策→写库→睡去"的无状态回路，它根本不知道有人在跟它聊天。
 * <p>
 * 所有方法按 userId 取自己的 trader，取不到就如实说"还没有"——归属判断只此一处。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraderChatService {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 决策一次最多给几条：叶子是轻模型，给多了读不完还挤掉问题本身 */
    static final int MAX_DECISIONS = 20;
    static final int DEFAULT_DECISIONS = 5;
    private static final int RECENT_CLOSED_PLANS = 5;
    /** 留言长度上限：它要原样进下一轮系统提示词，太长会挤掉真正的交易上下文 */
    static final int MAX_NOTE_CHARS = 500;

    private final TraderService traderService;
    private final TraderPlanStore planStore;
    private final TraderScheduler scheduler;
    private final ReviewRunner reviewRunner;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final SimTradeClient simTradeClient;

    // ===== 查询（纯读库） =====

    /** 概况：状态/权益/轮次/配置 + 复盘笔记全文（笔记是"它学到了什么"的唯一载体，必须给全）。 */
    public String overview(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader();
        }
        return new JSONObject()
                .fluentPut("hasTrader", true)
                .fluentPut("name", t.getName())
                .fluentPut("status", t.getStatus())
                .fluentPut("pausedReason", t.getPausedReason())
                .fluentPut("roundNo", t.getRoundNo())
                .fluentPut("equity", traderService.latestEquity(t))
                .fluentPut("initialBalance", TraderService.INITIAL_BALANCE)
                .fluentPut("symbols", t.getSymbols())
                .fluentPut("intervalCode", t.getIntervalCode())
                .fluentPut("model", t.getModel())
                .fluentPut("consecutiveFailures", t.getConsecutiveFailures())
                .fluentPut("reviewEnabled", t.getReviewEnabled())
                .fluentPut("alertEnabled", t.getAlertEnabled())
                .fluentPut("leverageRange", t.getLeverageMin() + "~" + t.getLeverageMax() + "倍")
                .fluentPut("marginPctRange", plain(t.getMarginPctMin()) + "~" + plain(t.getMarginPctMax()) + "%")
                .fluentPut("memory", t.getMemory())
                .fluentPut("memoryNote", "复盘笔记全文：learning agent 每日复盘写的，trader 每次唤醒都会看到")
                .fluentPut("customPrompt", t.getCustomPrompt())
                .fluentPut("pendingOwnerNote", t.getOwnerNote())
                .toJSONString();
    }

    /** 当前持仓：口径与唤醒时注入给 trader 的账户状态一致，免得两处说法对不上。 */
    public String positions(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader();
        }
        JSONArray arr = new JSONArray();
        for (FuturesPositionDTO p : simTradeClient.getAllPositions(t.getSimUserId())) {
            JSONObject row = new JSONObject()
                    .fluentPut("positionId", p.getId())
                    .fluentPut("symbol", p.getSymbol())
                    .fluentPut("side", p.getSide())
                    .fluentPut("quantity", p.getQuantity())
                    .fluentPut("entryPrice", p.getEntryPrice())
                    .fluentPut("leverage", p.getLeverage())
                    .fluentPut("unrealizedPnl", p.getUnrealizedPnl())
                    .fluentPut("marginMode", p.getMarginMode());
            if (p.getStopLosses() != null && !p.getStopLosses().isEmpty()) {
                row.put("currentStopLoss", p.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList());
            }
            if (p.getTakeProfits() != null && !p.getTakeProfits().isEmpty()) {
                row.put("currentTakeProfit", p.getTakeProfits().stream().map(FuturesTakeProfit::getPrice).toList());
            }
            arr.add(row);
        }
        return new JSONObject()
                .fluentPut("hasTrader", true)
                .fluentPut("equity", traderService.latestEquity(t))
                .fluentPut("positions", arr)
                .toJSONString();
    }

    /**
     * 决策时间线。<b>reasoning 给全文</b>：用户质询"你那笔为什么开多"靠的就是它，
     * 截断了正好把收尾的【本轮结论】切掉，剩一堆行情铺垫等于没给。
     */
    public String decisions(long userId, Integer limit) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader();
        }
        int n = limit == null ? DEFAULT_DECISIONS : Math.clamp(limit, 1, MAX_DECISIONS);
        JSONArray arr = new JSONArray();
        for (AiTraderDecision d : traderService.decisions(t.getId(), n, null, null)) {
            arr.add(new JSONObject()
                    .fluentPut("time", TIME_FMT.format(Instant.ofEpochMilli(d.getWakeTime())))
                    .fluentPut("wakeTime", d.getWakeTime())
                    .fluentPut("kind", d.getKind())
                    .fluentPut("status", d.getStatus())
                    .fluentPut("equity", d.getEquity())
                    .fluentPut("toolCalls", d.getToolCalls())
                    .fluentPut("error", d.getError())
                    .fluentPut("tools", toolNames(d.getActionsJson()))
                    .fluentPut("reasoning", d.getReasoning()));
        }
        return new JSONObject()
                .fluentPut("hasTrader", true)
                .fluentPut("roundNo", t.getRoundNo())
                .fluentPut("decisions", arr)
                .fluentPut("kindNote", "TRADE=K线收盘唤醒 ALERT=波动警报唤醒 REVIEW=每日复盘（reasoning是复盘全文）")
                .toJSONString();
    }

    /** 交易计划：存活的全给，另附最近归档的几条——"上一笔为什么平了"只看 LIVE 是答不了的。 */
    public String plans(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader();
        }
        JSONArray live = new JSONArray();
        planStore.list(t.getId(), t.getRoundNo()).forEach(p -> live.add(planJson(p)));
        JSONArray closed = new JSONArray();
        planStore.recentClosed(t.getId(), t.getRoundNo(), RECENT_CLOSED_PLANS)
                .forEach(p -> closed.add(planJson(p)));
        return new JSONObject()
                .fluentPut("hasTrader", true)
                .fluentPut("livePlans", live)
                .fluentPut("recentClosedPlans", closed)
                .fluentPut("planNote", "invalidationCondition 是开仓时立的失效条件——它被触发才允许主动平仓；"
                        + "revisions 是止损止盈的修订留痕")
                .toJSONString();
    }

    // ===== 动作 =====

    /**
     * 手动唤醒：治理与准入全归调度器，这里只判"能不能唤醒这个 trader"。
     * <p>
     * 暂停状态不放行——手动唤醒要是能绕过暂停，那"暂停"就成了摆设；连败自动暂停的
     * trader 更不该被一句话叫起来接着亏。
     */
    public String wake(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader();
        }
        if (AiTrader.STATUS_LIQUIDATED.equals(t.getStatus())) {
            return outcome(false, "本局已爆仓终局，要先在配置页重置开新一局才能继续交易");
        }
        if (!AiTrader.STATUS_RUNNING.equals(t.getStatus())) {
            return outcome(false, "trader 当前是暂停状态"
                    + (t.getPausedReason() == null ? "" : "（" + t.getPausedReason() + "）")
                    + "，手动唤醒不绕过暂停：请先去「我的 Trader」页启动它");
        }
        String why = scheduler.tryManualWake(t);
        return why == null
                ? outcome(true, "已触发一次唤醒，trader 正在后台做决策；结果稍后出现在竞技场的决策时间线上")
                : outcome(false, why);
    }

    /**
     * 点播复盘：同步跑完（{@link ReviewRunner#review} 内部有 180s 预算）。
     * <p>
     * review() 无返回值、无素材时会静默跳过，所以拿"本次时刻有没有落下 REVIEW 行"来判定——
     * 跳过了却报"复盘完成"，用户会去时间线上找一篇根本不存在的复盘。
     */
    public String reviewNow(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader();
        }
        long at = System.currentTimeMillis();
        reviewRunner.review(t, at);
        AiTraderDecision d = latestReview(t);
        if (d == null || d.getWakeTime() == null || d.getWakeTime() != at) {
            return outcome(false, "本期没有新的已了结交易可复盘，已跳过（没有消耗模型调用）");
        }
        if (!AiTraderDecision.STATUS_OK.equals(d.getStatus())) {
            return outcome(false, "复盘执行失败：" + d.getError());
        }
        return new JSONObject()
                .fluentPut("ok", true)
                .fluentPut("message", "复盘已完成，记忆笔记已更新")
                .fluentPut("review", d.getReasoning())
                .toJSONString();
    }

    /** 留言：覆盖写（同时只有一条待读），trader 下次唤醒注入后即焚。 */
    public String leaveNote(long userId, String note) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader();
        }
        if (note == null || note.isBlank()) {
            return outcome(false, "留言内容不能为空");
        }
        String text = note.strip();
        if (text.length() > MAX_NOTE_CHARS) {
            text = text.substring(0, MAX_NOTE_CHARS);
        }
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getOwnerNote, text)
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        log.info("[TraderChat] 留言已记下 traderId={} 长度={}", t.getId(), text.length());
        return outcome(true, "留言已记下，trader 下次唤醒时会看到（只看这一次，看完就消失）"
                + (t.getOwnerNote() == null ? "" : "；覆盖了上一条还没被读走的留言"));
    }

    // ===== 内部 =====

    private AiTraderDecision latestReview(AiTrader t) {
        return decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .eq(AiTraderDecision::getKind, AiTraderDecision.KIND_REVIEW)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
    }

    private static JSONObject planJson(AiTraderPlan p) {
        JSONObject row = new JSONObject()
                .fluentPut("symbol", p.getSymbol())
                .fluentPut("side", p.getSide())
                .fluentPut("status", p.getStatus())
                .fluentPut("playType", p.getPlayType())
                .fluentPut("signalsUsed", p.getSignalsUsed())
                .fluentPut("invalidationCondition", p.getInvalidationCondition())
                .fluentPut("entryPrice", p.getEntryPrice())
                .fluentPut("originalStop", p.getStopLossPrice())
                .fluentPut("target", p.getTakeProfitPrice())
                .fluentPut("openedAt", TIME_FMT.format(Instant.ofEpochMilli(p.getOpenedWakeTime())));
        if (p.getClosedWakeTime() != null) {
            row.put("closedAt", TIME_FMT.format(Instant.ofEpochMilli(p.getClosedWakeTime())));
        }
        if (p.getRevisionsJson() != null && !p.getRevisionsJson().isBlank()) {
            row.put("revisions", JSON.parse(p.getRevisionsJson()));
        }
        return row;
    }

    /** 动作轨迹只取工具名：全文可能上万字符，而这里要的只是"那一轮它动手没有"。 */
    private static List<String> toolNames(String actionsJson) {
        if (actionsJson == null || actionsJson.isBlank()) {
            return List.of();
        }
        try {
            return JSON.parseArray(actionsJson).stream()
                    .map(o -> o instanceof JSONObject j ? j.getString("tool") : null)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String plain(java.math.BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String noTrader() {
        return new JSONObject()
                .fluentPut("hasTrader", false)
                .fluentPut("message", "这位用户还没有创建 AI Trader，可以去「我的 Trader」页创建一个")
                .toJSONString();
    }

    private static String outcome(boolean ok, String message) {
        return new JSONObject().fluentPut("ok", ok).fluentPut("message", message).toJSONString();
    }
}
