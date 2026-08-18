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
import com.mawai.wiibquant.external.sim.SimTradeClient;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** 时间线条目上限：5m 档一天 288 轮全文注入烧不起；有动作的行优先保全，早段观望被省略 */
    static final int MAX_TIMELINE_ENTRIES = 80;
    /** 动作行结论字数上限 */
    static final int ACTION_MAX_CHARS = 300;
    /** 等待条件字数上限：观望对账的唯一原料，与动作行同档 */
    static final int WAIT_MAX_CHARS = 300;
    /** "等待"段：吃到下一个小节标签或块尾。多行 + 行首锚定，分条写的条件才不会只捞到标签行 */
    private static final Pattern WAIT_SECTION = Pattern.compile(
            "(?ms)^\\s*等待(?:条件)?[：:]\\h*(.*?)(?=^\\s*(?:判断|动作|计划依据)[：:]|\\z)");
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

    /**
     * 窗口内有无新交易素材（TRADE/ALERT/MANUAL 的 OK 行）——无素材跳过复盘，不白烧钱。
     * 白名单不是黑名单：LEARN 行每天必有一条，用 ne(REVIEW) 排除的话它会天天充当"新素材"，
     * 无交易的日子复盘再也跳不过去
     */
    public boolean hasNewMaterial(long traderId, int roundNo, long fromMs, long toMs) {
        Long n = decisionMapper.selectCount(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, traderId)
                .eq(AiTraderDecision::getRoundNo, roundNo)
                .in(AiTraderDecision::getKind, AiTraderDecision.KIND_TRADE,
                        AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL)
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

    /**
     * 同 symbol/side 里选开仓时刻最贴近该仓位开仓时间的计划（懒归档时刻粗糙，openedWakeTime 才可靠）。
     * 对同包 PeerInsightService 开放：同侪详情的论点→结局配对必须与复盘同一套算法，两处各配一套就会自相矛盾。
     */
    static AiTraderPlan bestMatch(List<AiTraderPlan> plans, FuturesPositionDTO pos, Set<AiTraderPlan> used) {
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
        // 白名单同 hasNewMaterial：时间线是交易行为的摘编，LEARN/REVIEW 进来会虚增"唤醒轮数"，
        // 保守度自检的对照物就失真了
        List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.KIND_TRADE,
                        AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL)
                .gt(AiTraderDecision::getWakeTime, fromMs)
                .le(AiTraderDecision::getWakeTime, toMs)
                .orderByAsc(AiTraderDecision::getWakeTime));
        List<TimelineEntry> entries = new ArrayList<>();
        int errors = 0;
        int skipped = 0;
        int opens = 0;
        // HOLD 段游标：连续同一等待条件压成一段，遇动作行或条件变化就结算。
        // 15m 档一天 96 轮，行情不动时几十轮等的是同一句话，一轮一行只会把动作行的信号稀释掉
        String holdKey = null;
        String holdWait = null;
        String holdKind = null;
        long holdFrom = 0;
        long holdTo = 0;
        int holdRounds = 0;
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
            String tag = AiTraderDecision.KIND_ALERT.equals(d.getKind()) ? "[警报] " : "";
            if (!acts.isEmpty()) {
                // 动作行逐条出、内容不动：它是复盘主菜，判断/依据/等待整块都是"为什么做这一手"的证据
                flushHold(entries, holdKind, holdWait, holdFrom, holdTo, holdRounds);
                holdKey = null;
                holdRounds = 0;
                // 数开仓动作按摘要文本认工具名：actionSummary 已过滤成 tool(args) 形态，误中不了正文
                opens += countOccurrences(acts, "open_position(");
                entries.add(new TimelineEntry("- " + TIME_FMT.format(Instant.ofEpochMilli(d.getWakeTime()))
                        + " " + tag + acts + " ｜ " + conclusion(d.getReasoning()), true));
                continue;
            }
            // 观望轮只留等待条件：它有对账物（价格路径能验证到没到），"判断"那段指标读数没有
            String wait = waitSection(d.getReasoning());
            // 警报轮与例行观望不混段：同样条件下被警报叫醒仍按兵不动，这件事本身就是复盘证据
            String key = (tag.isEmpty() ? "N|" : "A|") + waitKey(wait);
            if (holdKey != null && holdKey.equals(key)) {
                holdTo = d.getWakeTime();
                holdRounds++;
            } else {
                flushHold(entries, holdKind, holdWait, holdFrom, holdTo, holdRounds);
                holdKey = key;
                holdWait = wait;
                holdKind = d.getKind();
                holdFrom = d.getWakeTime();
                holdTo = d.getWakeTime();
                holdRounds = 1;
            }
        }
        flushHold(entries, holdKind, holdWait, holdFrom, holdTo, holdRounds);

        StringBuilder sb = new StringBuilder(
                "【决策时间线摘编】（时间升序；动作行含工具摘要，连续等待条件相同的观望轮已合并成段）\n");
        // 活动统计给保守度自检当对照物：唤醒多动作少是"没信号"还是"吓缩了"，得先有数才能问。
        // 轮数取自原始行而非合并后的段数——合并只是省字，"这期醒了多少次"不能跟着缩水
        sb.append("本期活动：唤醒 ").append(rows.size()).append(" 轮，动作轮 ")
                .append(entries.stream().filter(TimelineEntry::hasAction).count())
                .append("，开仓动作 ").append(opens).append(" 次\n");
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
            sb.append("（早段已省略 ").append(entries.size() - keep.size()).append(" 段观望）\n");
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
     * 动作行的结论：【本轮结论】整块，截断保头（块内判断在前）。
     * 没有结论块（旧数据/格式失守）退化为截尾片段——结论在末尾，保头会正好把它切掉。
     */
    private static String conclusion(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            return "";
        }
        int idx = reasoning.lastIndexOf("【本轮结论】");
        if (idx < 0) {
            String tail = reasoning.strip();
            return (tail.length() > 120 ? "…" + tail.substring(tail.length() - 120) : tail).replace('\n', ' ');
        }
        String flat = reasoning.substring(idx + "【本轮结论】".length()).strip().replace('\n', ' ');
        return flat.length() > ACTION_MAX_CHARS ? flat.substring(0, ACTION_MAX_CHARS) + "…" : flat;
    }

    /**
     * 观望行的原料：【本轮结论】里的"等待"段，返回完整内容不截断
     * （截断留给输出——先截再算合并键，会把"前段相同、后段不同"的两条误并成一条）。
     * <p>
     * 按标签块切而不是按行取：模型有时把条件写在标签同一行、有时换行分条列，
     * 早先按行 startsWith 只捞得到标签行本身，分条写的条件整段丢掉——线上 59 条观望里
     * 49 条就这么丢的，观望对账从上线起一直在空转。行首锚定防正文里的"等待："被误认。
     */
    static String waitSection(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            return "";
        }
        int idx = reasoning.lastIndexOf("【本轮结论】");
        if (idx < 0) {
            String tail = reasoning.strip();
            return (tail.length() > 120 ? "…" + tail.substring(tail.length() - 120) : tail).replace('\n', ' ');
        }
        Matcher m = WAIT_SECTION.matcher(reasoning.substring(idx + "【本轮结论】".length()).strip());
        // 没有等待段就是没有：不拿正文冒充条件，对账时它该被当成"这轮没给条件"
        return m.find() ? m.group(1).replaceAll("\\s+", " ").strip() : "";
    }

    /**
     * 合并键：抹掉纯注解括号与空白。括号里多是"（前高/MA20 63450）"这类每轮微动的依据说明，
     * 核心价位没变就是同一个等待，不该拆成两段重复喂；但带条件词的括号
     * （"（且成交量&gt;1.5倍）"）是条件本身的一部分，抹了会把两个不同条件合成一个。
     */
    private static String waitKey(String wait) {
        return wait.replaceAll("[（(](?![^）)]*[且或><≥≤])[^）)]*[）)]", "").replaceAll("\\s+", "");
    }

    /**
     * 结算一个观望段。多轮的写成时间段+轮数——"这个条件挂了多久、耗了多少轮"本身就是
     * 保守度自检的证据（该行动没行动 vs 市场真没信号），比同一句话重复 N 遍有用。
     */
    private static void flushHold(List<TimelineEntry> out, String kind, String wait,
                                  long from, long to, int rounds) {
        if (rounds == 0) {
            return;
        }
        String tag = AiTraderDecision.KIND_ALERT.equals(kind) ? "[警报] " : "";
        String head = rounds == 1
                ? "- " + TIME_FMT.format(Instant.ofEpochMilli(from)) + " " + tag
                : "- " + TIME_FMT.format(Instant.ofEpochMilli(from)) + "~"
                  + TIME_FMT.format(Instant.ofEpochMilli(to)) + "（" + rounds + "轮）" + tag;
        String w = wait == null || wait.isEmpty() ? "（本轮未给等待条件）"
                : wait.length() > WAIT_MAX_CHARS ? wait.substring(0, WAIT_MAX_CHARS) + "…" : wait;
        out.add(new TimelineEntry(head + "等待：" + w, false));
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
            // 逐小时高低必须给：等待条件多是"回踩 63370–63480"这种区间触碰，只有收盘序列
            // 判不出"这一小时探到过没有"，模型要么瞎猜要么编，观望对账就成了假账
            StringBuilder ranges = new StringBuilder();
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
                ranges.append(ranges.isEmpty() ? "" : "→")
                        .append(plain(k.high())).append('/').append(plain(k.low()));
            }
            BigDecimal pct = open.signum() > 0
                    ? close.subtract(open).multiply(BigDecimal.valueOf(100)).divide(open, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            sb.append("- ").append(symbol).append(": 开 ").append(plain(open))
                    .append(" → 收 ").append(plain(close)).append("（").append(signed(pct)).append("%）")
                    .append("；最高 ").append(plain(high)).append("（").append(TIME_FMT.format(Instant.ofEpochMilli(highAt)))
                    .append("）最低 ").append(plain(low)).append("（").append(TIME_FMT.format(Instant.ofEpochMilli(lowAt)))
                    .append("）\n  1h收盘: ").append(closes)
                    .append("\n  1h高/低: ").append(ranges).append('\n');
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
    // msOf/plain/signed/nullSafe/humanize 对同包 PeerInsightService 开放：
    // 同侪详情与复盘素材是同一批数字的两种视角，格式化各写一套迟早会出现"两处口径对不上"

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

    static long msOf(java.time.LocalDateTime t) {
        return t == null ? 0 : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    static String signed(BigDecimal v) {
        if (v == null) {
            return "?";
        }
        return v.signum() >= 0 ? "+" + v.toPlainString() : v.toPlainString();
    }

    static String nullSafe(String s) {
        return s == null ? "—" : s;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    static String humanize(long ms) {
        long min = Math.max(0, ms / 60_000);
        if (min < 120) {
            return min + "分钟";
        }
        long hours = min / 60;
        return hours < 48 ? hours + "小时" : (hours / 24) + "天";
    }
}
