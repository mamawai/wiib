package com.mawai.wiibsim.campaign.score;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibsim.campaign.mapper.CampaignCarryoverMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;

/**
 * 交易任务与罚分。分两步：先把每个用户各积分项的<b>达成次数</b>数出来（countAll），
 * 再由次数算分（toItems）。
 * <p>
 * 次数制：所有任务得分都从达成<b>次数</b>算出，次数是充分统计量。
 * 这么拆为了重置遗留——重置事务把 countAll 结果累加进 campaign_carryover（只存次数），
 * 算分时同 code 相加从头重算，重置刷不出第二份分；快照与日常算分共用 countAll，口径一致。
 * 合约三分类不复用 CategorySets（那是五分类盈亏归集），两边唯一真相都是 BinanceProperties 的配置列表。
 */
@Component
@RequiredArgsConstructor
public class TradeScorer {

    public static final String CODE_ROI20 = "ROI20";
    public static final String CODE_ROI40 = "ROI40";
    public static final String CODE_ROI60 = "ROI60";
    public static final String CODE_ROI100 = "ROI100";
    /** ≥40% 配额外的无限 +1。纯展示用的明细 code：countAll 不产生它，toItems 从占位结果派生 */
    public static final String CODE_ROI40_EXTRA = "ROI40_EXTRA";
    public static final String CODE_GODLY = "GODLY";
    public static final String CODE_SPOT = "SPOT";
    public static final String CODE_TRIPLE = "TRIPLE";
    public static final String CODE_PREDICTION = "PREDICTION";
    public static final String CODE_STOP_LOSS = "STOP_LOSS_HERO";
    public static final String CODE_PNL_PROFIT = "PNL_PROFIT";
    public static final String CODE_PNL_LOSS = "PNL_LOSS";
    /**
     * 触发强平 = 逐仓强平（LIQUIDATED 订单数，进 countAll、可随重置固化）
     * + 全仓爆仓（notification 事件数，只在 scoreAll 里并入 —— notification 不随重置删，
     * 进了快照就是重置一次双算一次）。
     */
    public static final String CODE_LIQ_TRIGGER = "LIQ_TRIGGER";
    /** 付费重置扣分。只存在于 carryover 表（CampaignCarryoverService.chargeExtraReset 写入），countAll 不产生 */
    public static final String CODE_RESET_EXTRA = "RESET_EXTRA";

    /**
     * 三市通吃的市场桶在次数表里的 code：BUCKET_crypto / BUCKET_commodity / BUCKET_tradfi。
     * 桶要按 code 单独存而不是存"TRIPLE 达没达成"：重置前凑齐 2 个桶的进度也得留下来，
     * 重置后补上第 3 个桶照样能拿 +15。
     */
    public static final String BUCKET_PREFIX = "BUCKET_";

    /** 三市桶的展示文案，顺序固定。score 恒 0，只为前端把"哪个市场已拿下"打上勾 */
    private static final List<Map.Entry<String, String>> MARKETS = List.of(
            Map.entry("crypto", "三市 · 加密合约一笔 ROI ≥ 50%"),
            Map.entry("commodity", "三市 · 黄金原油一笔 ROI ≥ 50%"),
            Map.entry("tradfi", "三市 · 美股永续一笔 ROI ≥ 50%"));

    private final CampaignStatsMapper statsMapper;
    private final BinanceProperties binanceProperties;
    private final CampaignCarryoverMapper carryoverMapper;

    /**
     * 全站交易积分与罚分：userId → 明细，已并入重置遗留次数。
     * 没有任何交易也没有遗留的人不出现在结果里。
     */
    public Map<Long, List<ScoreItem>> scoreAll(Long campaignId, LocalDateTime start, LocalDateTime end) {
        Map<Long, Map<String, Integer>> counts = countAll(start, end);

        // 重置遗留：同 code 次数相加。阶梯与占位都按合并后的总次数从头重算
        // （ScoreRules.tierSum / roiLadder），重置前占掉的高分位不会再吐出来
        for (CarryoverRow r : carryoverMapper.listByCampaign(campaignId)) {
            counts.computeIfAbsent(r.getUserId(), k -> new HashMap<>())
                    .merge(r.getCode(), r.getCnt(), Integer::sum);
        }

        // 全仓爆仓并入「触发强平」计数（每事件 −5）。放在遗留合并之后、算分之前：
        // 它数的是 notification 行，重置不删那张表、countAll 也不产生它，所以永远是活口径，绝不双算
        for (CountRow r : statsMapper.countCrossLiquidations(start, end)) {
            counts.computeIfAbsent(r.getUserId(), k -> new HashMap<>())
                    .merge(CODE_LIQ_TRIGGER, r.getCnt(), Integer::sum);
        }

        Map<Long, List<ScoreItem>> result = new HashMap<>();
        counts.forEach((userId, c) -> {
            List<ScoreItem> items = toItems(c);
            if (!items.isEmpty()) result.put(userId, items);
        });

        return result;
    }

