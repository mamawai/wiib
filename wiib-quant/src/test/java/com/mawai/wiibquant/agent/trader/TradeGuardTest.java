package com.mawai.wiibquant.agent.trader;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static com.mawai.wiibquant.agent.trader.TradeGuard.validateOpen;
import static org.assertj.core.api.Assertions.assertThat;

/** 开仓硬护栏：盯主人设的仓位规格（杠杆区间/保证金区间/单仓/双开）与加仓的豁免边界。 */
class TradeGuardTest {

    private static final Set<String> WL = Set.of("BTCUSDT");
    private static final BigDecimal EQUITY = new BigDecimal("10000");
    private static final BigDecimal MARK = new BigDecimal("100000");

    /** 默认配置：杠杆 5~20，保证金 5~20%，多仓位开、双开关 */
    private TraderRiskConfig cfg() {
        return new TraderRiskConfig(5, 20, new BigDecimal("5"), new BigDecimal("20"),
                true, false, true, false);
    }

    private TraderRiskConfig cfg(boolean multi, boolean hedge) {
        return new TraderRiskConfig(5, 20, new BigDecimal("5"), new BigDecimal("20"),
                multi, hedge, true, false);
    }

    /** 基准单：0.1×100000/10 = 保证金1000 = 权益10%，落在 5~20% 区间内 */
    private TradeGuard.OpenReq base() {
        return new TradeGuard.OpenReq("BTCUSDT", "LONG", "MARKET", new BigDecimal("0.1"), 10,
                null, new BigDecimal("95000"), null, "BREAKOUT", "突破前高", "1h收盘跌回98000下方");
    }

    @Test
    void validOpenPasses() {
        assertThat(validateOpen(base(), EQUITY, MARK, WL, cfg(), List.of())).isNull();
    }

    // ---------- 杠杆区间：允许集合，不是上限 ----------

    /** 配 5~20 时选 3 也不行——低于下界同样拒，这是"必须从区间里选"的核心语义 */
    @Test
    void leverageBelowMinRejected() {
        String r = validateOpen(base().withLeverage(3), EQUITY, MARK, WL, cfg(), List.of());
        assertThat(r).contains("5~20").contains("你给了3");
    }

    @Test
    void leverageAboveMaxRejected() {
        assertThat(validateOpen(base().withLeverage(50), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("5~20");
    }

    // ---------- 保证金区间 ----------

    /**
     * 一晚 8 次贴边拒绝的实测教训：拒因里的提示数量必须照抄可过——
     * 下界向上取整、上界向下取整，模型复制建议值重试不能再次被拒陷入循环。
     */
    @Test
    void marginHintQuantitiesAreCopyPasteSafe() {
        // 0.05×100000/10=500=5%，低于 cfg 里改设的 10~15 下界 → 拒并给建议数量
        TraderRiskConfig tight = new TraderRiskConfig(5, 20, new BigDecimal("10"), new BigDecimal("15"),
                true, false, true, false);
        String reject = validateOpen(base().withQuantity(new BigDecimal("0.05")),
                EQUITY, MARK, WL, tight, List.of());
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("数量应在 (\\S+) ~ (\\S+) 之间").matcher(reject);
        assertThat(m.find()).isTrue();

        assertThat(validateOpen(base().withQuantity(new BigDecimal(m.group(1))),
                EQUITY, MARK, WL, tight, List.of())).isNull();
        assertThat(validateOpen(base().withQuantity(new BigDecimal(m.group(2))),
                EQUITY, MARK, WL, tight, List.of())).isNull();
    }

    /** 9.995% 不许四舍五入显示成"10.00%超出10~15%区间"的自相矛盾——占比原样展示 */
    @Test
    void marginPctDisplayedWithoutMisleadingRounding() {
        TraderRiskConfig tight = new TraderRiskConfig(5, 20, new BigDecimal("10"), new BigDecimal("15"),
                true, false, true, false);
        // 0.09995×100000/10=999.5 → 9.995%，贴着下界差一丝
        String r = validateOpen(base().withQuantity(new BigDecimal("0.09995")),
                EQUITY, MARK, WL, tight, List.of());
        assertThat(r).contains("9.995%").doesNotContain("10%，超出");
    }

    /** 0.02×100000/10=200=权益2% < 下界5% → 拒，且把该配的数量算给模型 */
    @Test
    void marginBelowMinRejected() {
        String r = validateOpen(base().withQuantity(new BigDecimal("0.02")), EQUITY, MARK, WL, cfg(), List.of());
        assertThat(r).contains("占权益2%").contains("数量应在");
    }

    /** 0.3×100000/10=3000=30% > 上界20% → 拒 */
    @Test
    void marginAboveMaxRejected() {
        assertThat(validateOpen(base().withQuantity(new BigDecimal("0.3")), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("占权益30%");
    }

    /** 边界含端点：0.05×100000/10=500=正好5% → 放行 */
    @Test
    void marginExactlyAtMinPasses() {
        assertThat(validateOpen(base().withQuantity(new BigDecimal("0.05")), EQUITY, MARK, WL, cfg(), List.of()))
                .isNull();
    }

    /** 限价单按限价算保证金：0.1×96000/10=960=9.6%，仍在区间内 */
    @Test
    void limitOrderMarginUsesLimitPrice() {
        TradeGuard.OpenReq req = base().withOrderType("LIMIT").withLimitPrice(new BigDecimal("96000"));
        assertThat(validateOpen(req, EQUITY, MARK, WL, cfg(), List.of())).isNull();
    }

    // ---------- 仓位数与双开 ----------

    /** 单仓模式下换标的：拒，并把占坑的仓位报给模型 */
    @Test
    void singlePositionModeRejectsAnotherSymbol() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("ETHUSDT", "LONG", 10, true));
        assertThat(validateOpen(base(), EQUITY, MARK, WL, cfg(false, false), held))
                .contains("一个仓位").contains("ETHUSDT LONG");
    }

    /** 单仓模式下对同一仓加仓：sim 会并仓不占新坑，放行 */
    @Test
    void singlePositionModeAllowsAddOn() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("BTCUSDT", "LONG", 10, true));
        assertThat(validateOpen(base(), EQUITY, MARK, WL, cfg(false, false), held)).isNull();
    }

