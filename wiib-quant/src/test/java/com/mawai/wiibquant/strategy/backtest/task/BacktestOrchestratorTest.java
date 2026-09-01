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
 * 编排注册表：三策略全解析、warmup 公式与 DbRun 现值一致（材料化数值防漂移）、缺口 fail-fast。
 */
class BacktestOrchestratorTest {

    private static final long M5 = 5 * 60_000L;
    private static final long H4 = 4 * 3_600_000L;
    private static final long M15 = 15 * 60_000L;

    @Test
    void warmupFormulasMatchDbRunValues() {
        BacktestOrchestrator orch = new BacktestOrchestrator(null);
        // FIBO: max((144+14+16)×15m, 201×4×15m) = 804×15m —— 含 SMA200 趋势闸预热
        assertThat(orch.warmupMs("FIBO")).isEqualTo(804 * M15);
        // TURTLE: (max(90,15)+4)×4h
        assertThat(orch.warmupMs("TURTLE")).isEqualTo(94 * H4);
        // SQZMOM: (2×20+6+40)×4h
        assertThat(orch.warmupMs("SQZMOM")).isEqualTo(86 * H4);
        assertThatThrownBy(() -> orch.warmupMs("NOPE"))
                .isInstanceOf(BacktestOrchestrator.BacktestSetupException.class);
    }

    @Test
    void strategiesListsThreeAndPrepareResolvesEach() {
        FakeStore store = new FakeStore();
        store.bars = flatBars(50);
        BacktestOrchestrator orch = new BacktestOrchestrator(store);

        assertThat(orch.knownStrategy("NOPE")).isFalse();
        assertThat(orch.knownStrategy("LIQFADE")).as("已下架的策略不再认").isFalse();
        for (String id : List.of("FIBO", "TURTLE", "SQZMOM")) {
            assertThat(orch.knownStrategy(id)).as(id).isTrue();
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
        BacktestOrchestrator orch = new BacktestOrchestrator(store);

        assertThatThrownBy(() -> orch.prepare("FIBO", "BTCUSDT", 10 * M5, 50 * M5))
                .isInstanceOf(BacktestOrchestrator.BacktestSetupException.class)
                .hasMessageContaining("quant.backtest.klineGap");
    }

    // ==================== 桩 ====================

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