    /**
     * 全站各积分项的达成次数：userId → (code → cnt)。<b>只数业务表里现存的数据</b>，不含重置遗留。
     * <p>
     * 重置前的快照（CampaignCarryoverService）与日常算分共用本方法 —— 口径只此一份，
     * 快照下来的次数就是算分会用到的次数，不存在"存的和算的不是一回事"。
     */
    public Map<Long, Map<String, Integer>> countAll(LocalDateTime start, LocalDateTime end) {
        Set<String> commodity = toSet(binanceProperties.getCommoditySymbols());
        Set<String> tradfi = toSet(binanceProperties.getTradfiSymbols());

        Map<Long, Map<String, Integer>> result = new HashMap<>();

        // ---- 合约仓位 ----
        statsMapper.listClosedPositions(start, end).stream()
                .collect(Collectors.groupingBy(ClosedPositionRow::getUserId, LinkedHashMap::new, Collectors.toList()))
                .forEach((userId, rows) ->
                        countPositions(rows, commodity, tradfi).forEach((code, cnt) -> add(result, userId, code, cnt)));

        // ---- 现货：按 (用户, 标的) 重放流水取高水位单位数，同一用户各标的单位加总进一条阶梯 ----
        statsMapper.listSpotOrders(start, end, ScoreRules.SPOT_MIN_BUY).stream()
                .collect(Collectors.groupingBy(SpotOrderRow::getUserId, LinkedHashMap::new,
                        Collectors.groupingBy(SpotOrderRow::getSymbol, LinkedHashMap::new, Collectors.toList())))
                .forEach((userId, bySymbol) -> add(result, userId, CODE_SPOT,
                        bySymbol.values().stream().mapToInt(rows -> countSpotUnits(rows, start, end)).sum()));

        // ---- 预测 / 止损 / 逐仓罚 ----
        for (CountRow r : statsMapper.countPredictionHits(start, end, ScoreRules.PREDICTION_MIN_COST)) {
            add(result, r.getUserId(), CODE_PREDICTION, r.getCnt());
        }
        for (CountRow r : statsMapper.countStopLossTriggered(start, end)) {
            add(result, r.getUserId(), CODE_STOP_LOSS, r.getCnt());
        }
        for (CountRow r : statsMapper.countIsolatedLiquidations(start, end)) {
            add(result, r.getUserId(), CODE_LIQ_TRIGGER, r.getCnt());
        }

        return result;
    }

    /**
     * 一个用户的合约仓位计数。
     * <p>
     * 净盈亏任务与 ROI 阶梯独立判：净盈亏<b>无保证金门槛</b>（1000 的绝对额本身就是门槛，
     * 见 ScoreRules.PNL_MIN 注释），ROI 阶梯沿用累计投入 ≥ 500。
     * <p>
     * 这里存的是各阈值的<b>累计</b>达标笔数（一笔 100% 的仓 ROI20/40/60/100 四个 code 都 +1），
     * "每仓只占一档"的占位分派在 toItems 里由 ScoreRules.roiLadder 从累计数派生 ——
     * 累计数才能与重置遗留同 code 相加，占位结果是算出来的、不是存出来的。
     * 三市的桶（50%）与封神（300%）独立于占位制，各自照常计数。
     */
    static Map<String, Integer> countPositions(List<ClosedPositionRow> rows,
                                               Set<String> commodity, Set<String> tradfi) {
        Map<String, Integer> c = new HashMap<>();
        for (ClosedPositionRow r : rows) {
            BigDecimal pnl = r.getNetPnl();
            if (pnl != null) {
                // 严格大于：恰好 ±1000 不算"超过"
                if (pnl.compareTo(ScoreRules.PNL_MIN) > 0) inc(c, CODE_PNL_PROFIT);
                if (pnl.negate().compareTo(ScoreRules.PNL_MIN) > 0) inc(c, CODE_PNL_LOSS);
            }

            // 门槛判的是累计投入保证金，不是仓位表那个被部分平仓减过的残值
            if (r.getInvestedMargin() == null
                    || r.getInvestedMargin().compareTo(ScoreRules.MIN_MARGIN) < 0) continue;
            // 过了上面那关就一定 investedMargin >= 500 > 0，roi() 只在 <= 0 时给 null，这里不会为空
            BigDecimal roi = r.roi();

            if (roi.compareTo(ScoreRules.ROI_20) >= 0) inc(c, CODE_ROI20);
            if (roi.compareTo(ScoreRules.ROI_40) >= 0) inc(c, CODE_ROI40);
            if (roi.compareTo(ScoreRules.ROI_50) >= 0) {
                inc(c, BUCKET_PREFIX + classify(r.getSymbol(), commodity, tradfi));
            }
            if (roi.compareTo(ScoreRules.ROI_60) >= 0) inc(c, CODE_ROI60);
            if (roi.compareTo(ScoreRules.ROI_100) >= 0) inc(c, CODE_ROI100);
            if (roi.compareTo(ScoreRules.ROI_300) >= 0) inc(c, CODE_GODLY);
        }
        return c;
    }

