package com.mawai.wiibsim.campaign.score;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibsim.campaign.mapper.CampaignCarryoverMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.model.CarryoverRow;
import com.mawai.wiibsim.campaign.model.ClosedPositionRow;
import com.mawai.wiibsim.campaign.model.CountRow;
import com.mawai.wiibsim.campaign.model.HeldPositionRow;
import com.mawai.wiibsim.campaign.model.ScoreItem;
import com.mawai.wiibsim.campaign.model.SpotSymbolRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * scoreAll 的组装半边：取数、分组、并遗留、拼明细。判分本身由 TradeScorerTest 管，这里不重复测阶梯。
 * <p>
 * 【为什么这半边非测不可】scoreAll 是本模块唯一的生产入口，而它全是"看着就对"的搬运代码 ——
 * 罚分正负号写反、止损英雄误按次数发、在持市值按 symbol 而不按 (user,symbol) 归集、
 * 重置遗留并错人，这几类错都不会让任何静态函数的用例变红，却每一个都直接改变发出去的 LDC 数额。
 * <p>
 * 【count 与 score 必须成对断言】计数与分值分两步算，计数串行而分值正确的话，
 * 只看 score 的用例全绿。所以这里一律用 extracting(code, count, score) 三元组比。
 */
class TradeScorerAssemblyTest {

