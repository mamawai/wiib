package com.mawai.wiibquant.agent.learning;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 复盘素材组装（纯代码，可单测）：战绩表/配对表/时间线摘编/价格路径四块硬事实。
 * 事实裁定归代码、模型只解读——战绩数字只许复述，代码错一位就是复盘造假。
 * 素材窗口 = (上次成功REVIEW的wake_time, 本日线边界]；无REVIEW则本局开始（round过滤天然覆盖）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewMaterialAssembler {

    /** 初始资金，与 TraderService.INITIAL_BALANCE 同一口径 */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000");
    /** 时间线条目上限：5m 档一天 288 轮全文注入烧不起；有动作的行优先保全，早段 HOLD 被省略 */
    static final int MAX_TIMELINE_ENTRIES = 80;
    /** 价格路径回看上限(小时)：窗口通常一天，首篇复盘 fromMs=0 时靠它兜住 */
    private static final int MAX_PATH_HOURS = 48;
    /** 已平仓位拉取上限：窗口通常一天，远超一天可能的成交笔数 */
    static final int CLOSED_FETCH_LIMIT = 200;
    /** 只进摘编的交易动作工具（get_account 是查户口不是动作） */
    private static final Set<String> ACTION_TOOLS = Set.of(
            "open_position", "close_position", "set_stop_loss",
            "set_take_profit", "cancel_order", "write_plan");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AiTraderDecisionMapper decisionMapper;
    private final AiTraderPlanMapper planMapper;
    private final SimTradeClient simTradeClient;
    private final KlineHistoryStore historyStore;

    /** 四块素材文本 + 已了结笔数（调用方日志用） */
    public record ReviewMaterial(String statsBlock, String tradesBlock,
                                 String timelineBlock, String pricePathBlock, int closedTrades) {
    }

    /**
     * 上一期成功的复盘行；无 → null（本局首篇，素材窗口从本局开始算）。
     * 一次查询两用：wake_time 定素材窗口起点，reasoning 全文回注给本期承接检验——
     * 上期立的"下期纪律"必须有人管，不然每期各写各的，闭环是断的。
     */
    public AiTraderDecision lastReview(long traderId, int roundNo) {
        return decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, traderId)
                .eq(AiTraderDecision::getRoundNo, roundNo)
                .eq(AiTraderDecision::getKind, AiTraderDecision.KIND_REVIEW)
                .eq(AiTraderDecision::getStatus, AiTraderDecision.STATUS_OK)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
    }

    /** 窗口内有无新交易素材（TRADE/ALERT 的 OK 行）——无素材跳过复盘，不白烧钱。 */
    public boolean hasNewMaterial(long traderId, int roundNo, long fromMs, long toMs) {
        Long n = decisionMapper.selectCount(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, traderId)
                .eq(AiTraderDecision::getRoundNo, roundNo)
                .ne(AiTraderDecision::getKind, AiTraderDecision.KIND_REVIEW)
                .eq(AiTraderDecision::getStatus, AiTraderDecision.STATUS_OK)
                .gt(AiTraderDecision::getWakeTime, fromMs)
                .le(AiTraderDecision::getWakeTime, toMs));
        return n != null && n > 0;
    }

    public ReviewMaterial assemble(AiTrader trader, long fromMs, long toMs) {
        // 已平仓位是"最近N条"，取满上限就说明可能被截断——战绩表得把这件事说出来，
        // 不能一边宣称"硬事实、禁止自行计算"一边给不完整的数字
        List<FuturesPositionDTO> fetched = simTradeClient.getClosedPositions(trader.getSimUserId(), CLOSED_FETCH_LIMIT);
        boolean maybeTruncated = fetched.size() >= CLOSED_FETCH_LIMIT;
        List<FuturesPositionDTO> closed = inWindow(fetched, fromMs, toMs);
        String stats = statsBlock(trader, fromMs, toMs, closed, maybeTruncated);
        String trades = tradesBlock(trader, closed, fromMs, toMs);
        String timeline = timelineBlock(trader, fromMs, toMs);
        String pricePath = pricePathBlock(trader, fromMs, toMs);
        return new ReviewMaterial(stats, trades, timeline, pricePath, closed.size());
    }

    // ==================== 战绩表 ====================

    private String statsBlock(AiTrader t, long fromMs, long toMs, List<FuturesPositionDTO> closed,
                              boolean maybeTruncated) {
        // 起始权益 = 窗口起点前最后一条带权益的决策行；开局首次复盘无前值 → 初始资金
        AiTraderDecision prior = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .isNotNull(AiTraderDecision::getEquity)
                .le(AiTraderDecision::getWakeTime, fromMs)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
        BigDecimal start = prior != null ? prior.getEquity() : INITIAL_BALANCE;
        List<AiTraderDecision> series = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getWakeTime, AiTraderDecision::getEquity)
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .isNotNull(AiTraderDecision::getEquity)
                .gt(AiTraderDecision::getWakeTime, fromMs)
                .le(AiTraderDecision::getWakeTime, toMs)
                .orderByAsc(AiTraderDecision::getWakeTime));
        BigDecimal end = series.isEmpty() ? start : series.get(series.size() - 1).getEquity();

        BigDecimal returnPct = start.signum() > 0
                ? end.subtract(start).multiply(BigDecimal.valueOf(100)).divide(start, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal peak = start;
        BigDecimal maxDd = BigDecimal.ZERO;
        for (AiTraderDecision d : series) {
            BigDecimal e = d.getEquity();
            if (e.compareTo(peak) > 0) {
                peak = e;
            } else if (peak.signum() > 0) {
                BigDecimal dd = peak.subtract(e).multiply(BigDecimal.valueOf(100))
                        .divide(peak, 2, RoundingMode.HALF_UP);
                if (dd.compareTo(maxDd) > 0) {
                    maxDd = dd;
                }
            }
        }
        long wins = closed.stream().filter(p -> p.getClosedPnl() != null && p.getClosedPnl().signum() > 0).count();
        BigDecimal pnlSum = closed.stream().map(FuturesPositionDTO::getClosedPnl)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder("【战绩表】（代码统计，只许原样复述，禁止自行计算）\n");
        if (maybeTruncated) {
            sb.append("注意：本期成交笔数可能超出统计上限 ").append(CLOSED_FETCH_LIMIT)
                    .append(" 笔，以下已了结相关数字为不完全统计——可以据此谈倾向，别当成完整战绩下定论\n");
        }
        sb.append("起始权益 ").append(start.setScale(2, RoundingMode.HALF_UP))
                .append(" → 期末权益 ").append(end.setScale(2, RoundingMode.HALF_UP))
                .append("，期间收益率 ").append(signed(returnPct)).append("%\n");
        sb.append("期间最大回撤 ").append(maxDd).append("%\n");
        if (closed.isEmpty()) {
            sb.append("期间无已了结交易\n");
        } else {
            sb.append("已了结 ").append(closed.size()).append(" 笔：")
                    .append(wins).append(" 胜 ").append(closed.size() - wins).append(" 负（胜率 ")
                    .append(wins * 100 / closed.size()).append("%），合计毛盈亏 ")
                    .append(signed(pnlSum.setScale(2, RoundingMode.HALF_UP)))
                    .append("（未计手续费与资金费）\n");
        }
        return sb.toString();
    }

    // ==================== 已了结交易配对表 ====================

    private String tradesBlock(AiTrader t, List<FuturesPositionDTO> closed, long fromMs, long toMs) {
        // 全量拉本局计划在内存配对：一局的计划量有限，省掉按笔查询
        List<AiTraderPlan> plans = planMapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, t.getId())
                .eq(AiTraderPlan::getRoundNo, t.getRoundNo()));
        StringBuilder sb = new StringBuilder("【已了结交易配对表】（论点→结局，代码配对）\n");
        Set<AiTraderPlan> used = new HashSet<>();
        int i = 1;
        for (FuturesPositionDTO pos : closed) {
            AiTraderPlan plan = bestMatch(plans, pos, used);
            sb.append(i++).append(". ").append(pos.getSymbol()).append(' ').append(pos.getSide());
            if (plan != null && plan.getPlayType() != null) {
                sb.append(" [").append(plan.getPlayType()).append(']');
            }
            sb.append(" 入场 ").append(plain(pos.getEntryPrice()))
                    .append(" → 出场 ").append(plain(pos.getClosedPrice()))
                    .append("，毛盈亏 ").append(signed(pos.getClosedPnl()))
                    .append("，持有 ").append(humanize(msOf(pos.getUpdatedAt()) - msOf(pos.getCreatedAt())))
                    .append("，").append(closeManner(pos)).append('\n');
            if (plan != null) {
                sb.append("   论点: ").append(nullSafe(plan.getSignalsUsed()))
                        .append(" ｜ 失效条件: ").append(nullSafe(plan.getInvalidationCondition())).append('\n');
            } else {
                sb.append("   （无计划记录）\n");
            }
        }
        // 窗口内归档却没配对上仓位的计划（多为挂单未成交撤销）：论点没得到执行机会也要留痕
        for (AiTraderPlan plan : plans) {
            if (AiTraderPlan.STATUS_CLOSED.equals(plan.getStatus()) && !used.contains(plan)
                    && plan.getClosedWakeTime() != null
                    && plan.getClosedWakeTime() > fromMs && plan.getClosedWakeTime() <= toMs) {
                sb.append("· ").append(plan.getSymbol()).append(' ').append(plan.getSide())
                        .append(" [").append(nullSafe(plan.getPlayType()))
                        .append("] 计划归档但未配对到已平仓位（多为挂单未成交撤销）；论点: ")
                        .append(nullSafe(plan.getSignalsUsed())).append('\n');
            }
        }
        if (i == 1 && sb.indexOf("·") < 0) {
            sb.append("期间无已了结交易\n");
        }
        return sb.toString();
    }

    /** 同 symbol/side 里选开仓时刻最贴近该仓位开仓时间的计划（懒归档时刻粗糙，openedWakeTime 才可靠）。 */
    private static AiTraderPlan bestMatch(List<AiTraderPlan> plans, FuturesPositionDTO pos, Set<AiTraderPlan> used) {
        long posOpen = msOf(pos.getCreatedAt());
        long posClose = msOf(pos.getUpdatedAt());
        return plans.stream()
                .filter(p -> !used.contains(p))
                .filter(p -> pos.getSymbol().equals(p.getSymbol()) && pos.getSide().equals(p.getSide()))
                .filter(p -> p.getOpenedWakeTime() != null && p.getOpenedWakeTime() <= posClose)
                .min(Comparator.comparingLong(p -> Math.abs(p.getOpenedWakeTime() - posOpen)))
                .map(p -> {
                    used.add(p);
                    return p;
                })
                .orElse(null);
    }

    /**
     * 了结方式推断：强平看状态；止损/止盈用方向性对照——触发价是探测时的 markPrice 会越过挂单价，
     * 不能按相等判。保护单实时监控在先，带内成交只能是主动平仓（模型自平或审批执行）。
     * 全平不清保护单列表（sim 只在部分平仓时改写），closed 行上的列表就是了结时在岗的那组。
     */
    static String closeManner(FuturesPositionDTO p) {
        if ("LIQUIDATED".equals(p.getStatus())) {
            return "强平";
        }
        BigDecimal cp = p.getClosedPrice();
        if (cp == null) {
            return "UNKNOWN";
        }
        boolean isLong = "LONG".equals(p.getSide());
        if (p.getStopLosses() != null && p.getStopLosses().stream().anyMatch(sl ->
                isLong ? cp.compareTo(sl.getPrice()) <= 0 : cp.compareTo(sl.getPrice()) >= 0)) {
            return "止损带走";
        }
        if (p.getTakeProfits() != null && p.getTakeProfits().stream().anyMatch(tp ->
                isLong ? cp.compareTo(tp.getPrice()) >= 0 : cp.compareTo(tp.getPrice()) <= 0)) {
            return "止盈带走";
        }
        return "主动平仓";
    }

    // ==================== 决策时间线摘编 ====================

    private record TimelineEntry(String line, boolean hasAction) {
    }

    private String timelineBlock(AiTrader t, long fromMs, long toMs) {
        List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .ne(AiTraderDecision::getKind, AiTraderDecision.KIND_REVIEW)
                .gt(AiTraderDecision::getWakeTime, fromMs)
                .le(AiTraderDecision::getWakeTime, toMs)
                .orderByAsc(AiTraderDecision::getWakeTime));
        List<TimelineEntry> entries = new ArrayList<>();
        int errors = 0;
        int skipped = 0;
        for (AiTraderDecision d : rows) {
            if (AiTraderDecision.STATUS_ERROR.equals(d.getStatus())) {
                errors++;
                continue;
            }
            if (AiTraderDecision.STATUS_SKIPPED.equals(d.getStatus())) {
                skipped++;
                continue;
            }
            String acts = actionSummary(d.getActionsJson());
            boolean hasAction = !acts.isEmpty();
            String tag = AiTraderDecision.KIND_ALERT.equals(d.getKind()) ? "[警报] " : "";
            String line = "- " + TIME_FMT.format(Instant.ofEpochMilli(d.getWakeTime())) + " " + tag
                    + (hasAction ? acts + " ｜ " : "") + conclusion(d.getReasoning(), hasAction);
            entries.add(new TimelineEntry(line, hasAction));
        }

        StringBuilder sb = new StringBuilder("【决策时间线摘编】（时间升序；动作行含工具摘要）\n");
        if (entries.size() > MAX_TIMELINE_ENTRIES) {
            // 动作行全保、无动作 HOLD 从最新往回补足额度：复盘的主菜是动作，观望看最近的就够
            int budget = MAX_TIMELINE_ENTRIES - (int) entries.stream().filter(TimelineEntry::hasAction).count();
            Set<Integer> keep = new HashSet<>();
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).hasAction()) {
                    keep.add(i);
                } else if (budget > 0) {
                    keep.add(i);
                    budget--;
                }
            }
            sb.append("（早段已省略 ").append(entries.size() - keep.size()).append(" 条无动作 HOLD 行）\n");
            List<TimelineEntry> kept = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                if (keep.contains(i)) {
                    kept.add(entries.get(i));
                }
            }
            entries = kept;
        }
        if (entries.isEmpty() && errors == 0 && skipped == 0) {
            sb.append("期间无决策记录\n");
        }
        entries.forEach(e -> sb.append(e.line()).append('\n'));
        if (errors > 0 || skipped > 0) {
            sb.append("期间另有 ");
            if (errors > 0) {
                sb.append(errors).append(" 轮 ERROR").append(skipped > 0 ? "、" : "");
            }
            if (skipped > 0) {
                sb.append(skipped).append(" 轮 SKIPPED");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 动作轨迹 JSON → 一行摘要；只取交易动作工具，拒/错标注结果。解析失败当无动作（摘编缺一行不挡复盘）。 */
    private static String actionSummary(String actionsJson) {
        if (actionsJson == null || actionsJson.isBlank()) {
            return "";
        }
        try {
            JSONArray arr = JSON.parseArray(actionsJson);
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject a = arr.getJSONObject(i);
                String tool = a.getString("tool");
                if (tool == null || !ACTION_TOOLS.contains(tool)) {
                    continue;
                }
                JSONObject args = a.getJSONObject("args");
                StringBuilder brief = new StringBuilder();
                if (args != null) {
                    for (String k : new String[]{"symbol", "side", "quantity", "positionId"}) {
                        Object v = args.get(k);
                        if (v != null) {
                            brief.append(brief.isEmpty() ? "" : " ").append(v);
                        }
                    }
                }
                // pending＝转成待主人确认的请求，本轮并没有成交，摘编里不标就成了"平了仓"的假事实
                String outcome = a.containsKey("rejected") ? "→被拒"
                        : "error".equals(a.getString("status")) ? "→失败"
                        : "pending".equals(a.getString("status")) ? "→待确认" : "";
                parts.add(tool + "(" + brief + ")" + outcome);
            }
            return String.join("；", parts);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 提取【本轮结论】块：动作行保留整块（截断保头——块内判断在前）；
     * HOLD 行只留"判断/等待"两行——等待条件是观望对账的原料，绝不能丢。
     * 没有结论块（旧数据/格式失守）退化为截尾片段。
     */
    private static String conclusion(String reasoning, boolean hasAction) {
        if (reasoning == null || reasoning.isBlank()) {
            return "";
        }
        int idx = reasoning.lastIndexOf("【本轮结论】");
        if (idx < 0) {
            String tail = reasoning.strip();
            return (tail.length() > 120 ? "…" + tail.substring(tail.length() - 120) : tail).replace('\n', ' ');
        }
        String block = reasoning.substring(idx + "【本轮结论】".length()).strip();
        if (hasAction) {
            String flat = block.replace('\n', ' ');
            return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
        }
        String kept = block.lines().map(String::strip)
                .filter(l -> l.startsWith("判断") || l.startsWith("等待"))
                .reduce((a, b) -> a + "；" + b).orElse("");
        if (kept.isEmpty()) {
            String flat = block.replace('\n', ' ');
            return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
        }
        return kept.length() > 200 ? kept.substring(0, 200) + "…" : kept;
    }

    // ==================== 各币价格路径 ====================

    /**
     * 价格路径走本地 kline_history 的 5m 现聚合成 1h：复盘看的全是已收盘行情，本地就有
     * （feed 每根 5m 收盘落库），没理由为此打外网——外网抖一下这个币就没了对照物。
     * 与哨兵阈值校准、策略回测同源。
     * 48h 上限兜住首篇复盘（fromMs=0）：真实覆盖范围写进块头，观望对账拿错对照物结论就是假的。
     */
    private String pricePathBlock(AiTrader t, long fromMs, long toMs) {
        long effectiveFrom = Math.max(fromMs, toMs - MAX_PATH_HOURS * 3_600_000L);
        StringBuilder sb = new StringBuilder("【各币1h价格路径】（观望对账的对照物；覆盖 "
                + TIME_FMT.format(Instant.ofEpochMilli(effectiveFrom)) + " → "
                + TIME_FMT.format(Instant.ofEpochMilli(toMs)) + "）\n");
        if (fromMs == 0) {
            sb.append("注意：本局首篇复盘，这段路径可能早于开局时刻——开局前的价格只作背景，不作对账依据\n");
        }
        for (String symbol : t.getSymbols().split(",")) {
            symbol = symbol.trim();
            if (symbol.isEmpty()) {
                continue;
            }
            List<KlineBar> hourly = hourlyBars(symbol, effectiveFrom, toMs);
            if (hourly.isEmpty()) {
                sb.append("- ").append(symbol).append(": 窗口内无K线数据\n");
                continue;
            }
            BigDecimal open = hourly.get(0).open();
            BigDecimal close = hourly.get(hourly.size() - 1).close();
            BigDecimal high = null;
            BigDecimal low = null;
            long highAt = 0;
            long lowAt = 0;
            StringBuilder closes = new StringBuilder();
            for (KlineBar k : hourly) {
                if (high == null || k.high().compareTo(high) > 0) {
                    high = k.high();
                    highAt = k.openTime();
                }
                if (low == null || k.low().compareTo(low) < 0) {
                    low = k.low();
                    lowAt = k.openTime();
                }
                closes.append(closes.isEmpty() ? "" : "→").append(plain(k.close()));
            }
            BigDecimal pct = open.signum() > 0
                    ? close.subtract(open).multiply(BigDecimal.valueOf(100)).divide(open, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            sb.append("- ").append(symbol).append(": 开 ").append(plain(open))
                    .append(" → 收 ").append(plain(close)).append("（").append(signed(pct)).append("%）")
                    .append("；最高 ").append(plain(high)).append("（").append(TIME_FMT.format(Instant.ofEpochMilli(highAt)))
                    .append("）最低 ").append(plain(low)).append("（").append(TIME_FMT.format(Instant.ofEpochMilli(lowAt)))
                    .append("）\n  1h收盘: ").append(closes).append('\n');
        }
        return sb.toString();
    }

    /**
     * 本地 5m 现聚合成 1h：按整点分组，开=组内首根开、高/低=组内极值、收=组内末根收。
     * 不足 12 根的组照算（库里有缺口或窗口边界切在半路），价格路径要的是形状不是完整性。
     */
    private List<KlineBar> hourlyBars(String symbol, long fromMs, long toMs) {
        List<KlineBar> bars = historyStore.load(symbol, KlineHistoryStore.DEFAULT_INTERVAL, fromMs, toMs);
        List<KlineBar> out = new ArrayList<>();
        long curHour = -1;
        BigDecimal open = null;
        BigDecimal high = null;
        BigDecimal low = null;
        BigDecimal close = null;
        long closeTime = 0;
        for (KlineBar b : bars) {
            long hour = b.openTime() - Math.floorMod(b.openTime(), 3_600_000L);
            if (hour != curHour) {
                if (curHour >= 0) {
                    out.add(new KlineBar(curHour, closeTime, open, high, low, close, BigDecimal.ZERO));
                }
                curHour = hour;
                open = b.open();
                high = b.high();
                low = b.low();
            } else {
                high = high.max(b.high());
                low = low.min(b.low());
            }
            close = b.close();
            closeTime = b.closeTime();
        }
        if (curHour >= 0) {
            out.add(new KlineBar(curHour, closeTime, open, high, low, close, BigDecimal.ZERO));
        }
        return out;
    }

    // ==================== 小工具 ====================

    private static List<FuturesPositionDTO> inWindow(List<FuturesPositionDTO> fetched, long fromMs, long toMs) {
        return fetched.stream()
                .filter(p -> p.getUpdatedAt() != null)
                .filter(p -> {
                    long closedAt = msOf(p.getUpdatedAt());
                    return closedAt > fromMs && closedAt <= toMs;
                })
                .sorted(Comparator.comparing(FuturesPositionDTO::getUpdatedAt))
                .toList();
    }

    private static long msOf(java.time.LocalDateTime t) {
        return t == null ? 0 : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    private static String signed(BigDecimal v) {
        if (v == null) {
            return "?";
        }
        return v.signum() >= 0 ? "+" + v.toPlainString() : v.toPlainString();
    }

    private static String nullSafe(String s) {
        return s == null ? "—" : s;
    }

    private static String humanize(long ms) {
        long min = Math.max(0, ms / 60_000);
        if (min < 120) {
            return min + "分钟";
        }
        long hours = min / 60;
        return hours < 48 ? hours + "小时" : (hours / 24) + "天";
    }
}
