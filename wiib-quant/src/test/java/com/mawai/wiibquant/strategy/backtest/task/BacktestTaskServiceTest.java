package com.mawai.wiibquant.strategy.backtest.task;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.strategy.core.StrategyMarketView;
import com.mawai.wiibquant.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.strategy.core.StrategySignal;
import com.mawai.wiibquant.strategy.core.TradingStrategySpi;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务服务多用户语义：指纹去重、队列上限、单用户限额、LRU 只逐终态、事件游标增量。
 * 用桩 orchestrator（可阻塞/可失败），不碰 DB。
 */
class BacktestTaskServiceTest {

    private static final long M5 = 5 * 60_000L;
    private static final BigDecimal BAL = new BigDecimal("100000");
    private static final long TO_MS = 100 * M5;   // 桩 bars 在 [0, 20×5m)，toMs 给足不提前 break

    /** 桩编排：gate 非空则阻塞（模拟长任务占住 worker）；fail=true 抛设置异常。 */
    private static final class FakeOrchestrator extends BacktestOrchestrator {
        volatile CountDownLatch gate;
        volatile boolean fail;

        FakeOrchestrator() {
            super(null, null);
        }

        @Override
        public Prepared prepare(String strategyId, String symbol, long tradingStartMs, long tradingEndMs) {
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    if (!g.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("gate 超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            if (fail) {
                throw new BacktestSetupException("桩失败");
            }
            return new Prepared(neverTrades(), flatBars(20), 0);
        }
    }

    @Test
    void runsToDoneAndServesEventsKlinesResult() throws Exception {
        FakeOrchestrator orch = new FakeOrchestrator();
        BacktestTaskService svc = new BacktestTaskService(orch);

        String id = svc.submit(1L, "FIBO", "BTCUSDT", 0, TO_MS, BAL, 5);
        awaitState(svc, id, "DONE");

        BacktestTaskService.StatusView st = svc.status(id);
        assertThat(st.totalBars()).isEqualTo(20);
        assertThat(st.barsDone()).isEqualTo(20);
        assertThat(st.queuePos()).isZero();

        BacktestTaskService.EventsPage page = svc.events(id, -1, 100);
        assertThat(page.events().getFirst().type()).isEqualTo("TASK_START");
        assertThat(page.events().getLast().type()).isEqualTo("TASK_DONE");
        // 游标续拉：从 nextAfter 继续为空、不重不漏
        assertThat(svc.events(id, page.nextAfter(), 100).events()).isEmpty();

        BacktestTaskService.KlinesPage klines = svc.klines(id, 0, 20_000);
        assertThat(klines.total()).isEqualTo(20);
        assertThat(klines.rows()).hasSize(20);
        assertThat(klines.rows().getFirst()).hasSize(6);

        BacktestTaskService.ResultPayload result = svc.result(id);
        assertThat(result.taskId()).isEqualTo(id);
        assertThat(result.summary()).containsKeys("totalTrades", "netProfit", "finalEquity");
        assertThat(result.equity()).isNotEmpty();
    }

    @Test
    void sameFingerprintReusesTaskAndFailedDoesNot() throws Exception {
        FakeOrchestrator orch = new FakeOrchestrator();
        BacktestTaskService svc = new BacktestTaskService(orch);

        String id = svc.submit(1L, "FIBO", "BTCUSDT", 0, TO_MS, BAL, 5);
        awaitState(svc, id, "DONE");
        // DONE 复用：另一个用户同参数直接拿同一个任务
        assertThat(svc.submit(2L, "FIBO", "BTCUSDT", 0, TO_MS, BAL, 5)).isEqualTo(id);

        orch.fail = true;
        String failed = svc.submit(3L, "TURTLE", "BTCUSDT", 0, TO_MS, BAL, 5);
        awaitState(svc, failed, "FAILED");
        assertThat(svc.status(failed).error()).contains("桩失败");
        orch.fail = false;
        // FAILED 不复用：同参数重提是新任务
        assertThat(svc.submit(3L, "TURTLE", "BTCUSDT", 0, TO_MS, BAL, 5)).isNotEqualTo(failed);
    }

