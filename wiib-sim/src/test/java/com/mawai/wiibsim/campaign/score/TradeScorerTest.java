package com.mawai.wiibsim.campaign.score;

import com.mawai.wiibsim.campaign.model.ClosedPositionRow;
import com.mawai.wiibsim.campaign.model.ScoreItem;
import com.mawai.wiibsim.campaign.model.SpotSymbolRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交易积分判分：计数（countPositions / countSpot）与按次数算分（toItems）合起来测。
 * 全部手搓数据，不起 Spring 不碰库。
 * <p>
 * 【重点在四处】① ROI 各档的保证金门槛用累计投入而非仓位表残值，而净盈亏任务<b>没有</b>这道门槛；
 * ② 一笔高 ROI 同时命中多档要独立累加；③ 三市通吃的三个桶来自 BinanceProperties 的两个配置列表；
 * ④ toItems 只认次数 —— 重置遗留合并进来的次数与现算的次数走同一条路，阶梯接着数、一次性档不复发。
 */
class TradeScorerTest {

    private static final Set<String> COMMODITY = Set.of("XAUUSDT", "CLUSDT");
    private static final Set<String> TRADFI = Set.of("SNDKUSDT", "SOXLUSDT", "MUUSDT", "SPCXUSDT");

    private static ClosedPositionRow pos(String symbol, String margin, String pnl, int minuteOffset) {
        ClosedPositionRow r = new ClosedPositionRow();
        r.setUserId(1L);
        r.setPositionId((long) minuteOffset);
        r.setSymbol(symbol);
        r.setMarginMode("ISOLATED");
        r.setInvestedMargin(new BigDecimal(margin));
        r.setNetPnl(new BigDecimal(pnl));
        r.setClosedAt(LocalDateTime.of(2026, 8, 3, 0, 0).plusMinutes(minuteOffset));
        return r;
    }

    /** 计数 + 算分一条龙，与生产链路（countAll → toItems）同构 */
    private static List<ScoreItem> score(List<ClosedPositionRow> rows) {
        return TradeScorer.toItems(TradeScorer.countPositions(rows, COMMODITY, TRADFI));
    }

    private static BigDecimal scoreOf(List<ScoreItem> items, String code) {
        return items.stream().filter(i -> i.code().equals(code))
                .map(ScoreItem::score).findFirst().orElse(BigDecimal.ZERO);
    }

    // ---- ROI 档的保证金门槛 ----

    /** 保证金不到 500 的仓位 ROI 上天也进不了任何 ROI 档；但净盈亏任务没有这道门槛，照算 */
    @Test
    void 保证金不足只计净盈亏不计ROI档() {
        List<ScoreItem> items = score(List.of(pos("BTCUSDT", "499", "5000", 1)));

        assertThat(scoreOf(items, "ROI25")).isEqualByComparingTo("0");
        assertThat(scoreOf(items, "ROI50")).isEqualByComparingTo("0");
        assertThat(scoreOf(items, "GODLY")).isEqualByComparingTo("0");
        assertThat(scoreOf(items, "PNL_PROFIT")).isEqualByComparingTo("1");
    }

    /** 边界：恰好 500 要算 */
    @Test
    void 保证金恰好五百算达标() {
        assertThat(scoreOf(score(List.of(pos("BTCUSDT", "500", "250", 1))), "ROI50"))
                .isEqualByComparingTo("5");
    }

    /** ROI 恰好 50%（250/500）达标，49.8% 只够 25% 档 */
    @Test
    void ROI边界按大于等于判() {
        List<ScoreItem> under = score(List.of(pos("BTCUSDT", "500", "249", 1)));
        assertThat(scoreOf(under, "ROI50")).isEqualByComparingTo("0");
        assertThat(scoreOf(under, "ROI25")).isEqualByComparingTo("1");

        assertThat(scoreOf(score(List.of(pos("BTCUSDT", "500", "250", 1))), "ROI50"))
                .isEqualByComparingTo("5");
    }

    /**
     * 一笔 350% 同时命中四档：1(25%档) + 5(50%档首笔) + 15(100%档首笔) + 20(封神) = 41。
     * 各档独立判定，不是取最高那一档。
     */
    @Test
    void 一笔高ROI各档独立累加() {
        List<ScoreItem> items = score(List.of(pos("BTCUSDT", "1000", "3500", 1)));

        assertThat(scoreOf(items, "ROI25")).isEqualByComparingTo("1");
        assertThat(scoreOf(items, "ROI50")).isEqualByComparingTo("5");
        assertThat(scoreOf(items, "ROI100")).isEqualByComparingTo("15");
        assertThat(scoreOf(items, "GODLY")).isEqualByComparingTo("20");
    }

