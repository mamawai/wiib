package com.mawai.wiibsim.campaign.score;

import com.mawai.wiibsim.campaign.model.ClosedPositionRow;
import com.mawai.wiibsim.campaign.model.ScoreItem;
import com.mawai.wiibsim.campaign.model.SpotOrderRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交易积分判分：计数（countPositions / countSpotUnits）与按次数算分（toItems）合起来测。
 * 全部手搓数据，不起 Spring 不碰库。
 * <p>
 * 【重点在四处】① ROI 各档的保证金门槛用累计投入而非仓位表残值，而净盈亏任务<b>没有</b>这道门槛；
 * ② 占位制一仓只占一档、高档满了往下顺延，封神与三市桶独立共享不占名额；
 * ③ 现货是已实现收益的高水位棘轮，只进不退、窗口外的时刻不取数；
 * ④ toItems 只认次数 —— 重置遗留合并进来的次数与现算的次数走同一条路，阶梯接着数、一次性档不复发。
 */
class TradeScorerTest {

    private static final Set<String> COMMODITY = Set.of("XAUUSDT", "CLUSDT");
    private static final Set<String> TRADFI = Set.of("SNDKUSDT", "SOXLUSDT", "MUUSDT", "SPCXUSDT");

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 15, 0, 0);

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

        assertThat(items).extracting(ScoreItem::code)
                .doesNotContain("ROI20", "ROI40", "ROI60", "ROI100", "GODLY", "BUCKET_crypto");
        assertThat(scoreOf(items, "PNL_PROFIT")).isEqualByComparingTo("1");
    }

    /** 边界：恰好 500 要算。250/500 = 50% 落 [40,60) 档 +3，同时顶起三市的桶 */
    @Test
    void 保证金恰好五百算达标() {
        List<ScoreItem> items = score(List.of(pos("BTCUSDT", "500", "250", 1)));

        assertThat(scoreOf(items, "ROI40")).isEqualByComparingTo("3");
        assertThat(scoreOf(items, "BUCKET_crypto")).isEqualByComparingTo("0");
        assertThat(items).extracting(ScoreItem::code).contains("BUCKET_crypto");
    }

    /** ROI 边界按大于等于判：19.9% 无档可进，恰好 20% 进 20 档，恰好 40% 进 40 档 */
    @Test
    void ROI边界按大于等于判() {
        assertThat(score(List.of(pos("BTCUSDT", "1000", "199", 1))))
                .extracting(ScoreItem::code).doesNotContain("ROI20", "ROI40");

        List<ScoreItem> at20 = score(List.of(pos("BTCUSDT", "1000", "200", 1)));
        assertThat(scoreOf(at20, "ROI20")).isEqualByComparingTo("1");
        assertThat(scoreOf(at20, "ROI40")).isEqualByComparingTo("0");

        List<ScoreItem> at40 = score(List.of(pos("BTCUSDT", "1000", "400", 1)));
        assertThat(scoreOf(at40, "ROI40")).isEqualByComparingTo("3");
        assertThat(scoreOf(at40, "ROI20")).isEqualByComparingTo("0");
    }

    // ---- 占位制：一仓只占一档 ----

    /** 一笔 350% 只占 100 档（+15），不再同时吃满四档；封神与桶独立共享，不占名额 */
    @Test
    void 一仓只占一档封神与桶独立共享() {
        List<ScoreItem> items = score(List.of(pos("BTCUSDT", "1000", "3500", 1)));

        assertThat(scoreOf(items, "ROI100")).isEqualByComparingTo("15");
        assertThat(scoreOf(items, "ROI60")).isEqualByComparingTo("0");
        assertThat(scoreOf(items, "ROI40")).isEqualByComparingTo("0");
        assertThat(scoreOf(items, "ROI20")).isEqualByComparingTo("0");
        assertThat(scoreOf(items, "GODLY")).isEqualByComparingTo("25");
        assertThat(items).filteredOn(i -> i.code().equals("BUCKET_crypto"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.count()).isEqualTo(1);
                    assertThat(i.score()).isEqualByComparingTo("0");
                });
    }

    /** 拍板过的场景：第 1 笔 100% 占掉 100 档，第 2 笔 100% 顺延进 60 档拿 +5 */
    @Test
    void 第二笔百分百顺延进六十档() {
        List<ScoreItem> items = score(List.of(
                pos("BTCUSDT", "1000", "1000", 1), pos("ETHUSDT", "1000", "1000", 2)));

        assertThat(scoreOf(items, "ROI100")).isEqualByComparingTo("15");
        assertThat(scoreOf(items, "ROI60")).isEqualByComparingTo("5");
        assertThat(scoreOf(items, "GODLY")).isEqualByComparingTo("0");
    }

    /** 40 档满 5 笔后溢出每笔 +1 无限：9 笔 45% → 5×3 + 4×1 */
    @Test
    void 四十档满后溢出每笔一分无限() {
        List<ClosedPositionRow> rows = new ArrayList<>();
        for (int i = 1; i <= 9; i++) rows.add(pos("BTCUSDT", "1000", "450", i));
        List<ScoreItem> items = score(rows);

        assertThat(items).filteredOn(i -> i.code().equals("ROI40"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.count()).isEqualTo(5);
                    assertThat(i.score()).isEqualByComparingTo("15");
                });
        assertThat(items).filteredOn(i -> i.code().equals("ROI40_EXTRA"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.count()).isEqualTo(4);
                    assertThat(i.score()).isEqualByComparingTo("4");
                });
    }

    /** 20~40% 档 20 笔封顶后 0 分，count 报真实笔数；这些仓永远吃不到溢出 +1 */
    @Test
    void 低档二十笔封顶不吃溢出() {
        List<ClosedPositionRow> rows = new ArrayList<>();
        for (int i = 1; i <= 25; i++) rows.add(pos("BTCUSDT", "1000", "300", i));
        List<ScoreItem> items = score(rows);

        assertThat(items).filteredOn(i -> i.code().equals("ROI20"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.count()).isEqualTo(25);
                    assertThat(i.score()).isEqualByComparingTo("20");
                });
        assertThat(items).extracting(ScoreItem::code).doesNotContain("ROI40", "ROI40_EXTRA");
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

    /** 净利润 20 仓封顶：30 仓大赚只拿 20 分，count 仍报 30 */
    @Test
    void 净利润每仓一分二十仓封顶() {
        List<ClosedPositionRow> rows = new ArrayList<>();
        for (int i = 1; i <= 30; i++) rows.add(pos("BTCUSDT", "100", "2000", i));

        assertThat(score(rows)).filteredOn(i -> i.code().equals("PNL_PROFIT"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.count()).isEqualTo(30);
                    assertThat(i.score()).isEqualByComparingTo("20");
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

    /** 桶的门槛独立于阶梯保持 50%：三个市场各一笔 45% 能占 40 档名额，却顶不起任何一个桶 */
    @Test
    void 桶门槛五十独立于阶梯四十() {
        List<ScoreItem> items = score(List.of(
                pos("BTCUSDT", "1000", "450", 1),
                pos("XAUUSDT", "1000", "450", 2),
                pos("MUUSDT", "1000", "450", 3)));

        assertThat(scoreOf(items, "ROI40")).isEqualByComparingTo("9");
        assertThat(items).extracting(ScoreItem::code)
                .doesNotContain("BUCKET_crypto", "BUCKET_commodity", "BUCKET_tradfi", "TRIPLE");
    }

    /** 桶达成状态单独下发（score 0），前端拿它打勾；没凑齐三个就没有 TRIPLE 行 */
    @Test
    void 桶达成状态单独下发用于打勾() {
        List<ScoreItem> items = score(List.of(
                pos("BTCUSDT", "1000", "600", 1),
                pos("ETHUSDT", "1000", "600", 2),
                pos("XAUUSDT", "1000", "500", 3)));

        assertThat(items).filteredOn(i -> i.code().equals("BUCKET_crypto"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.count()).isEqualTo(2);
                    assertThat(i.score()).isEqualByComparingTo("0");
                });
        assertThat(items).filteredOn(i -> i.code().equals("BUCKET_commodity"))
                .singleElement()
                .satisfies(i -> assertThat(i.count()).isEqualTo(1));
        assertThat(items).extracting(ScoreItem::code).doesNotContain("BUCKET_tradfi", "TRIPLE");
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

    /** 遗留 + 新交易合并成 6 笔 ≥40%：40 档占满 5 笔 +15，第 6 笔进溢出 +1 —— 高分位重置刷不回来 */
    @Test
    void 遗留次数并入后占位接着排() {
        List<ScoreItem> items = TradeScorer.toItems(Map.of("ROI20", 6, "ROI40", 6));

        assertThat(scoreOf(items, "ROI40")).isEqualByComparingTo("15");
        assertThat(scoreOf(items, "ROI40_EXTRA")).isEqualByComparingTo("1");
    }

    /** 遗留里已封神，重置后再封神不发第二份 25 */
    @Test
    void 遗留封神不复发() {
        assertThat(scoreOf(TradeScorer.toItems(Map.of("GODLY", 2)), "GODLY"))
                .isEqualByComparingTo("25");
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

    // ---- 现货：已实现收益的高水位棘轮 ----

    private static SpotOrderRow ord(String side, String amount, String fee, int minuteOffset) {
        SpotOrderRow r = new SpotOrderRow();
        r.setUserId(1L);
        r.setSymbol("BTCUSDT");
        r.setOrderSide(side);
        r.setFilledAmount(new BigDecimal(amount));
        r.setCommission(new BigDecimal(fee));
        r.setFilledAt(LocalDateTime.of(2026, 8, 3, 0, 0).plusMinutes(minuteOffset));
        return r;
    }

    /** 活动窗口开始前的成交 */
    private static SpotOrderRow ordJuly(String side, String amount, String fee, int minuteOffset) {
        SpotOrderRow r = ord(side, amount, fee, minuteOffset);
        r.setFilledAt(LocalDateTime.of(2026, 7, 1, 0, 0).plusMinutes(minuteOffset));
        return r;
    }

    private static int units(List<SpotOrderRow> rows) {
        return TradeScorer.countSpotUnits(rows, START, END);
    }

    /** 验收例子：BTC 花 2000 卖回 2400（20% = 2 单位）+ 闪迪 1000 卖回 2000（100% = 10 单位）→ 3×5 + 9×1 = 24 */
    @Test
    void 现货单位制验收例子() {
        int btc = units(List.of(ord("BUY", "2000", "0", 1), ord("SELL", "2400", "0", 2)));
        int sndkb = units(List.of(ord("BUY", "1000", "0", 3), ord("SELL", "2000", "0", 4)));

        assertThat(btc).isEqualTo(2);
        assertThat(sndkb).isEqualTo(10);
        assertThat(scoreOf(TradeScorer.toItems(Map.of("SPOT", btc + sndkb)), "SPOT"))
                .isEqualByComparingTo("24");
    }

    /** 棘轮只进不退：摸到 10% 后加仓亏回去单位不回收；爬回 12.5% 不加，要摸到 20% 才有第 2 个 */
    @Test
    void 现货高水位只进不退() {
        assertThat(units(List.of(
                ord("BUY", "1000", "0", 1),
                ord("SELL", "1100", "0", 2),    // (1100-1000)/1000 = 10% → 1 单位
                ord("BUY", "1000", "0", 3),     // (1100-2000)/2000 = -45%，不回收
                ord("SELL", "1150", "0", 4),    // (2250-2000)/2000 = 12.5%，没到下一台阶
                ord("SELL", "150", "0", 5))))   // (2400-2000)/2000 = 20% → 2 单位
                .isEqualTo(2);
    }

    /** 手续费两头都算：买付 1010、卖净得 1188 → 17.6%，只算 1 个单位 */
    @Test
    void 现货收益率含手续费() {
        assertThat(units(List.of(
                ord("BUY", "1000", "10", 1),
                ord("SELL", "1200", "12", 2))))
                .isEqualTo(1);
    }

    /** 窗口外的时刻不取数：活动前摸到 50% 不算成绩，但那段成本一直沉在分母里 */
    @Test
    void 现货窗口外高水位不算() {
        assertThat(units(List.of(
                ordJuly("BUY", "1000", "0", 1),
                ordJuly("SELL", "1500", "0", 2),   // 活动前就实现了 50%，不取数
                ord("BUY", "1000", "0", 3))))      // 窗口内首个时刻：(1500-2000)/2000 = -25%
                .isZero();
    }

    /** 亏损标的就是 0 个单位，不会出负数 */
    @Test
    void 现货亏损不出负单位() {
        assertThat(units(List.of(ord("BUY", "1000", "0", 1), ord("SELL", "500", "0", 2))))
                .isZero();
    }

    /** 单位阶梯封顶：26 个单位 = 3×5 + 20×1，第 24 个起 0 分，count 报真实单位数 */
    @Test
    void 现货单位阶梯限二十次加一() {
        assertThat(TradeScorer.toItems(Map.of("SPOT", 26)))
                .filteredOn(i -> i.code().equals("SPOT"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.count()).isEqualTo(26);
                    assertThat(i.score()).isEqualByComparingTo("35");
                });
    }
}