    private static final long CAMPAIGN_ID = 7L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 15, 0, 0);

    private static final long RICH = 1L;   // 各类明细齐全的用户
    private static final long PLAIN = 2L;  // 只有一笔合约 + 一个不达标现货标的

    private CampaignStatsMapper statsMapper;
    private CacheService cacheService;
    private BinanceProperties binanceProperties;
    private CampaignCarryoverMapper carryoverMapper;
    private TradeScorer scorer;

    @BeforeEach
    void setUp() {
        statsMapper = mock(CampaignStatsMapper.class);
        cacheService = mock(CacheService.class);
        binanceProperties = mock(BinanceProperties.class);
        carryoverMapper = mock(CampaignCarryoverMapper.class);
        when(carryoverMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of());
        scorer = new TradeScorer(statsMapper, binanceProperties, cacheService, carryoverMapper);

        when(binanceProperties.getCommoditySymbols()).thenReturn(List.of("XAUUSDT", "CLUSDT"));
        when(binanceProperties.getTradfiSymbols()).thenReturn(List.of("MUUSDT", "SOXLUSDT"));

        // ---- 合约：RICH 两笔 350%（同时命中三档），PLAIN 一笔 60%（只命中 50% 档）----
        when(statsMapper.listClosedPositions(START, END)).thenReturn(List.of(
                pos(RICH, "BTCUSDT", "1000", "3500"),
                pos(RICH, "ETHUSDT", "1000", "3500"),
                pos(PLAIN, "XAUUSDT", "1000", "600")));

        // ---- 现货：三行都是"买 1000 卖 500"，唯一变量是在持市值能不能补上那 500 ----
        when(statsMapper.listSpotSymbols(START, END, ScoreRules.SPOT_MIN_BUY)).thenReturn(List.of(
                spot(RICH, "BTCUSDT", "5000"),      // RICH 持 BTC，市值 700 → 收益 20%，达标
                spot(RICH, "DOGEUSDT", "4000"),     // RICH 持 DOGE 但没缓存价 → 按 0 计，不达标
                spot(PLAIN, "BTCUSDT", "3000")));   // PLAIN 一股没持 → 按 0 计，不达标

        // ---- 在持：只有 RICH 有。DOGEUSDT 故意不 stub 价格，getCryptoPrice 返回 null ----
        when(statsMapper.listHeldPositions()).thenReturn(List.of(
                held(RICH, "BTCUSDT", "2"),
                held(RICH, "DOGEUSDT", "100")));
        when(cacheService.getCryptoPrice("BTCUSDT")).thenReturn(new BigDecimal("350"));

        // ---- 四类计数：全挂在 RICH 名下 ----
        // 预测给 12 次：跨过两个降档点（3 次后降 1 分、10 次后归零），
        // 给 3 次的话 3×5 与阶梯和相等，退化回"每次 +5"的老实现也不会红
        when(statsMapper.countPredictionHits(START, END, ScoreRules.PREDICTION_MIN_COST))
                .thenReturn(List.of(count(RICH, 12)));
        when(statsMapper.countStopLossTriggered(START, END)).thenReturn(List.of(count(RICH, 4)));
        when(statsMapper.countIsolatedLiquidations(START, END)).thenReturn(List.of(count(RICH, 2)));
        // 全仓爆仓事件并入「触发强平」：2 逐仓 + 1 全仓 = 3 次 −15
        when(statsMapper.countCrossLiquidations(START, END)).thenReturn(List.of(count(RICH, 1)));
    }

    /**
     * 全部来源（合约各档 / 净盈亏 / 现货 / 预测 / 止损 / 逐仓罚）拼进同一个用户的清单，
     * 且 code、count、score 三者一一对得上。
     */
    @Test
    void 全部明细组装进同一个用户的清单() {
        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(result.get(RICH))
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(
                        tuple("ROI25", 2, BigDecimal.valueOf(2)),           // 两笔各 1
                        tuple("ROI50", 2, BigDecimal.valueOf(10)),          // 两笔各 5
                        tuple("ROI100", 2, BigDecimal.valueOf(20)),         // 15 + 5
                        tuple("GODLY", 2, BigDecimal.valueOf(20)),          // 计分只认首笔
                        tuple("SPOT", 1, BigDecimal.valueOf(5)),            // 只有 BTC 达标
                        tuple("PREDICTION", 12, BigDecimal.valueOf(22)),    // 3×5 + 7×1 + 2×0
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
     * 封神：count 报达标笔数（2 笔），score 只认首笔（20 分）。
     * 计分一次性不等于计数也归一 —— 前端要显示"你有 2 笔封神级仓位"。
     */
    @Test
    void 封神计数报达标笔数计分只认首笔() {
        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(item(result.get(RICH), "GODLY"))
                .extracting(ScoreItem::count, ScoreItem::score)
                .containsExactly(2, BigDecimal.valueOf(20));
    }

    /**
     * 缺现价的标的按 0 计，不抛异常也不把整个人丢掉：
     * RICH 的 DOGEUSDT 没缓存价 → 该标的不达标，但 BTCUSDT 照常达标，SPOT 仍产出 count 1。
     */
    @Test
    void 缺现价的标的按零计而不是炸掉() {
        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(item(result.get(RICH), "SPOT"))
                .extracting(ScoreItem::count, ScoreItem::score)
                .containsExactly(1, BigDecimal.valueOf(5));
    }

    /**
     * 在持市值按 (用户, 标的) 归集，不是按标的全站共用。
     * <p>
     * PLAIN 和 RICH 的现货行都是 BTCUSDT、都是"买 1000 卖 500"，差别只在 RICH 手里还有 2 个币。
     * 若把在持市值按 symbol 全站共用，PLAIN 会白捡 RICH 的 700 市值、收益率从 −50% 变成 +20%，
     * 凭空多拿 5 分。所以 PLAIN 这里必须一条 SPOT 都没有。
     */
    @Test
    void 在持市值按用户隔离不串号() {
        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(result.get(PLAIN)).extracting(ScoreItem::code).doesNotContain("SPOT");
    }

    /** 分组是按用户切的：PLAIN 那笔算它自己的第 1 笔（5 分），不会接着 RICH 的序号往下排 */
    @Test
    void 按用户分组各自从第一笔起算阶梯() {
        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(result).containsOnlyKeys(RICH, PLAIN);
        assertThat(result.get(PLAIN))
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(
                        tuple("ROI25", 1, BigDecimal.valueOf(1)),
                        tuple("ROI50", 1, BigDecimal.valueOf(5)));
    }

    /**
     * 重置遗留并进对应用户：RICH 遗留 5 笔 ROI50 → 总 7 笔 = 5×5 + 2×1 = 27；
     * 只剩遗留、业务表已空的用户（重置完还没交易）也必须上榜，否则重置=积分蒸发。
     */
    @Test
    void 重置遗留并入次数且纯遗留用户也上榜() {
        long GHOST = 9L;   // 重置过、还没有任何新交易的用户
        when(carryoverMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of(
                carry(RICH, "ROI50", 5),
                carry(GHOST, "PREDICTION", 2),
                carry(GHOST, "LIQ_TRIGGER", 1)));

        Map<Long, List<ScoreItem>> result = scorer.scoreAll(CAMPAIGN_ID, START, END);

        assertThat(item(result.get(RICH), "ROI50"))
                .extracting(ScoreItem::count, ScoreItem::score)
                .containsExactly(7, BigDecimal.valueOf(27));
        assertThat(result.get(GHOST))
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(
                        tuple("PREDICTION", 2, BigDecimal.valueOf(10)),
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

    /** 一律买 1000 卖 500：达不达标全看在持市值能不能补上缺的那 500 */
    private static SpotSymbolRow spot(long userId, String symbol, String buyWindow) {
        SpotSymbolRow r = new SpotSymbolRow();
        r.setUserId(userId);
        r.setSymbol(symbol);
        r.setBuyInWindow(new BigDecimal(buyWindow));
        r.setBuyAll(new BigDecimal("1000"));
        r.setSellAll(new BigDecimal("500"));
        return r;
    }

    private static HeldPositionRow held(long userId, String symbol, String qty) {
        HeldPositionRow r = new HeldPositionRow();
        r.setUserId(userId);
        r.setSymbol(symbol);
        r.setQty(new BigDecimal(qty));
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