    /** 阶梯只看总次数：7 笔 60% → 前 5 笔各 5、第 6/7 笔各 1 → 27 */
    @Test
    void 阶梯前五笔高分之后降档() {
        List<ClosedPositionRow> rows = new ArrayList<>();
        for (int i = 1; i <= 7; i++) rows.add(pos("BTCUSDT", "1000", "600", i));

        assertThat(scoreOf(score(rows), "ROI50")).isEqualByComparingTo("27");
    }

    /** 封神只发一次：第二笔 300%+ 不再加分（但 50/100 两档照常降档累加） */
    @Test
    void 封神第二笔不再加分() {
        List<ScoreItem> items = score(List.of(
                pos("BTCUSDT", "1000", "4000", 1), pos("ETHUSDT", "1000", "4000", 2)));

        assertThat(scoreOf(items, "GODLY")).isEqualByComparingTo("20");
        assertThat(scoreOf(items, "ROI100")).isEqualByComparingTo("20");   // 15 + 5
    }

    // ---- ROI ≥ 25% 档 ----

    /** 25% 档每笔 1 分、10 笔封顶：12 笔 30% 只拿 10 分，且够不着 50% 档 */
    @Test
    void ROI25档每笔一分十笔封顶() {
        List<ClosedPositionRow> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) rows.add(pos("BTCUSDT", "1000", "300", i));
        List<ScoreItem> items = score(rows);

