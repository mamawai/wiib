package com.mawai.wiibquant.strategy.backtest;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.strategy.core.StrategyMarketView;
import com.mawai.wiibquant.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.strategy.core.StrategySignal;
import com.mawai.wiibquant.strategy.core.TradingStrategySpi;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引擎事件发射：合成 bars + 桩策略，断言事件序列与既有撮合行为一致
 * （SIGNAL→FILL→EXIT 顺序、腿指纹去重、REJECTED 原因、EXIT 与 trades 一致、无 listener 零变化）。
 */
class BacktestEngineEventTest {

    private static final long M5 = 5 * 60_000L;
    private static final String SYM = "BTCUSDT";

    private record Ev(String type, long barTimeMs, Map<String, Object> data) {
    }

    private static final class Capture implements BacktestListener {
        final List<Ev> events = new ArrayList<>();
        int barCalls;
        int lastTotal;

        @Override
        public void onBar(int index, int total) {
            barCalls++;
            lastTotal = total;
        }

        @Override
        public void onEvent(String type, long barTimeMs, Map<String, Object> data) {
            events.add(new Ev(type, barTimeMs, data));
        }

        List<String> types() {
            return events.stream().map(Ev::type).toList();
        }

        List<Ev> of(String type) {
            return events.stream().filter(e -> e.type().equals(type)).toList();
        }
    }

