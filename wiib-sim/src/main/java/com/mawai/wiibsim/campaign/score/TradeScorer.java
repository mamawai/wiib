package com.mawai.wiibsim.campaign.score;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 交易任务与罚分。判分是 static 纯函数，本类的实例部分只管取数与分组。
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

    public static final String CODE_ROI50 = "ROI50";
    public static final String CODE_ROI100 = "ROI100";
    public static final String CODE_GODLY = "GODLY";
    public static final String CODE_SPOT = "SPOT";
    public static final String CODE_TRIPLE = "TRIPLE";
    public static final String CODE_PREDICTION = "PREDICTION";
    public static final String CODE_STOP_LOSS = "STOP_LOSS_HERO";
    public static final String CODE_LIQ_ISOLATED = "LIQ_ISOLATED";
    public static final String CODE_LIQ_CROSS = "LIQ_CROSS";

    private final CampaignStatsMapper statsMapper;
    private final BinanceProperties binanceProperties;
    private final CacheService cacheService;

    /** 全站交易积分与罚分：userId → 明细。没有任何交易的人不出现在结果里 */
    public Map<Long, List<ScoreItem>> scoreAll(LocalDateTime start, LocalDateTime end) {
        Set<String> commodity = toSet(binanceProperties.getCommoditySymbols());
        Set<String> tradfi = toSet(binanceProperties.getTradfiSymbols());

        Map<Long, List<ScoreItem>> result = new HashMap<>();

        // ---- 合约仓位：SQL 已按 (user_id, updated_at, id) 排好，分组后顺序天然正确 ----
        statsMapper.listClosedPositions(start, end).stream()
                .collect(Collectors.groupingBy(ClosedPositionRow::getUserId, LinkedHashMap::new, Collectors.toList()))
                .forEach((userId, rows) ->
                        addAll(result, userId, scorePositions(rows, commodity, tradfi)));

        // ---- 现货：SQL 已按 (user_id, buy_in_window DESC) 排好 ----
        Map<Long, Map<String, BigDecimal>> heldValue = loadHeldValue();
        statsMapper.listSpotSymbols(start, end, ScoreRules.SPOT_MIN_BUY).stream()
                .collect(Collectors.groupingBy(SpotSymbolRow::getUserId, LinkedHashMap::new, Collectors.toList()))
                .forEach((userId, rows) ->
                        addAll(result, userId, scoreSpot(rows, heldValue.getOrDefault(userId, Map.of()))));

        // ---- 预测市场 ----
        for (CountRow r : statsMapper.countPredictionHits(start, end, ScoreRules.PREDICTION_MIN_COST)) {
            addAll(result, r.getUserId(), List.of(ScoreItem.of(CODE_PREDICTION,
                    "预测市场持有到结算且猜中", r.getCnt(), r.getCnt() * ScoreRules.PREDICTION_HIT)));
        }

        // ---- 止损英雄：只判有没有，不按次数 ----
        for (CountRow r : statsMapper.countStopLossTriggered(start, end)) {
            addAll(result, r.getUserId(), List.of(ScoreItem.of(CODE_STOP_LOSS,
                    "止损英雄：挂过止损并被触发", 1, ScoreRules.STOP_LOSS_HERO)));
        }

        // ---- 罚分 ----
        for (CountRow r : statsMapper.countIsolatedLiquidations(start, end)) {
            addAll(result, r.getUserId(), List.of(ScoreItem.of(CODE_LIQ_ISOLATED,
                    "逐仓强平", r.getCnt(), r.getCnt() * ScoreRules.PENALTY_ISOLATED)));
        }
        for (CountRow r : statsMapper.countCrossLiquidations(start, end)) {
            addAll(result, r.getUserId(), List.of(ScoreItem.of(CODE_LIQ_CROSS,
                    "全仓爆仓", r.getCnt(), r.getCnt() * ScoreRules.PENALTY_CROSS)));
        }

        return result;
    }

    /**
     * 合约仓位任务。rows 必须已按平仓时间升序 —— 阶梯的"前 N 笔"就是按这个顺序数的。
     * <p>
     * 三档独立判定：一笔 350% 且保证金达标的，50%档、100%档、封神各拿一份。
     */
    public static List<ScoreItem> scorePositions(List<ClosedPositionRow> rows,
                                                 Set<String> commodity, Set<String> tradfi) {
        int n50 = 0, n100 = 0, nGod = 0;
        int s50 = 0, s100 = 0, sGod = 0;
        Set<String> buckets = new HashSet<>();

        for (ClosedPositionRow r : rows) {
            // 门槛判的是累计投入保证金，不是仓位表那个被部分平仓减过的残值
            if (r.getInvestedMargin() == null
                    || r.getInvestedMargin().compareTo(ScoreRules.MIN_MARGIN) < 0) continue;
            // 过了上面那关就一定 investedMargin >= 500 > 0，roi() 只在 <= 0 时给 null，这里不会为空
            BigDecimal roi = r.roi();

            if (roi.compareTo(ScoreRules.ROI_50) >= 0) {
                s50 += ScoreRules.roi50Tier(++n50);
                buckets.add(classify(r.getSymbol(), commodity, tradfi));
            }
            if (roi.compareTo(ScoreRules.ROI_100) >= 0) s100 += ScoreRules.roi100Tier(++n100);
            if (roi.compareTo(ScoreRules.ROI_300) >= 0) sGod += ScoreRules.godlyTier(++nGod);
        }

        List<ScoreItem> items = new ArrayList<>(4);
        if (n50 > 0) items.add(ScoreItem.of(CODE_ROI50, "单仓位 ROI ≥ 50%（保证金 ≥ 500）", n50, s50));
        if (n100 > 0) items.add(ScoreItem.of(CODE_ROI100, "单仓位 ROI ≥ 100%（保证金 ≥ 500）", n100, s100));
        if (nGod > 0) items.add(ScoreItem.of(CODE_GODLY, "单笔封神：ROI ≥ 300%", nGod, sGod));
        if (buckets.size() >= 3) {
            items.add(ScoreItem.of(CODE_TRIPLE, "三市通吃：加密合约 / 黄金原油 / 美股永续", 1, ScoreRules.TRIPLE_MARKET));
        }
        return items;
    }

    /**
     * 现货标的任务。rows 必须已按活动期买入额降序 —— "前 3 个标的"按这个顺序数。
     * <p>
     * 收益率 =（全历史卖出净得 − 全历史买入总付 + 当前在持市值）÷ 全历史买入总付。
     * 单笔收益率能靠"只卖赚的、亏的扛着"造假，标的整体收益率造不了假。
     */
    public static List<ScoreItem> scoreSpot(List<SpotSymbolRow> rows, Map<String, BigDecimal> heldValue) {
        int n = 0, score = 0;
        for (SpotSymbolRow r : rows) {
            if (r.getBuyAll() == null || r.getBuyAll().signum() <= 0) continue;
            BigDecimal held = heldValue.getOrDefault(r.getSymbol(), BigDecimal.ZERO);
            BigDecimal ret = r.getSellAll().subtract(r.getBuyAll()).add(held)
                    .divide(r.getBuyAll(), 6, RoundingMode.HALF_UP);
            if (ret.compareTo(ScoreRules.SPOT_MIN_RETURN) >= 0) score += ScoreRules.spotTier(++n);
        }
        return n == 0 ? List.of()
                : List.of(ScoreItem.of(CODE_SPOT, "现货标的整体收益 ≥ 10%（活动期买入 ≥ 1000）", n, score));
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

    private static void addAll(Map<Long, List<ScoreItem>> target, Long userId, List<ScoreItem> items) {
        if (items.isEmpty()) return;
        target.computeIfAbsent(userId, k -> new ArrayList<>()).addAll(items);
    }

    private static Set<String> toSet(List<String> list) {
        return list == null ? Set.of() : Set.copyOf(list);
    }
}