    @Test
    void queueCapAndPerUserLimit() throws Exception {
        FakeOrchestrator orch = new FakeOrchestrator();
        orch.gate = new CountDownLatch(1);
        BacktestTaskService svc = new BacktestTaskService(orch);

        // 2 个占住 worker（等它们真进 RUNNING，否则短暂 QUEUED 会提前把队列计满），再排 4 个（QUEUED 满）
        List<String> ids = new ArrayList<>();
        for (int u = 0; u < 2; u++) {
            ids.add(svc.submit(u, "FIBO", "BTCUSDT", u * M5, TO_MS, BAL, 5));
        }
        awaitState(svc, ids.get(0), "RUNNING");
        awaitState(svc, ids.get(1), "RUNNING");
        for (int u = 2; u < 6; u++) {
            ids.add(svc.submit(u, "FIBO", "BTCUSDT", u * M5, TO_MS, BAL, 5));
        }
        assertThatThrownBy(() -> svc.submit(99L, "FIBO", "BTCUSDT", 999 * M5, TO_MS, BAL, 5))
                .isInstanceOf(BacktestTaskService.TaskRejectedException.class)
                .hasMessageContaining("排队已满");
        // 排队位次：最后提交的排第 4
        assertThat(svc.status(ids.getLast()).state()).isEqualTo("QUEUED");
        assertThat(svc.status(ids.getLast()).queuePos()).isEqualTo(4);
        // 单用户限额：用户 0 已有活动任务，换参数也拒
        assertThatThrownBy(() -> svc.submit(0L, "TURTLE", "ETHUSDT", 0, TO_MS, BAL, 5))
                .isInstanceOf(BacktestTaskService.TaskRejectedException.class)
                .hasMessageContaining("已有一个回测");
        // 指纹命中不受限额影响：用户 0 撞用户 1 的参数 → 复用
        assertThat(svc.submit(0L, "FIBO", "BTCUSDT", M5, TO_MS, BAL, 5)).isEqualTo(ids.get(1));

        orch.gate.countDown();
        for (String id : ids) {
            awaitState(svc, id, "DONE");
        }
    }

    @Test
    void lruEvictsOnlyTerminalTasks() throws Exception {
        FakeOrchestrator orch = new FakeOrchestrator();
        BacktestTaskService svc = new BacktestTaskService(orch);

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < BacktestTaskService.MAX_TASKS + 1; i++) {
            String id = svc.submit(1L, "FIBO", "BTCUSDT", i * M5, TO_MS, BAL, 5);
            awaitState(svc, id, "DONE");
            ids.add(id);
        }
        // 第 9 个提交时逐出最老的第 1 个；最新的还在
        assertThatThrownBy(() -> svc.status(ids.getFirst()))
                .isInstanceOf(BacktestTaskService.TaskRejectedException.class);
        assertThat(svc.status(ids.getLast()).state()).isEqualTo("DONE");
    }

    // ==================== 工具 ====================

    private static void awaitState(BacktestTaskService svc, String id, String state) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;   // 宽 timeout 堵偶发红
        for (;;) {
            BacktestTaskService.StatusView st = svc.status(id);
            if (state.equals(st.state())) return;
            if ("FAILED".equals(st.state()) && !"FAILED".equals(state)) {
                throw new AssertionError("任务意外失败: " + st.error());
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等待超时，当前 " + st.state());
            }
            Thread.sleep(20);
        }
    }

    private static TradingStrategySpi neverTrades() {
        return new TradingStrategySpi() {
            @Override public String id() { return "STUB"; }

            @Override public List<String> symbols() { return List.of("BTCUSDT"); }

            @Override public StrategyRiskPolicy riskPolicy() { return StrategyRiskPolicy.defaults(); }

            @Override
            public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
                return Optional.empty();
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
}