    /** 挂单一并计数：只看持仓的话，先挂几单就能绕过单仓限制 */
    @Test
    void pendingOrderCountsAsOccupied() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("ETHUSDT", "SHORT", 10, false));
        assertThat(validateOpen(base(), EQUITY, MARK, WL, cfg(false, false), held)).contains("一个仓位");
    }

    @Test
    void hedgeRejectedWhenDisabled() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("BTCUSDT", "SHORT", 10, true));
        assertThat(validateOpen(base(), EQUITY, MARK, WL, cfg(true, false), held))
                .contains("反向仓位").contains("多空双开");
    }

    @Test
    void hedgeAllowedWhenEnabled() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("BTCUSDT", "SHORT", 10, true));
        assertThat(validateOpen(base(), EQUITY, MARK, WL, cfg(true, true), held)).isNull();
    }

    // ---------- 加仓的两条特殊规则 ----------

    /** 同币杠杆必须一致（sim 侧规则），护栏提前拦并说清该用几倍 */
    @Test
    void addOnMustMatchSymbolLeverage() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("BTCUSDT", "LONG", 10, true));
        assertThat(validateOpen(base().withLeverage(15), EQUITY, MARK, WL, cfg(), held))
                .contains("杠杆必须一致").contains("10倍");
    }

    /** 加仓量由模型自己斟酌：0.5 → 保证金5000=50%，远超20%上界也放行 */
    @Test
    void addOnSkipsMarginRange() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("BTCUSDT", "LONG", 10, true));
        assertThat(validateOpen(base().withQuantity(new BigDecimal("0.5")), EQUITY, MARK, WL, cfg(), held))
                .isNull();
    }

    /**
     * 同向挂单不算加仓：sim 只与已成交持仓并仓，把挂单当加仓等于送出一条绕过保证金区间的路——
     * 先挂一个远离现价永不成交的限价单，之后开多大都不受约束。
     */
    @Test
    void sameSidePendingOrderIsNotAnAddOn() {
        List<TradeGuard.PosSnap> pending = List.of(new TradeGuard.PosSnap("BTCUSDT", "LONG", 10, false));
        assertThat(validateOpen(base().withQuantity(new BigDecimal("0.5")), EQUITY, MARK, WL, cfg(), pending))
                .contains("占权益50%");
    }

    // ---------- 与配置无关的既有护栏 ----------

    @Test
    void blankInvalidationConditionRejected() {
        assertThat(validateOpen(base().withInvalidationCondition(" "), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("失效条件");
        assertThat(validateOpen(base().withInvalidationCondition(null), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("失效条件");
    }

    @Test
    void stopLossOnWrongSideRejected() {
        assertThat(validateOpen(base().withStopLossPrice(new BigDecimal("105000")),
                EQUITY, MARK, WL, cfg(), List.of())).contains("止损价方向错误");
    }
}
