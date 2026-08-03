package com.mawai.wiibsim.campaign.score;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibsim.campaign.mapper.CampaignCarryoverMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 【为什么是"次数制"而不是逐笔判分】所有阶梯的分值都只与"第几次"有关（见 ScoreRules 各 tier），
 * 所以次数就是充分统计量。这么拆的直接受益者是<b>重置遗留</b>：重置账户会删掉仓位/订单/预测注单，
 * 重置事务里把 countAll 的结果累加进 campaign_carryover（只存次数），算分时同 code 相加、
 * 阶梯按合并后的总次数从头累加 —— 高分档接着数、一次性档天然封顶，一周重置两次也刷不出第二份。
 * 快照与日常算分共用同一个 countAll，两边口径永远一致。
 * <p>
 * 【合约三分类为什么不复用 CategorySets】AssetSnapshotServiceImpl 那个是 private record，
 * 包外取不到；更要紧的是它做的是五分类盈亏归集（tradfi 并进 bstock 桶），
 * 而这里要的是合约三分类（加密/黄金原油/美股永续）。两边的<b>唯一真相</b>都是
 * BinanceProperties 的 commoditySymbols / tradfiSymbols 两个配置列表，
 * 读同一份配置不构成第二套口径，不会漂移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeScorer {

    public static final String CODE_ROI25 = "ROI25";
    public static final String CODE_ROI50 = "ROI50";
    public static final String CODE_ROI100 = "ROI100";
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

    private final CampaignStatsMapper statsMapper;
    private final BinanceProperties binanceProperties;
    private final CacheService cacheService;
    private final CampaignCarryoverMapper carryoverMapper;

    /**
     * 全站交易积分与罚分：userId → 明细，已并入重置遗留次数。
     * 没有任何交易也没有遗留的人不出现在结果里。
     */
    public Map<Long, List<ScoreItem>> scoreAll(Long campaignId, LocalDateTime start, LocalDateTime end) {
        Map<Long, Map<String, Integer>> counts = countAll(start, end);

        // 重置遗留：同 code 次数相加。阶梯按合并后的总次数从头累加（ScoreRules.tierSum），
        // 重置前占掉的高分位不会再吐出来
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

        // ---- 现货 ----
        Map<Long, Map<String, BigDecimal>> heldValue = loadHeldValue();
        statsMapper.listSpotSymbols(start, end, ScoreRules.SPOT_MIN_BUY).stream()
                .collect(Collectors.groupingBy(SpotSymbolRow::getUserId, LinkedHashMap::new, Collectors.toList()))
                .forEach((userId, rows) ->
                        add(result, userId, CODE_SPOT, countSpot(rows, heldValue.getOrDefault(userId, Map.of()))));

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
     * 净盈亏任务与 ROI 各档独立判：净盈亏<b>无保证金门槛</b>（1000 的绝对额本身就是门槛，
     * 见 ScoreRules.PNL_MIN 注释），ROI 各档沿用累计投入 ≥ 500。
     * 三档 ROI 独立判定：一笔 350% 且保证金达标的，25%、50%、100% 档、封神各计一次。
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

            if (roi.compareTo(ScoreRules.ROI_25) >= 0) inc(c, CODE_ROI25);
            if (roi.compareTo(ScoreRules.ROI_50) >= 0) {
                inc(c, CODE_ROI50);
                inc(c, BUCKET_PREFIX + classify(r.getSymbol(), commodity, tradfi));
            }
            if (roi.compareTo(ScoreRules.ROI_100) >= 0) inc(c, CODE_ROI100);
            if (roi.compareTo(ScoreRules.ROI_300) >= 0) inc(c, CODE_GODLY);
        }
        return c;
    }

    /**
     * 一个用户达标现货标的个数。
     * <p>
     * 收益率 =（全历史卖出净得 − 全历史买入总付 + 当前在持市值）÷ 全历史买入总付。
     * 单笔收益率能靠"只卖赚的、亏的扛着"造假，标的整体收益率造不了假。
     */
    static int countSpot(List<SpotSymbolRow> rows, Map<String, BigDecimal> heldValue) {
        int n = 0;
        for (SpotSymbolRow r : rows) {
            if (r.getBuyAll() == null || r.getBuyAll().signum() <= 0) continue;
            BigDecimal held = heldValue.getOrDefault(r.getSymbol(), BigDecimal.ZERO);
            BigDecimal ret = r.getSellAll().subtract(r.getBuyAll()).add(held)
                    .divide(r.getBuyAll(), 6, RoundingMode.HALF_UP);
            if (ret.compareTo(ScoreRules.SPOT_MIN_RETURN) >= 0) n++;
        }
        return n;
    }

    /**
     * 次数 → 明细。count 展示合并后的总次数（含重置遗留），score = 阶梯前 n 项之和。
     * <p>
     * 一次性项的语义由各自的 tier 承担：封神 tierSum 到 2 还是 20 分；
     * 止损英雄这里显式归一（count 恒 1）；三市通吃看 BUCKET_* 凑没凑齐三个。
     */
    static List<ScoreItem> toItems(Map<String, Integer> c) {
        List<ScoreItem> items = new ArrayList<>();

        addTier(items, c, CODE_ROI25, "单仓位 ROI ≥ 25%（保证金 ≥ 500）", ScoreRules::roi25Tier);
        addTier(items, c, CODE_ROI50, "单仓位 ROI ≥ 50%（保证金 ≥ 500）", ScoreRules::roi50Tier);
        addTier(items, c, CODE_ROI100, "单仓位 ROI ≥ 100%（保证金 ≥ 500）", ScoreRules::roi100Tier);
        addTier(items, c, CODE_GODLY, "单笔封神：ROI ≥ 300%", ScoreRules::godlyTier);

        long buckets = c.keySet().stream().filter(k -> k.startsWith(BUCKET_PREFIX)).count();
        if (buckets >= 3) {
            items.add(ScoreItem.of(CODE_TRIPLE, "三市通吃：加密合约 / 黄金原油 / 美股永续", 1,
                    ScoreRules.TRIPLE_MARKET));
        }

        addTier(items, c, CODE_SPOT, "现货标的整体收益 ≥ 10%（活动期买入 ≥ 1000）", ScoreRules::spotTier);
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

    /**
     * userId → (symbol → 在持市值)。缺价的标的按 0 计，不整仓丢弃。
     * <p>
     * 【缺价要留痕】按 0 计会压低该标的的整体收益率，能把一个本该达标的现货任务压到不达标 ——
     * 那是真金白银的 LDC 差额。逐条 debug 太细看不见，故末尾按标的数汇总 warn 一条，
     * 运营看日志能立刻知道"这轮有几个标的是瞎算的"。
     */
    private Map<Long, Map<String, BigDecimal>> loadHeldValue() {
        Map<Long, Map<String, BigDecimal>> out = new HashMap<>();
        Set<String> missingPrice = new HashSet<>();
        for (HeldPositionRow h : statsMapper.listHeldPositions()) {
            BigDecimal price = cacheService.getCryptoPrice(h.getSymbol());
            if (price == null) {
                log.debug("活动积分：{} 缺现价，在持市值按 0 计", h.getSymbol());
                missingPrice.add(h.getSymbol());
                continue;
            }
            out.computeIfAbsent(h.getUserId(), k -> new HashMap<>())
                    .merge(h.getSymbol(), price.multiply(h.getQty()), BigDecimal::add);
        }
        if (!missingPrice.isEmpty()) {
            log.warn("活动积分：{} 个标的缺现价，在持市值按 0 计，现货任务可能被低估：{}",
                    missingPrice.size(), missingPrice);
        }
        return out;
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