    /**
     * 一个 (用户, 标的) 的现货达标单位数：按成交时间重放全历史流水，每笔成交后算一次
     * 已实现收益率 =（累计卖出净得 − 累计买入总付）÷ 累计买入总付（买卖均含手续费），
     * 取<b>活动窗口内</b>的成交时刻里摸到过的最高台阶（每 10% 一档，向下取整）。
     * 高水位只进不退，从流水确定性重放、不另存状态，全量重算幂等。
     * 分母是全历史买入总付：单笔收益率能靠"只卖赚的"造假，全历史净现金流造不了假。
     */
    static int countSpotUnits(List<SpotOrderRow> rows, LocalDateTime start, LocalDateTime end) {
        BigDecimal buy = BigDecimal.ZERO;
        BigDecimal sell = BigDecimal.ZERO;
        int units = 0;
        for (SpotOrderRow r : rows) {
            if ("BUY".equals(r.getOrderSide())) {
                buy = buy.add(r.getFilledAmount()).add(r.getCommission());
            } else {
                sell = sell.add(r.getFilledAmount()).subtract(r.getCommission());
            }
            if (buy.signum() <= 0) continue;
            if (r.getFilledAt().isBefore(start) || !r.getFilledAt().isBefore(end)) continue;

            BigDecimal ratio = sell.subtract(buy).divide(buy, 6, RoundingMode.HALF_UP);
            // 亏损时 FLOOR 出负台阶，被 max(units, ·) 天然压住 —— units 从 0 起步、只升不降
            units = Math.max(units, ratio.divide(ScoreRules.SPOT_UNIT_STEP, 0, RoundingMode.FLOOR).intValue());
        }
        return units;
    }