        assertThat(items).filteredOn(i -> i.code().equals("ROI25"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.count()).isEqualTo(12);
                    assertThat(i.score()).isEqualByComparingTo("10");
                });
        assertThat(scoreOf(items, "ROI50")).isEqualByComparingTo("0");
    }

    // ---- 净盈亏任务 ----

    /** 恰好 ±1000 不算"超过"，一分不给不扣 */
    @Test
    void 净盈亏恰好一千不算超过() {
        List<ScoreItem> items = score(List.of(
                pos("BTCUSDT", "100", "1000", 1), pos("ETHUSDT", "100", "-1000", 2)));

        assertThat(scoreOf(items, "PNL_PROFIT")).isEqualByComparingTo("0");
        assertThat(scoreOf(items, "PNL_LOSS")).isEqualByComparingTo("0");
    }

    /** 净利润 25 仓封顶：30 仓大赚只拿 25 分，count 仍报 30 */
    @Test
    void 净利润每仓一分二十五仓封顶() {
        List<ClosedPositionRow> rows = new ArrayList<>();
        for (int i = 1; i <= 30; i++) rows.add(pos("BTCUSDT", "100", "2000", i));

        assertThat(score(rows)).filteredOn(i -> i.code().equals("PNL_PROFIT"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.count()).isEqualTo(30);
                    assertThat(i.score()).isEqualByComparingTo("25");
                });
    }

    /** 净亏损每仓 −2 不封顶：30 仓大亏就是 −60（多空双开刷分的另一腿在这儿等着） */
    @Test
    void 净亏损每仓扣二不限次数() {
        List<ClosedPositionRow> rows = new ArrayList<>();
        for (int i = 1; i <= 30; i++) rows.add(pos("BTCUSDT", "100", "-2000", i));

        assertThat(score(rows)).filteredOn(i -> i.code().equals("PNL_LOSS"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.count()).isEqualTo(30);
                    assertThat(i.score()).isEqualByComparingTo("-60");
                });
    }

    // ---- 三市通吃 ----

    /** 三市通吃：加密/大宗/美股永续各一笔达标才给 +15 */
    @Test
    void 三市通吃要三个桶都有() {
        List<ClosedPositionRow> two = List.of(
                pos("BTCUSDT", "1000", "600", 1),
                pos("XAUUSDT", "1000", "600", 2));
        assertThat(scoreOf(score(two), "TRIPLE")).isEqualByComparingTo("0");

        List<ClosedPositionRow> three = List.of(
                pos("BTCUSDT", "1000", "600", 1),
                pos("XAUUSDT", "1000", "600", 2),
                pos("MUUSDT", "1000", "600", 3));
        assertThat(scoreOf(score(three), "TRIPLE")).isEqualByComparingTo("15");
    }

    /** 三市通吃沿用同一门槛：保证金不够的那笔不能顶桶 */
    @Test
    void 三市通吃不认保证金不足的仓位() {
        List<ClosedPositionRow> rows = List.of(
                pos("BTCUSDT", "1000", "600", 1),
                pos("XAUUSDT", "1000", "600", 2),
                pos("MUUSDT", "100", "60", 3));      // 保证金只有 100

        assertThat(scoreOf(score(rows), "TRIPLE")).isEqualByComparingTo("0");
    }

    /** 三市通吃只发一次，不因多笔重复给 */
    @Test
    void 三市通吃是一次性的() {
        List<ClosedPositionRow> rows = List.of(
                pos("BTCUSDT", "1000", "600", 1), pos("ETHUSDT", "1000", "600", 2),
                pos("XAUUSDT", "1000", "600", 3), pos("CLUSDT", "1000", "600", 4),
                pos("MUUSDT", "1000", "600", 5), pos("SOXLUSDT", "1000", "600", 6));

        assertThat(scoreOf(score(rows), "TRIPLE")).isEqualByComparingTo("15");
    }

    // ---- 重置遗留：toItems 只认次数，合并进来的与现算的走同一条路 ----

    /** 遗留 5 笔 + 新 1 笔 = 第 6 笔，只拿 1 分 —— 高分位重置刷不回来 */
    @Test
    void 遗留次数并入后阶梯接着数() {
        assertThat(scoreOf(TradeScorer.toItems(Map.of("ROI50", 6)), "ROI50"))
                .isEqualByComparingTo("26");   // 5×5 + 1
    }

    /** 遗留里已封神，重置后再封神不发第二份 20 */
    @Test
    void 遗留封神不复发() {
        assertThat(scoreOf(TradeScorer.toItems(Map.of("GODLY", 2)), "GODLY"))
                .isEqualByComparingTo("20");
    }

    /** 重置前凑了 2 个桶，重置后补上第 3 个 → 三市通吃照样成立 */
    @Test
    void 遗留市场桶与新桶拼出三市通吃() {
        Map<String, Integer> merged = Map.of(
                "BUCKET_crypto", 1, "BUCKET_commodity", 1, "BUCKET_tradfi", 1);
        assertThat(scoreOf(TradeScorer.toItems(merged), "TRIPLE")).isEqualByComparingTo("15");

        assertThat(scoreOf(TradeScorer.toItems(
                Map.of("BUCKET_crypto", 1, "BUCKET_commodity", 1)), "TRIPLE"))
                .isEqualByComparingTo("0");
    }

    // ---- 现货 ----

    private static SpotSymbolRow spot(String symbol, String buyWindow, String buyAll, String sellAll) {
        SpotSymbolRow r = new SpotSymbolRow();
        r.setUserId(1L);
        r.setSymbol(symbol);
        r.setBuyInWindow(new BigDecimal(buyWindow));
        r.setBuyAll(new BigDecimal(buyAll));
        r.setSellAll(new BigDecimal(sellAll));
        return r;
    }

    private static List<ScoreItem> scoreSpot(List<SpotSymbolRow> rows, Map<String, BigDecimal> held) {
        return TradeScorer.toItems(Map.of("SPOT", TradeScorer.countSpot(rows, held)));
    }

    /** 收益率 =（卖出 − 买入 + 在持市值）÷ 买入。买 1000 卖 500 还剩值 700 的货 → 20% */
    @Test
    void 现货收益率含在持市值() {
        List<ScoreItem> items = scoreSpot(
                List.of(spot("BTCUSDT", "1000", "1000", "500")),
                Map.of("BTCUSDT", new BigDecimal("700")));

        assertThat(scoreOf(items, "SPOT")).isEqualByComparingTo("5");
    }

    /** 不到 10% 不给分：买 1000 卖 500 只剩值 550 → 5% */
    @Test
    void 现货收益不足十个点不计分() {
        assertThat(scoreSpot(
                List.of(spot("BTCUSDT", "1000", "1000", "500")),
                Map.of("BTCUSDT", new BigDecimal("550")))).isEmpty();
    }

    /** 前 3 个标的各 5 分，第 4 个起各 1 分 */
    @Test
    void 现货前三个标的高分之后降档() {
        List<SpotSymbolRow> rows = List.of(
                spot("BTCUSDT", "5000", "1000", "1500"),
                spot("ETHUSDT", "4000", "1000", "1500"),
                spot("SOLUSDT", "3000", "1000", "1500"),
                spot("XRPUSDT", "2000", "1000", "1500"));

        // 5 + 5 + 5 + 1
        assertThat(scoreOf(scoreSpot(rows, Map.of()), "SPOT")).isEqualByComparingTo("16");
    }

    /** 全卖光（在持为 0）也算数，不能因为查不到持仓就跳过 */
    @Test
    void 清仓标的照常算收益() {
        assertThat(scoreOf(scoreSpot(
                List.of(spot("BTCUSDT", "1000", "1000", "1200")), Map.of()), "SPOT"))
                .isEqualByComparingTo("5");
    }
}
