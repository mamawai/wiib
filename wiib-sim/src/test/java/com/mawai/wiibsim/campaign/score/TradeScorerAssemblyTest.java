package com.mawai.wiibsim.campaign.score;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibsim.campaign.mapper.CampaignCarryoverMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.model.CarryoverRow;
import com.mawai.wiibsim.campaign.model.ClosedPositionRow;
import com.mawai.wiibsim.campaign.model.CountRow;
import com.mawai.wiibsim.campaign.model.ScoreItem;
import com.mawai.wiibsim.campaign.model.SpotOrderRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

/**
 * scoreAll 的组装半边：取数、分组、并遗留、拼明细。判分本身由 TradeScorerTest 管，这里不重复测阶梯。
 * <p>
 * scoreAll 是本模块唯一的生产入口，搬运错（正负号、归集键、并遗留并错人）不会让静态函数用例红，
 * 却直接改变发出去的 LDC 数额——所以这半边非测不可。
 * count 与 score 成对断言（extracting 三元组），只看 score 会漏掉计数串行。
 */
class TradeScorerAssemblyTest {

    private static final long CAMPAIGN_ID = 7L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 15, 0, 0);

    private static final long RICH = 1L;   // 各类明细齐全的用户
    private static final long PLAIN = 2L;  // 只有一笔合约 + 一个不达标现货标的

    private CampaignStatsMapper statsMapper;
    private BinanceProperties binanceProperties;
    private CampaignCarryoverMapper carryoverMapper;
    private TradeScorer scorer;

    @BeforeEach
    void setUp() {
        statsMapper = mock(CampaignStatsMapper.class);
        binanceProperties = mock(BinanceProperties.class);
        carryoverMapper = mock(CampaignCarryoverMapper.class);
        when(carryoverMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of());
        scorer = new TradeScorer(statsMapper, binanceProperties, carryoverMapper);

        when(binanceProperties.getCommoditySymbols()).thenReturn(List.of("XAUUSDT", "CLUSDT"));
        when(binanceProperties.getTradfiSymbols()).thenReturn(List.of("MUUSDT", "SOXLUSDT"));

        // ---- 合约：RICH 两笔 350%（占位制下 1 进 100 档、1 顺延 60 档），PLAIN 一笔 60% ----
        when(statsMapper.listClosedPositions(START, END)).thenReturn(List.of(
                pos(RICH, "BTCUSDT", "1000", "3500"),
                pos(RICH, "ETHUSDT", "1000", "3500"),
                pos(PLAIN, "XAUUSDT", "1000", "600")));

        // ---- 现货流水：RICH 买 1000 卖 1200（已实现 20% = 2 单位），PLAIN 买 3000 只卖回 500（负收益，0 单位）----
        when(statsMapper.listSpotOrders(START, END, ScoreRules.SPOT_MIN_BUY)).thenReturn(List.of(
                spotOrder(RICH, "BTCUSDT", "BUY", "1000", 1),
                spotOrder(RICH, "BTCUSDT", "SELL", "1200", 2),
                spotOrder(PLAIN, "BTCUSDT", "BUY", "3000", 1),
                spotOrder(PLAIN, "BTCUSDT", "SELL", "500", 2)));

        // ---- 四类计数：全挂在 RICH 名下 ----
        // 预测给 12 次：跨过两个降档点（3 次后降 1 分、10 次后归零），
        // 给 3 次的话 3×3 与阶梯和相等，退化回"每次 +3"的老实现也不会红
        when(statsMapper.countPredictionHits(START, END, ScoreRules.PREDICTION_MIN_COST))
                .thenReturn(List.of(count(RICH, 12)));
        when(statsMapper.countStopLossTriggered(START, END)).thenReturn(List.of(count(RICH, 4)));
        when(statsMapper.countIsolatedLiquidations(START, END)).thenReturn(List.of(count(RICH, 2)));
        // 全仓爆仓事件并入「触发强平」：2 逐仓 + 1 全仓 = 3 次 −15
        when(statsMapper.countCrossLiquidations(START, END)).thenReturn(List.of(count(RICH, 1)));
    }

    /**
     * 全部来源（合约占位 / 净盈亏 / 现货 / 预测 / 止损 / 逐仓罚）拼进同一个用户的清单，
     * 且 code、count、score 三者一一对得上。
     */
    @Test
    void 全部明细组装进同一个用户的清单() {
        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(result.get(RICH))
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(
                        tuple("ROI100", 1, BigDecimal.valueOf(15)),         // 首笔占 100 档
                        tuple("ROI60", 1, BigDecimal.valueOf(5)),           // 第二笔顺延 60 档
                        tuple("GODLY", 2, BigDecimal.valueOf(25)),          // 计分只认首笔
                        tuple("BUCKET_crypto", 2, BigDecimal.valueOf(0)),   // 打勾用，不给分
                        tuple("SPOT", 2, BigDecimal.valueOf(10)),           // 已实现 20% = 2 单位
                        tuple("PREDICTION", 12, BigDecimal.valueOf(16)),    // 3×3 + 7×1 + 2×0
                        tuple("STOP_LOSS_HERO", 1, BigDecimal.valueOf(3)),  // 不按次数
                        tuple("PNL_PROFIT", 2, BigDecimal.valueOf(2)),      // 两仓各赚 3500
                        tuple("LIQ_TRIGGER", 3, BigDecimal.valueOf(-15)));  // 2 逐仓 + 1 全仓爆仓
    }

    /** 罚分必须是负的，且按次数乘。正负号写反是这段搬运代码最贵的一种错 */
    @Test
    void 两类罚分带负号且按次数累乘() {
        // 付费重置只出现在 carryover 表里（chargeExtraReset 写入），不出自 countAll
        when(carryoverMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of(
                carry(RICH, "RESET_EXTRA", 2)));

        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(item(result.get(RICH), "LIQ_TRIGGER"))
                .extracting(ScoreItem::count, ScoreItem::score)
                .containsExactly(3, BigDecimal.valueOf(-15));
        assertThat(item(result.get(RICH), "RESET_EXTRA"))
                .extracting(ScoreItem::count, ScoreItem::score)
                .containsExactly(2, BigDecimal.valueOf(-60));
        // 两条都得是负的 —— 单看数值容易看串，这里再钉一次符号
        assertThat(item(result.get(RICH), "LIQ_TRIGGER").score().signum()).isNegative();
        assertThat(item(result.get(RICH), "RESET_EXTRA").score().signum()).isNegative();
    }

    /** 止损英雄只判有没有：触发 4 次也只记 1 次、给 3 分 */
    @Test
    void 止损英雄不按次数只发一份() {
        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(item(result.get(RICH), "STOP_LOSS_HERO"))
                .extracting(ScoreItem::count, ScoreItem::score)
                .containsExactly(1, BigDecimal.valueOf(3));
    }

    /**
     * 封神：count 报达标笔数（2 笔），score 只认首笔（25 分）。
     * 计分一次性不等于计数也归一 —— 前端要显示"你有 2 笔封神级仓位"。
     */
    @Test
    void 封神计数报达标笔数计分只认首笔() {
        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(item(result.get(RICH), "GODLY"))
                .extracting(ScoreItem::count, ScoreItem::score)
                .containsExactly(2, BigDecimal.valueOf(25));
    }

    /**
     * 现货只算已实现：PLAIN 买 3000 只卖回 500，已实现收益为负 → 一个单位没有，
     * 不因为"手里可能还持着涨了的币"给分 —— 在持市值已经彻底退出计分口径。
     */
    @Test
    void 现货只认已实现卖出不认在持() {
        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(result.get(PLAIN)).extracting(ScoreItem::code).doesNotContain("SPOT");
    }

    /** 分组是按用户切的：PLAIN 那笔 60% 算它自己的首笔（顺延前 60 档还空着），不接着 RICH 的名额往下排 */
    @Test
    void 按用户分组各自从头占位() {
        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(result).containsOnlyKeys(RICH, PLAIN);
        assertThat(result.get(PLAIN))
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(
                        tuple("ROI60", 1, BigDecimal.valueOf(5)),
                        tuple("BUCKET_commodity", 1, BigDecimal.valueOf(0)));
    }

    /**
     * 重置遗留并进对应用户：RICH 遗留 5 笔 ≥40%（快照连 ROI20 一起存），
     * 与现算的 2 笔 ≥100% 合并后占位 → 100 档 1、60 档 1、40 档 5 笔占满；
     * 只剩遗留、业务表已空的用户（重置完还没交易）也必须上榜，否则重置=积分蒸发。
     */
    @Test
    void 重置遗留并入次数且纯遗留用户也上榜() {
        long GHOST = 9L;   // 重置过、还没有任何新交易的用户
        when(carryoverMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of(
                carry(RICH, "ROI20", 5),
                carry(RICH, "ROI40", 5),
                carry(GHOST, "PREDICTION", 2),
                carry(GHOST, "LIQ_TRIGGER", 1)));

        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        // 合并后 c20=7 c40=7 c60=2 c100=2：100 档 1 笔、顺延 60 档 1 笔、遗留 5 笔占满 40 档
        assertThat(item(result.get(RICH), "ROI40"))
                .extracting(ScoreItem::count, ScoreItem::score)
                .containsExactly(5, BigDecimal.valueOf(15));
        assertThat(result.get(GHOST))
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(
                        tuple("PREDICTION", 2, BigDecimal.valueOf(6)),
                        tuple("LIQ_TRIGGER", 1, BigDecimal.valueOf(-5)));
    }

    /** 一笔交易都没有的用户不出现在结果里 */
    @Test
    void 无任何交易的用户不进结果() {
        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(result).doesNotContainKey(999L);
    }

    // ---- 手搓行 ----

    private static ScoreItem item(List<ScoreItem> items, String code) {
        return items.stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("清单里没有 " + code + " 这条：" + items));
    }

    private static ClosedPositionRow pos(long userId, String symbol, String margin, String pnl) {
        ClosedPositionRow r = new ClosedPositionRow();
        r.setUserId(userId);
        r.setPositionId(1L);
        r.setSymbol(symbol);
        r.setMarginMode("ISOLATED");
        r.setInvestedMargin(new BigDecimal(margin));
        r.setNetPnl(new BigDecimal(pnl));
        r.setClosedAt(START.plusDays(1));
        return r;
    }

    private static SpotOrderRow spotOrder(long userId, String symbol, String side, String amount, int minuteOffset) {
        SpotOrderRow r = new SpotOrderRow();
        r.setUserId(userId);
        r.setSymbol(symbol);
        r.setOrderSide(side);
        r.setFilledAmount(new BigDecimal(amount));
        r.setCommission(BigDecimal.ZERO);
        r.setFilledAt(START.plusDays(1).plusMinutes(minuteOffset));
        return r;
    }

    private static CountRow count(long userId, int cnt) {
        CountRow r = new CountRow();
        r.setUserId(userId);
        r.setCnt(cnt);
        return r;
    }

    private static CarryoverRow carry(long userId, String code, int cnt) {
        CarryoverRow r = new CarryoverRow();
        r.setUserId(userId);
        r.setCode(code);
        r.setCnt(cnt);
        return r;
    }
}
