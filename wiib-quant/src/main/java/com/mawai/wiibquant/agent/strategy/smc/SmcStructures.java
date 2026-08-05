package com.mawai.wiibquant.agent.strategy.smc;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.SwingDetector;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SMC(聪明钱)原语库：FVG / 结构偏向 / 摆动区间。全部纯静态函数、只吃已闭合K线。
 * 4套SMC策略共用一处定义，杜绝同一概念多处实现的口径漂移；
 * 防重绘由两点保证：输入只有闭合bar(view契约) + 结构判定复用 SwingDetector 已确认pivot。
 */
public final class SmcStructures {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private SmcStructures() {
    }

    /**
     * Fair Value Gap：三根K线缺口，bar3闭合才存在。
     * 看涨=bar1.high&lt;bar3.low，区间[lower=bar1.high, upper=bar3.low]；看跌对称。
     * barIndex=bar3在输入列表中的下标（缓解/触及判定从其后开始扫）。
     */
    public record Fvg(int barIndex, long formedOpenTime, BigDecimal lower, BigDecimal upper, boolean bullish) {

        /** 中位=回补入场挂单价。两沿均值除2必然除尽，无舍入。 */
        public BigDecimal mid() {
            return lower.add(upper).divide(TWO);
        }

        /** 唯一键（形成bar openTime×10+方向），"一FVG只交易一次"的消费凭据。 */
        public long key() {
            return formedOpenTime * 10 + (bullish ? 1 : 2);
        }
    }

    /**
     * 扫描窗口内全部FVG（升序）。位移过滤：中间推动K线bar2的实体|close-open| ≥ mult×ATR
     * ——用实体不用振幅，长影线不算位移；mult≤0关闭过滤；ATR算不出(NaN)时保守跳过。
     */
    public static List<Fvg> findFvgs(List<KlineBar> bars, int atrPeriod, double displacementAtrMult) {
        List<Fvg> out = new ArrayList<>();
        if (bars == null || bars.size() < 3) return out;
        double[] atr = SwingDetector.atrSeries(bars, atrPeriod);
        for (int i = 2; i < bars.size(); i++) {
            KlineBar b1 = bars.get(i - 2), b2 = bars.get(i - 1), b3 = bars.get(i);
            boolean bullish = b1.high().compareTo(b3.low()) < 0;
            boolean bearish = b1.low().compareTo(b3.high()) > 0;
            if (!bullish && !bearish) continue;
            if (displacementAtrMult > 0) {
                double a = atr[i - 1];
                double body = b2.close().subtract(b2.open()).abs().doubleValue();
                if (Double.isNaN(a) || a <= 0 || body < displacementAtrMult * a) continue;
            }
            out.add(bullish
                    ? new Fvg(i, b3.openTime(), b1.high(), b3.low(), true)
                    : new Fvg(i, b3.openTime(), b3.high(), b1.low(), false));
        }
        return out;
    }

    /** 已缓解=形成后价格完全穿越过缺口：看涨FVG有low≤下沿 / 看跌有high≥上沿。 */
    public static boolean mitigated(Fvg fvg, List<KlineBar> bars) {
        for (int i = fvg.barIndex() + 1; i < bars.size(); i++) {
            boolean crossed = fvg.bullish()
                    ? bars.get(i).low().compareTo(fvg.lower()) <= 0
                    : bars.get(i).high().compareTo(fvg.upper()) >= 0;
            if (crossed) return true;
        }
        return false;
    }

    /** 中位已被触及=首次回补机会已被市场用掉（没接住就不追第二次触碰）。 */
    public static boolean midTouched(Fvg fvg, List<KlineBar> bars) {
        BigDecimal mid = fvg.mid();
        for (int i = fvg.barIndex() + 1; i < bars.size(); i++) {
            boolean touched = fvg.bullish()
                    ? bars.get(i).low().compareTo(mid) <= 0
                    : bars.get(i).high().compareTo(mid) >= 0;
            if (touched) return true;
        }
        return false;
    }

    /**
     * 结构偏向：最近两个HIGH pivot与最近两个LOW pivot——
     * HH+HL=+1(多头结构)、LH+LL=-1(空头结构)、混合或pivot不足=0(无偏向)。
     * 0即不交易：震荡/换向期自动停手，这正是SMC假信号的重灾区。
     */
    public static int structureBias(List<KlineBar> bars, int atrPeriod, double reversalAtrMult) {
        List<SwingDetector.Pivot> pivots = SwingDetector.confirmedPivots(bars, atrPeriod, reversalAtrMult);
        BigDecimal h2 = null, h1 = null, l2 = null, l1 = null;   // 2=较新 1=较旧
        for (int i = pivots.size() - 1; i >= 0 && (h1 == null || l1 == null); i--) {
            SwingDetector.Pivot p = pivots.get(i);
            if (p.type() == SwingDetector.PivotType.HIGH) {
                if (h2 == null) h2 = p.price();
                else if (h1 == null) h1 = p.price();
            } else {
                if (l2 == null) l2 = p.price();
                else if (l1 == null) l1 = p.price();
            }
        }
        if (h1 == null || l1 == null) return 0;
        if (h2.compareTo(h1) > 0 && l2.compareTo(l1) > 0) return 1;
        if (h2.compareTo(h1) < 0 && l2.compareTo(l1) < 0) return -1;
        return 0;
    }

    /** 摆动区间=最近已确认HIGH/LOW pivot围成，Premium/Discount分界与"对面流动性"止盈都锚在这。 */
    public record SwingRange(BigDecimal high, BigDecimal low) {

        /** 50%均衡位：上方Premium、下方Discount。 */
        public BigDecimal equilibrium() {
            return high.add(low).divide(TWO);
        }
    }

    public static Optional<SwingRange> swingRange(List<KlineBar> bars, int atrPeriod, double reversalAtrMult) {
        List<SwingDetector.Pivot> pivots = SwingDetector.confirmedPivots(bars, atrPeriod, reversalAtrMult);
        BigDecimal high = null, low = null;
        for (int i = pivots.size() - 1; i >= 0 && (high == null || low == null); i--) {
            SwingDetector.Pivot p = pivots.get(i);
            if (p.type() == SwingDetector.PivotType.HIGH && high == null) high = p.price();
            if (p.type() == SwingDetector.PivotType.LOW && low == null) low = p.price();
        }
        if (high == null || low == null || high.compareTo(low) <= 0) return Optional.empty();
        return Optional.of(new SwingRange(high, low));
    }
}
