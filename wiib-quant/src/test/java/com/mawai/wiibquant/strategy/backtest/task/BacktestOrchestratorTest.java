package com.mawai.wiibquant.strategy.backtest.task;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编排注册表：四策略全解析、warmup 公式与 DbRun 现值一致（材料化数值防漂移）、
 * 缺口 fail-fast、LIQ 覆盖率门槛。
 */
class BacktestOrchestratorTest {

    private static final long M5 = 5 * 60_000L;
    private static final long H4 = 4 * 3_600_000L;
    private static final long M15 = 15 * 60_000L;

    @Test
    void warmupFormulasMatchDbRunValues() {
        BacktestOrchestrator orch = new BacktestOrchestrator(null, null);
        // FIBO: max((144+14+16)×15m, 201×4×15m) = 804×15m —— 含 SMA200 趋势闸预热
        assertThat(orch.warmupMs("FIBO")).isEqualTo(804 * M15);
        // TURTLE: (max(90,15)+4)×4h
        assertThat(orch.warmupMs("TURTLE")).isEqualTo(94 * H4);
        // SQZMOM: (2×20+6+40)×4h
        assertThat(orch.warmupMs("SQZMOM")).isEqualTo(86 * H4);
        // LIQFADE: 6h 常量
        assertThat(orch.warmupMs("LIQFADE")).isEqualTo(6 * 3_600_000L);
        assertThatThrownBy(() -> orch.warmupMs("NOPE"))
                .isInstanceOf(BacktestOrchestrator.BacktestSetupException.class);
    }

    @Test
    void strategiesListsFourAndPrepareResolvesEach() {
        FakeStore store = new FakeStore();
        store.bars = flatBars(50);
        BacktestOrchestrator orch = new BacktestOrchestrator(store, denseSide());

        assertThat(orch.strategies()).extracting(BacktestOrchestrator.StrategyMeta::id)
                .containsExactly("FIBO", "TURTLE", "SQZMOM", "LIQFADE");
        for (String id : List.of("FIBO", "TURTLE", "SQZMOM", "LIQFADE")) {
            BacktestOrchestrator.Prepared p = orch.prepare(id, "BTCUSDT", 10 * M5, 50 * M5);
            assertThat(p.strategy().id()).isEqualTo(id);
            assertThat(p.bars()).hasSize(50);
            assertThat(p.warmupBars()).isEqualTo(10);   // closeTime < 10×5m 的根数
        }
    }

    @Test
    void gapFailsFast() {
        FakeStore store = new FakeStore();
        List<KlineBar> bars = new ArrayList<>(flatBars(50));
        bars.remove(25);
        store.bars = bars;
        BacktestOrchestrator orch = new BacktestOrchestrator(store, null);

        assertThatThrownBy(() -> orch.prepare("FIBO", "BTCUSDT", 10 * M5, 50 * M5))
                .isInstanceOf(BacktestOrchestrator.BacktestSetupException.class)
                .hasMessageContaining("不连续");
    }

    @Test
    void liqCoverageBelowThresholdFails() {
        FakeStore store = new FakeStore();
        store.bars = flatBars(50);
        // 空 side data：窗口内 0 覆盖
        DbLiqSideData empty = new DbLiqSideData(null) {
            @Override
            public Loaded load(String symbol) {
                return new Loaded(new long[0], new double[0], new long[0], new double[0]);
            }
        };
        BacktestOrchestrator orch = new BacktestOrchestrator(store, empty);

        assertThatThrownBy(() -> orch.prepare("LIQFADE", "BTCUSDT", 10 * M5, 50 * M5))
                .isInstanceOf(BacktestOrchestrator.BacktestSetupException.class)
                .hasMessageContaining("覆盖率");
    }

    // ==================== 桩 ====================

    /** 交易窗 [10×5m, 50×5m) 全覆盖的 taker 桶。 */
    private static DbLiqSideData denseSide() {
        int n = 60;
        long[] t = new long[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * M5;
            v[i] = 1.0;
        }
        DbLiqSideData.Loaded loaded = new DbLiqSideData.Loaded(t, v, t.clone(), v.clone());
        return new DbLiqSideData(null) {
            @Override
            public Loaded load(String symbol) {
                return loaded;
            }
        };
    }

    private static List<KlineBar> flatBars(int n) {
        List<KlineBar> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long t = i * M5;
            BigDecimal p = BigDecimal.valueOf(100);
            out.add(new KlineBar(t, t + M5 - 1, p, p.add(BigDecimal.ONE), p.subtract(BigDecimal.ONE), p, BigDecimal.ONE));
        }
        return out;
    }

    private static final class FakeStore extends KlineHistoryStore {
        List<KlineBar> bars = List.of();

        private FakeStore() {
            super(null);
        }

        @Override
        public List<KlineBar> load(String symbol, String intervalCode, long fromMs, long toMs) {
            return bars;
        }
    }
}
