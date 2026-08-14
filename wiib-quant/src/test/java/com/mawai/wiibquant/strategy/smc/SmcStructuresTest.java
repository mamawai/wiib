package com.mawai.wiibquant.strategy.smc;

import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmcStructuresTest {

    private static final long M5 = 5 * 60_000L;

    // ---- FVG 检测 ----

    @Test
    void detectsBullishFvgWithZoneEdges() {
        List<KlineBar> bars = bars(
                b(100, 101, 99, 100),
                b(100, 105, 99.5, 104.8),
                b(104.8, 106, 102, 105));   // bar1.high=101 < bar3.low=102

        List<SmcStructures.Fvg> fvgs = SmcStructures.findFvgs(bars, 3, 0.0);

        assertEquals(1, fvgs.size());
        SmcStructures.Fvg f = fvgs.getFirst();
        assertTrue(f.bullish());
        assertEquals(0, f.lower().compareTo(new BigDecimal("101")), "下沿=bar1.high");
        assertEquals(0, f.upper().compareTo(new BigDecimal("102")), "上沿=bar3.low");
        assertEquals(0, f.mid().compareTo(new BigDecimal("101.5")), "中位=两沿均值");
    }

    @Test
    void detectsBearishFvgMirrored() {
        List<KlineBar> bars = bars(
                b(100, 101, 99, 100),
                b(100, 100.5, 95, 95.2),
                b(95.2, 96, 94, 95));       // bar1.low=99 > bar3.high=96

        List<SmcStructures.Fvg> fvgs = SmcStructures.findFvgs(bars, 3, 0.0);

        assertEquals(1, fvgs.size());
        SmcStructures.Fvg f = fvgs.getFirst();
        assertFalse(f.bullish());
        assertEquals(0, f.lower().compareTo(new BigDecimal("96")), "下沿=bar3.high");
        assertEquals(0, f.upper().compareTo(new BigDecimal("99")), "上沿=bar1.low");
    }

    @Test
    void displacementFilterDropsWeakImpulse() {
        // 三根平K定ATR(≈2.33)，随后推动K实体2.8：mult=1.0过、mult=2.0滤掉
        List<KlineBar> bars = bars(
                b(100, 101, 99, 100),
                b(100, 101, 99, 100),
                b(100, 101, 99, 100),
                b(100, 103, 100, 102.8),
                b(102.8, 104, 101.5, 103)); // bar1(idx2).high=101 < bar3(idx4).low=101.5

        assertEquals(1, SmcStructures.findFvgs(bars, 3, 1.0).size(), "位移够(2.8≥1.0×ATR)应检出");
        assertTrue(SmcStructures.findFvgs(bars, 3, 2.0).isEmpty(), "位移不足(2.8<2.0×ATR)应滤掉");
    }

    @Test
    void mitigationAndMidTouchLifecycle() {
        List<KlineBar> base = bars(
                b(100, 101, 99, 100),
                b(100, 105, 99.5, 104.8),
                b(104.8, 106, 102, 105));
        SmcStructures.Fvg f = SmcStructures.findFvgs(base, 3, 0.0).getFirst();  // 区间[101,102] 中位101.5

        List<KlineBar> withShallow = append(base, b(105, 105.5, 101.6, 105.2));   // low 101.6：未到中位
        assertFalse(SmcStructures.midTouched(f, withShallow));
        assertFalse(SmcStructures.mitigated(f, withShallow));

        List<KlineBar> withMidTouch = append(withShallow, b(105.2, 105.5, 101.4, 105)); // low 101.4≤101.5：触中位
        assertTrue(SmcStructures.midTouched(f, withMidTouch));
        assertFalse(SmcStructures.mitigated(f, withMidTouch), "触及中位但未穿下沿≠缓解");

        List<KlineBar> withFullCross = append(withMidTouch, b(105, 105.2, 100.9, 104)); // low 100.9≤101：完全穿越
        assertTrue(SmcStructures.mitigated(f, withFullCross));
    }

    // ---- 结构偏向 + 摆动区间 ----

    @Test
    void bullishStructureGivesLongBiasAndRange() {
        List<KlineBar> bars = swingPath(false);

        assertEquals(1, SmcStructures.structureBias(bars, 3, 1.0), "HH+HL应判多头结构");
        Optional<SmcStructures.SwingRange> range = SmcStructures.swingRange(bars, 3, 1.0);
        assertTrue(range.isPresent());
        assertEquals(0, range.get().high().compareTo(new BigDecimal("156")), "最近确认HIGH pivot");
        assertEquals(0, range.get().low().compareTo(new BigDecimal("140")), "最近确认LOW pivot");
        assertEquals(0, range.get().equilibrium().compareTo(new BigDecimal("148")), "EQ=50%位");
    }

    @Test
    void bearishStructureGivesShortBias() {
        List<KlineBar> bars = swingPath(true);   // 镜像路径：LH+LL

        assertEquals(-1, SmcStructures.structureBias(bars, 3, 1.0), "LH+LL应判空头结构");
    }

    @Test
    void monotonicMoveHasNoBias() {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i <= 10; i++) bars.add(b4(100 + i * 4, bars));

        assertEquals(0, SmcStructures.structureBias(bars, 3, 1.0), "单边无二组pivot应判无偏向");
    }

    // ---- helpers ----

    /**
     * 标准摆动路径(21根, h=c+4/l=c-4)：上100→140、回134/130、上136→152、回148/144、上150。
     * reversal=1.0×ATR(≈8)下确认pivot序列 L108,H144,L126,H156,L140 → HH+HL。mirror=true取300-c镜像。
     * 注意阈值必须大于影线(4)+步长(4)之和的一半以上，否则单边行情会逐根误确认pivot。
     */
    private static List<KlineBar> swingPath(boolean mirror) {
        double[] closes = {100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140,
                134, 130, 136, 140, 144, 148, 152, 148, 144, 150};
        List<KlineBar> bars = new ArrayList<>();
        for (double c : closes) bars.add(b4(mirror ? 300 - c : c, bars));
        return bars;
    }

    /** 接续前一根的收盘为开盘，h=c+4/l=c-4。 */
    private static KlineBar b4(double close, List<KlineBar> prev) {
        double open = prev.isEmpty() ? close : prev.getLast().close().doubleValue();
        long t = prev.size() * M5;
        return new KlineBar(t, t + M5 - 1,
                BigDecimal.valueOf(open), BigDecimal.valueOf(close + 4),
                BigDecimal.valueOf(close - 4), BigDecimal.valueOf(close), BigDecimal.ONE);
    }

    private static KlineBar b(double open, double high, double low, double close) {
        return new KlineBar(0, M5 - 1,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.ONE);
    }

    /** 依序编号时间戳，保证bars时间递增（FVG检测只看OHLC，时间戳仅须合法）。 */
    private static List<KlineBar> bars(KlineBar... items) {
        List<KlineBar> out = new ArrayList<>();
        for (KlineBar k : items) {
            long t = out.size() * M5;
            out.add(new KlineBar(t, t + M5 - 1, k.open(), k.high(), k.low(), k.close(), k.volume()));
        }
        return out;
    }

    private static List<KlineBar> append(List<KlineBar> base, KlineBar bar) {
        List<KlineBar> out = new ArrayList<>(base);
        long t = out.size() * M5;
        out.add(new KlineBar(t, t + M5 - 1, bar.open(), bar.high(), bar.low(), bar.close(), bar.volume()));
        return out;
    }
}
