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
 * 交易积分判分。全部手搓数据，不起 Spring 不碰库。
 * <p>
 * 【重点在三处】① 保证金门槛用的是累计投入而非仓位表残值；
 * ② 一笔高 ROI 同时命中多档要独立累加；③ 三市通吃的三个桶来自 BinanceProperties 的两个配置列表。
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

    private static BigDecimal scoreOf(List<ScoreItem> items, String code) {
        return items.stream().filter(i -> i.code().equals(code))
                .map(ScoreItem::score).findFirst().orElse(BigDecimal.ZERO);
    }

    /** 保证金不到 500 的一律不算，哪怕 ROI 上天 */
    @Test
    void 保证金不足的仓位完全不计分() {
        List<ScoreItem> items = TradeScorer.scorePositions(
                List.of(pos("BTCUSDT", "499", "5000", 1)), COMMODITY, TRADFI);

        assertThat(items).isEmpty();
    }

    /** 边界：恰好 500 要算 */
    @Test
    void 保证金恰好五百算达标() {
        List<ScoreItem> items = TradeScorer.scorePositions(
                List.of(pos("BTCUSDT", "500", "250", 1)), COMMODITY, TRADFI);

        assertThat(scoreOf(items, "ROI50")).isEqualByComparingTo("5");
    }

    /** ROI 恰好 50%（250/500）达标，49.9% 不达标 */
    @Test
    void ROI边界按大于等于判() {
        assertThat(TradeScorer.scorePositions(
                List.of(pos("BTCUSDT", "500", "249", 1)), COMMODITY, TRADFI)).isEmpty();
        assertThat(scoreOf(TradeScorer.scorePositions(
                List.of(pos("BTCUSDT", "500", "250", 1)), COMMODITY, TRADFI), "ROI50"))
                .isEqualByComparingTo("5");
    }

    /**
     * 一笔 350% 同时命中三档：5(50%档首笔) + 15(100%档首笔) + 20(封神) = 40。
     * 三行任务是独立判定的，不是取最高那一档。
     */
    @Test
    void 一笔高ROI三档独立累加() {
        List<ScoreItem> items = TradeScorer.scorePositions(
                List.of(pos("BTCUSDT", "1000", "3500", 1)), COMMODITY, TRADFI);

        assertThat(scoreOf(items, "ROI50")).isEqualByComparingTo("5");
        assertThat(scoreOf(items, "ROI100")).isEqualByComparingTo("15");
        assertThat(scoreOf(items, "GODLY")).isEqualByComparingTo("20");
    }

    /** 阶梯按平仓时间先后排，不按 ROI 高低排 */
    @Test
    void 阶梯按平仓时间顺序发放() {
        List<ClosedPositionRow> rows = new ArrayList<>();
        for (int i = 1; i <= 7; i++) rows.add(pos("BTCUSDT", "1000", "600", i));

        // 前 5 笔各 5，第 6、7 笔各 1 → 27
        assertThat(scoreOf(TradeScorer.scorePositions(rows, COMMODITY, TRADFI), "ROI50"))
                .isEqualByComparingTo("27");
    }

    /** 封神只发一次：第二笔 300%+ 不再加分（但 50/100 两档照常降档累加） */
    @Test
    void 封神第二笔不再加分() {
        List<ScoreItem> items = TradeScorer.scorePositions(
                List.of(pos("BTCUSDT", "1000", "4000", 1), pos("ETHUSDT", "1000", "4000", 2)),
                COMMODITY, TRADFI);

        assertThat(scoreOf(items, "GODLY")).isEqualByComparingTo("20");
        assertThat(scoreOf(items, "ROI100")).isEqualByComparingTo("20");   // 15 + 5
    }

    /** 三市通吃：加密/大宗/美股永续各一笔达标才给 +15 */
    @Test
    void 三市通吃要三个桶都有() {
        List<ClosedPositionRow> two = List.of(
                pos("BTCUSDT", "1000", "600", 1),
                pos("XAUUSDT", "1000", "600", 2));
        assertThat(scoreOf(TradeScorer.scorePositions(two, COMMODITY, TRADFI), "TRIPLE"))
                .isEqualByComparingTo("0");

        List<ClosedPositionRow> three = List.of(
                pos("BTCUSDT", "1000", "600", 1),
                pos("XAUUSDT", "1000", "600", 2),
                pos("MUUSDT", "1000", "600", 3));
        assertThat(scoreOf(TradeScorer.scorePositions(three, COMMODITY, TRADFI), "TRIPLE"))
                .isEqualByComparingTo("15");
    }

    /** 三市通吃沿用同一门槛：保证金不够的那笔不能顶桶 */
    @Test
    void 三市通吃不认保证金不足的仓位() {
        List<ClosedPositionRow> rows = List.of(
                pos("BTCUSDT", "1000", "600", 1),
                pos("XAUUSDT", "1000", "600", 2),
                pos("MUUSDT", "100", "60", 3));      // 保证金只有 100

        assertThat(scoreOf(TradeScorer.scorePositions(rows, COMMODITY, TRADFI), "TRIPLE"))
                .isEqualByComparingTo("0");
    }

    /** 三市通吃只发一次，不因多笔重复给 */
    @Test
    void 三市通吃是一次性的() {
        List<ClosedPositionRow> rows = List.of(
                pos("BTCUSDT", "1000", "600", 1), pos("ETHUSDT", "1000", "600", 2),
                pos("XAUUSDT", "1000", "600", 3), pos("CLUSDT", "1000", "600", 4),
                pos("MUUSDT", "1000", "600", 5), pos("SOXLUSDT", "1000", "600", 6));

        assertThat(scoreOf(TradeScorer.scorePositions(rows, COMMODITY, TRADFI), "TRIPLE"))
                .isEqualByComparingTo("15");
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

    /** 收益率 =（卖出 − 买入 + 在持市值）÷ 买入。买 1000 卖 500 还剩值 700 的货 → 20% */
    @Test
    void 现货收益率含在持市值() {
        List<ScoreItem> items = TradeScorer.scoreSpot(
                List.of(spot("BTCUSDT", "1000", "1000", "500")),
                Map.of("BTCUSDT", new BigDecimal("700")));

        assertThat(scoreOf(items, "SPOT")).isEqualByComparingTo("5");
    }

    /** 不到 10% 不给分：买 1000 卖 500 只剩值 550 → 5% */
    @Test
    void 现货收益不足十个点不计分() {
        assertThat(TradeScorer.scoreSpot(
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
        assertThat(scoreOf(TradeScorer.scoreSpot(rows, Map.of()), "SPOT")).isEqualByComparingTo("16");
    }

    /** 全卖光（在持为 0）也算数，不能因为查不到持仓就跳过 */
    @Test
    void 清仓标的照常算收益() {
        assertThat(scoreOf(TradeScorer.scoreSpot(
                List.of(spot("BTCUSDT", "1000", "1000", "1200")), Map.of()), "SPOT"))
                .isEqualByComparingTo("5");
    }
}