    @Test
    void marketFlowEmitsSignalFillExitInOrder() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));
        bars.set(10, bar(10, 102, 103, 101, 102));
        bars.set(15, bar(15, 102, 131, 101, 128));   // 冲高触发 TP=130

        Capture cap = new Capture();
        BacktestResult r = new StrategyKlineBacktestEngine(
                onceLongAt(9, 90, 130), SYM, bars, new BigDecimal("100000"), 5, 0, null, null)
                .run(cap);

        assertThat(cap.types()).containsExactly("SIGNAL", "ENTRY_FILL", "EXIT");
        Ev signal = cap.events.get(0);
        assertThat(signal.barTimeMs()).isEqualTo(9 * M5);
        assertThat(signal.data()).containsEntry("orderType", "MARKET").containsEntry("side", "LONG");
        Ev fill = cap.events.get(1);
        assertThat(fill.barTimeMs()).isEqualTo(10 * M5);
        assertThat(((BigDecimal) fill.data().get("price"))).isEqualByComparingTo("102");
        Ev exit = cap.events.get(2);
        assertThat(exit.barTimeMs()).isEqualTo(15 * M5);
        assertThat(exit.data()).containsEntry("reason", "TP").containsEntry("holdBars", 5);
        assertThat(cap.of("EXIT")).hasSize(r.totalTrades());
        assertThat(cap.barCalls).isEqualTo(20);
        assertThat(cap.lastTotal).isEqualTo(20);
    }

    @Test
    void reaffirmedLegEmitsSingleSignalAndForceCloseExit() {
        List<KlineBar> bars = new ArrayList<>(flatBars(30, 100));

        Capture cap = new Capture();
        BacktestResult r = new StrategyKlineBacktestEngine(
                alwaysLongAfter(9, 90, 200), SYM, bars, new BigDecimal("100000"), 5, 0, null, null)
                .run(cap);

        // 同腿（flat 收盘价恒定 → 指纹恒定）每根 reaffirm，只记首条
        assertThat(cap.of("SIGNAL")).hasSize(1);
        assertThat(r.totalTrades()).isEqualTo(1);
        assertThat(cap.of("EXIT")).hasSize(1);
        assertThat(cap.of("EXIT").getFirst().data()).containsEntry("reason", "FORCE_CLOSE");
    }

    @Test
    void staleLimitEmitsOrderCancelled() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));   // low 恒 99，挂单 95 永不触及

        Capture cap = new Capture();
        new StrategyKlineBacktestEngine(
                limitLongBetween(9, 12, 95, 90, 110), SYM, bars, new BigDecimal("100000"), 5, 0, null, null)
                .run(cap);

        assertThat(cap.types()).containsExactly("SIGNAL", "ORDER_CANCELLED");
        Ev cancelled = cap.events.get(1);
        assertThat(cancelled.barTimeMs()).isEqualTo(12 * M5);   // bar12 停止 reaffirm 即撤
        assertThat(((BigDecimal) cancelled.data().get("entryRef"))).isEqualByComparingTo("95");
    }

    @Test
    void gapBeyondStopEmitsRejectWithReason() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));
        bars.set(10, bar(10, 85, 86, 84, 85));   // 下根开盘 85 跳空跌破 SL=90

        Capture cap = new Capture();
        BacktestResult r = new StrategyKlineBacktestEngine(
                onceLongAt(9, 90, 110), SYM, bars, new BigDecimal("100000"), 5, 0, null, null)
                .run(cap);

        assertThat(r.totalTrades()).isZero();
        assertThat(cap.types()).containsExactly("SIGNAL", "ENTRY_REJECTED");
        // 跳空越过止损时 SL 已在成交价错侧，方向门先拦下（GAPPED 门与其条件互补，实际由 DIRECTIONAL 表达）
        assertThat(cap.of("ENTRY_REJECTED").getFirst().data()).containsEntry("reason", "DIRECTIONAL");
    }

    @Test
    void nullListenerKeepsLegacyBehaviour() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));
        bars.set(10, bar(10, 102, 103, 101, 102));
        bars.set(15, bar(15, 102, 131, 101, 128));

        BacktestResult withNull = new StrategyKlineBacktestEngine(
                onceLongAt(9, 90, 130), SYM, bars, new BigDecimal("100000"), 5, 0, null, null).run();
        BacktestResult withCap = new StrategyKlineBacktestEngine(
                onceLongAt(9, 90, 130), SYM, bars, new BigDecimal("100000"), 5, 0, null, null).run(new Capture());

        assertThat(withNull.totalTrades()).isEqualTo(withCap.totalTrades());
        assertThat(withNull.netProfit()).isEqualByComparingTo(withCap.netProfit());
    }

    // ==================== 桩策略/工具（与 StrategyKlineBacktestEngineTest 同款口径） ====================

    private static TradingStrategySpi onceLongAt(int signalBarIndex, double sl, double tp) {
        return new TradingStrategySpi() {
            private boolean fired;

            @Override public String id() { return "STUB"; }

            @Override public List<String> symbols() { return List.of(SYM); }

            @Override public StrategyRiskPolicy riskPolicy() { return StrategyRiskPolicy.defaults(); }

            @Override
            public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
                List<KlineBar> base = view.closedBars(StrategyMarketView.BASE_INTERVAL_MILLIS, 100_000);
                if (!fired && base.size() == signalBarIndex + 1) {
                    fired = true;
                    return Optional.of(longSignal(base.getLast(), sl, tp, "MARKET"));
                }
                return Optional.empty();
            }
        };
    }

    private static TradingStrategySpi alwaysLongAfter(int firstSignalBarIndex, double sl, double tp) {
        return new TradingStrategySpi() {
            @Override public String id() { return "STUB"; }

            @Override public List<String> symbols() { return List.of(SYM); }

            @Override public StrategyRiskPolicy riskPolicy() { return StrategyRiskPolicy.defaults(); }

            @Override
            public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
                List<KlineBar> base = view.closedBars(StrategyMarketView.BASE_INTERVAL_MILLIS, 100_000);
                if (base.size() >= firstSignalBarIndex + 1) {
                    return Optional.of(longSignal(base.getLast(), sl, tp, "MARKET"));
                }
                return Optional.empty();
            }
        };
    }

    /** 仅在 bar 索引 [fromBar, toBarExclusive) 输出 LIMIT 信号，之外 empty（模拟挂单失效→撤单）。 */
    private static TradingStrategySpi limitLongBetween(int fromBar, int toBarExclusive,
                                                       double limit, double sl, double tp) {
        return new TradingStrategySpi() {
            @Override public String id() { return "STUB"; }

            @Override public List<String> symbols() { return List.of(SYM); }

            @Override public StrategyRiskPolicy riskPolicy() { return StrategyRiskPolicy.defaults(); }

            @Override
            public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
                int idx = view.closedBars(StrategyMarketView.BASE_INTERVAL_MILLIS, 100_000).size() - 1;
                if (idx >= fromBar && idx < toBarExclusive) {
                    return Optional.of(new StrategySignal("STUB", SYM, "LONG", true,
                            bd(limit), bd(sl), bd(tp), 1.0, "stub", idx * M5 + M5 - 1, "LIMIT"));
                }
                return Optional.empty();
            }
        };
    }

    private static StrategySignal longSignal(KlineBar bar, double sl, double tp, String orderType) {
        return new StrategySignal("STUB", SYM, "LONG", true,
                bar.close(), bd(sl), bd(tp), 1.0, "stub", bar.closeTime(), orderType);
    }

    private static List<KlineBar> flatBars(int n, double price) {
        List<KlineBar> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(bar(i, price, price + 1, price - 1, price));
        }
        return out;
    }

    private static KlineBar bar(int i, double open, double high, double low, double close) {
        long t = i * M5;
        return new KlineBar(t, t + M5 - 1, bd(open), bd(high), bd(low), bd(close), BigDecimal.ONE);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