    /**
     * 次数 → 明细。count 展示合并后的总次数（含重置遗留），score = 该项累计得分。
     * <p>
     * ROI 阶梯从累计达标笔数派生占位结果（ScoreRules.roiLadder），按档拆行下发：
     * 100/60/40 三档的 count 是占掉的名额数，溢出行与 20~40% 行的 count 是真实笔数。
     * 一次性项的语义由各自的 tier 承担：封神 tierSum 到 2 还是 25 分；
     * 止损英雄这里显式归一（count 恒 1）；三市通吃看 BUCKET_* 凑没凑齐三个。
     */
    static List<ScoreItem> toItems(Map<String, Integer> c) {
        List<ScoreItem> items = new ArrayList<>();

        ScoreRules.RoiLadder ladder = ScoreRules.roiLadder(
                c.getOrDefault(CODE_ROI20, 0), c.getOrDefault(CODE_ROI40, 0),
                c.getOrDefault(CODE_ROI60, 0), c.getOrDefault(CODE_ROI100, 0));
        if (ladder.n100() > 0) {
            items.add(ScoreItem.of(CODE_ROI100, "单仓位 ROI ≥ 100%（保证金 ≥ 500）",
                    ladder.n100(), ladder.n100() * ScoreRules.LADDER_100_POINTS));
        }
        if (ladder.n60() > 0) {
            items.add(ScoreItem.of(CODE_ROI60, "单仓位 ROI ≥ 60%（保证金 ≥ 500）",
                    ladder.n60(), ladder.n60() * ScoreRules.LADDER_60_POINTS));
        }
        if (ladder.n40() > 0) {
            items.add(ScoreItem.of(CODE_ROI40, "单仓位 ROI ≥ 40%（保证金 ≥ 500）",
                    ladder.n40(), ladder.n40() * ScoreRules.LADDER_40_POINTS));
        }
        if (ladder.extra40() > 0) {
            items.add(ScoreItem.of(CODE_ROI40_EXTRA, "ROI ≥ 40% 配额外加分", ladder.extra40(), ladder.extra40()));
        }
        if (ladder.band20() > 0) {
            items.add(ScoreItem.of(CODE_ROI20, "单仓位 ROI 20% ~ 40%（保证金 ≥ 500）",
                    ladder.band20(), Math.min(ladder.band20(), ScoreRules.LADDER_20_SLOTS)));
        }

        addTier(items, c, CODE_GODLY, "单笔封神：ROI ≥ 300%", ScoreRules::godlyTier);

        for (Map.Entry<String, String> m : MARKETS) {
            int n = c.getOrDefault(BUCKET_PREFIX + m.getKey(), 0);
            if (n > 0) items.add(ScoreItem.of(BUCKET_PREFIX + m.getKey(), m.getValue(), n, 0));
        }
        long buckets = c.keySet().stream().filter(k -> k.startsWith(BUCKET_PREFIX)).count();
        if (buckets >= 3) {
            items.add(ScoreItem.of(CODE_TRIPLE, "三市通吃：加密合约 / 黄金原油 / 美股永续", 1,
                    ScoreRules.TRIPLE_MARKET));
        }

        addTier(items, c, CODE_SPOT, "现货达标单位：已实现收益每摸高 10% 记一个", ScoreRules::spotTier);
        addTier(items, c, CODE_PREDICTION, "预测市场持有到结算且猜中", ScoreRules::predictionTier);

        if (c.getOrDefault(CODE_STOP_LOSS, 0) > 0) {
            items.add(ScoreItem.of(CODE_STOP_LOSS, "止损英雄：挂过止损并被触发", 1, ScoreRules.STOP_LOSS_HERO));
        }

        addTier(items, c, CODE_PNL_PROFIT, "单仓位净利润 > 1000", ScoreRules::pnlProfitTier);

        int loss = c.getOrDefault(CODE_PNL_LOSS, 0);
        if (loss > 0) {
            items.add(ScoreItem.of(CODE_PNL_LOSS, "单仓位净亏损 > 1000", loss, loss * ScoreRules.PNL_LOSS));
        }
        int liq = c.getOrDefault(CODE_LIQ_TRIGGER, 0);
        if (liq > 0) {
            items.add(ScoreItem.of(CODE_LIQ_TRIGGER, "触发强平：逐仓强平 / 全仓爆仓", liq,
                    liq * ScoreRules.PENALTY_LIQ_TRIGGER));
        }
        int extraResets = c.getOrDefault(CODE_RESET_EXTRA, 0);
        if (extraResets > 0) {
            items.add(ScoreItem.of(CODE_RESET_EXTRA, "付费重置账户", extraResets,
                    extraResets * ScoreRules.RESET_EXTRA));
        }
        return items;
    }

    private static void addTier(List<ScoreItem> items, Map<String, Integer> c,
                                String code, String label, IntUnaryOperator tier) {
        int n = c.getOrDefault(code, 0);
        if (n > 0) items.add(ScoreItem.of(code, label, n, ScoreRules.tierSum(tier, n)));
    }

    /** 合约标的三分类。配置来源即唯一真相，不新写一套符号表 */
    static String classify(String symbol, Set<String> commodity, Set<String> tradfi) {
        if (commodity.contains(symbol)) return "commodity";
        if (tradfi.contains(symbol)) return "tradfi";
        return "crypto";
    }

    private static void inc(Map<String, Integer> c, String code) {
        c.merge(code, 1, Integer::sum);
    }

    private static void add(Map<Long, Map<String, Integer>> target, Long userId, String code, int cnt) {
        if (cnt <= 0) return;
        target.computeIfAbsent(userId, k -> new HashMap<>()).merge(code, cnt, Integer::sum);
    }

    private static Set<String> toSet(List<String> list) {
        return list == null ? Set.of() : Set.copyOf(list);
    }
}
