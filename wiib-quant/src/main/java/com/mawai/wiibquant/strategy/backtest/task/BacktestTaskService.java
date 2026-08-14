package com.mawai.wiibquant.strategy.backtest.task;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.strategy.backtest.BacktestListener;
import com.mawai.wiibquant.strategy.backtest.BacktestResult;
import com.mawai.wiibquant.strategy.backtest.StrategyKlineBacktestEngine;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可视化回测任务服务（多用户版）。
 *
 * <p>并发模型：2 worker 固定池；QUEUED→RUNNING→DONE/FAILED。
 * 参数指纹去重——相同「策略|币|窗口|资金|杠杆」直接复用既有任务（回测确定性，事件/K线/结果只读共享），
 * FAILED 不复用（可能是数据环境问题，允许重试）。排队上限 {@value #MAX_QUEUED}，
 * 单用户同时最多 1 个活动任务（命中去重复用不占额）。
 * 任务留存 LRU 上限 {@value #MAX_TASKS}（内存大头是 bars，一年 5m ≈ 5MB/任务），只逐终态；
 * 活动任务至多 2 RUNNING + 4 QUEUED = 6 &lt; 8，永远有位可逐。进程重启即失，重跑即可，不落库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestTaskService {

    static final int MAX_TASKS = 8;
    static final int MAX_QUEUED = 4;
    private static final int EVENTS_PAGE_MAX = 2000;
    private static final int KLINES_PAGE_MAX = 20_000;

    /** 给用户看的拒绝/失败（排队满、任务不存在等），控制器转 Result.fail。 */
    public static class TaskRejectedException extends RuntimeException {
        public TaskRejectedException(String message) {
            super(message);
        }
    }

    enum State { QUEUED, RUNNING, DONE, FAILED }

    static final class Task {
        final String id = UUID.randomUUID().toString();
        final long submitSeq;
        final String fingerprint;
        final long userId;
        final String strategyId;
        final String symbol;
        final long fromMs;
        final long toMs;
        final BigDecimal initialBalance;
        final int leverage;
        volatile State state = State.QUEUED;
        final AtomicInteger barsDone = new AtomicInteger();
        volatile int totalBars;
        volatile int warmupBars;
        volatile List<KlineBar> bars;          // 引擎用的同一份，K线接口直接切片
        volatile BacktestResult result;
        volatile String error;
        volatile long lastTouchMs = System.currentTimeMillis();   // LRU 逐出依据
        final List<BacktestEvent> events = new ArrayList<>();     // 只增不删；读写都 synchronized(events)

        Task(long submitSeq, String fingerprint, long userId, String strategyId, String symbol,
             long fromMs, long toMs, BigDecimal initialBalance, int leverage) {
            this.submitSeq = submitSeq;
            this.fingerprint = fingerprint;
            this.userId = userId;
            this.strategyId = strategyId;
            this.symbol = symbol;
            this.fromMs = fromMs;
            this.toMs = toMs;
            this.initialBalance = initialBalance;
            this.leverage = leverage;
        }

        void touch() {
            lastTouchMs = System.currentTimeMillis();
        }
    }

    // ==================== 响应 DTO（字段名与前端 types 严格对齐） ====================

    public record StatusView(String taskId, String state, String strategyId, String symbol,
                             int barsDone, int totalBars, int warmupBars, String error, int queuePos) {
    }

    public record EventsPage(List<BacktestEvent> events, long nextAfter, String state) {
    }

    public record KlinesPage(int total, int offset, List<List<Number>> rows) {
    }

    public record TradeView(int openBarIndex, int closeBarIndex, long openTime, long closeTime, String side,
                            BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal quantity, int leverage,
                            BigDecimal pnl, BigDecimal fee, BigDecimal rMultiple, String exitReason,
                            BigDecimal maxFavorableR, BigDecimal maxAdverseR) {
    }

    public record ResultPayload(String taskId, String strategyId, String symbol, int warmupBars,
                                Map<String, Object> summary, List<TradeView> trades, List<List<Number>> equity) {
    }

    private final BacktestOrchestrator orchestrator;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong submitSeq = new AtomicLong();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "backtest-worker");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    // ==================== 提交 ====================

    public synchronized String submit(long userId, String strategyId, String symbol,
                                      long fromMs, long toMs, BigDecimal initialBalance, int leverage) {
        String fp = strategyId + "|" + symbol + "|" + fromMs + "|" + toMs + "|"
                + initialBalance.stripTrailingZeros().toPlainString() + "|" + leverage;
        // 指纹命中（排队/运行/已完成）直接复用；大多数用户点默认参数第二人起秒回
        for (Task t : tasks.values()) {
            if (fp.equals(t.fingerprint) && t.state != State.FAILED) {
                t.touch();
                return t.id;
            }
        }
        // 先查用户自己的额度再查全局队列：文案对提交者更可操作
        if (tasks.values().stream().anyMatch(t -> t.userId == userId
                && (t.state == State.QUEUED || t.state == State.RUNNING))) {
            throw new TaskRejectedException("你已有一个回测在排队/进行中，等它完成再提交");
        }
        if (tasks.values().stream().filter(t -> t.state == State.QUEUED).count() >= MAX_QUEUED) {
            throw new TaskRejectedException("回测排队已满，稍后再试");
        }
        evictIfNeeded();
        Task t = new Task(submitSeq.incrementAndGet(), fp, userId, strategyId, symbol,
                fromMs, toMs, initialBalance, leverage);
        tasks.put(t.id, t);
        pool.submit(() -> runTask(t));
        return t.id;
    }

    /** 只逐终态；活动任务上限 6 < MAX_TASKS，必有位可逐。 */
    private void evictIfNeeded() {
        while (tasks.size() >= MAX_TASKS) {
            Task oldest = tasks.values().stream()
                    .filter(t -> t.state == State.DONE || t.state == State.FAILED)
                    .min(Comparator.comparingLong(t -> t.lastTouchMs))
                    .orElse(null);
            if (oldest == null) return;
            tasks.remove(oldest.id);
        }
    }

    // ==================== 执行 ====================

    private void runTask(Task t) {
        t.state = State.RUNNING;
        try {
            BacktestOrchestrator.Prepared p = orchestrator.prepare(t.strategyId, t.symbol, t.fromMs, t.toMs);
            t.bars = p.bars();
            t.totalBars = p.bars().size();
            t.warmupBars = p.warmupBars();
            addEvent(t, "TASK_START", 0, Map.of(
                    "strategyId", t.strategyId, "symbol", t.symbol, "fromMs", t.fromMs, "toMs", t.toMs,
                    "bars", t.totalBars, "warmupBars", t.warmupBars, "leverage", t.leverage));
            BacktestResult r = new StrategyKlineBacktestEngine(
                    p.strategy(), t.symbol, p.bars(), t.initialBalance, t.leverage,
                    p.warmupBars(), t.fromMs, t.toMs)
                    .run(new BacktestListener() {
                        @Override
                        public void onBar(int index, int total) {
                            t.barsDone.set(index + 1);
                        }

                        @Override
                        public void onEvent(String type, long barTimeMs, Map<String, Object> data) {
                            addEvent(t, type, barTimeMs, data);
                        }
                    });
            t.result = r;
            // 挂在最后处理的 bar 上：回放推到末尾才显示"完成"
            int lastIdx = Math.max(0, t.barsDone.get() - 1);
            long doneTime = t.bars.isEmpty() ? 0 : t.bars.get(Math.min(lastIdx, t.bars.size() - 1)).openTime();
            addEvent(t, "TASK_DONE", doneTime, Map.of("totalTrades", r.totalTrades()));
            t.state = State.DONE;
            log.info("[BacktestTask] 完成 {} {} {} bars={} trades={}",
                    t.strategyId, t.symbol, t.id, t.totalBars, r.totalTrades());
        } catch (Exception e) {
            boolean setup = e instanceof BacktestOrchestrator.BacktestSetupException;
            String msg = setup ? e.getMessage() : "回测执行异常: " + rootMessage(e);
            if (setup) {
                log.warn("[BacktestTask] 失败 {} {}: {}", t.strategyId, t.symbol, msg);
            } else {
                log.error("[BacktestTask] 异常 {} {}", t.strategyId, t.symbol, e);
            }
            t.error = msg;
            addEvent(t, "TASK_FAILED", 0, Map.of("message", msg));
            t.state = State.FAILED;
        }
    }

    /** 兜底文案取最深层 cause，别把包装异常的空泛信息给用户。 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }

    private void addEvent(Task t, String type, long barTimeMs, Map<String, Object> data) {
        synchronized (t.events) {
            t.events.add(new BacktestEvent(t.events.size(), barTimeMs, type, data));
        }
    }

    // ==================== 查询 ====================

    private Task get(String taskId) {
        Task t = tasks.get(taskId);
        if (t == null) {
            throw new TaskRejectedException("回测任务不存在（可能因服务重启丢失），请重新提交");
        }
        t.touch();
        return t;
    }

    public StatusView status(String taskId) {
        Task t = get(taskId);
        int queuePos = 0;
        if (t.state == State.QUEUED) {
            queuePos = (int) tasks.values().stream()
                    .filter(o -> o.state == State.QUEUED && o.submitSeq < t.submitSeq)
                    .count() + 1;
        }
        return new StatusView(t.id, t.state.name(), t.strategyId, t.symbol,
                t.barsDone.get(), t.totalBars, t.warmupBars, t.error, queuePos);
    }

    public EventsPage events(String taskId, long after, int limit) {
        Task t = get(taskId);
        int lim = Math.max(1, Math.min(limit, EVENTS_PAGE_MAX));
        synchronized (t.events) {
            int from = (int) Math.max(0, after + 1);
            if (from >= t.events.size()) {
                return new EventsPage(List.of(), after, t.state.name());
            }
            int to = Math.min(from + lim, t.events.size());
            return new EventsPage(List.copyOf(t.events.subList(from, to)), to - 1L, t.state.name());
        }
    }

    public KlinesPage klines(String taskId, int offset, int limit) {
        Task t = get(taskId);
        List<KlineBar> bars = t.bars;
        if (bars == null) {
            return new KlinesPage(0, offset, List.of());
        }
        int lim = Math.max(1, Math.min(limit, KLINES_PAGE_MAX));
        int from = Math.max(0, Math.min(offset, bars.size()));
        int to = Math.min(from + lim, bars.size());
        List<List<Number>> rows = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            KlineBar b = bars.get(i);
            rows.add(List.of(b.openTime(), b.open(), b.high(), b.low(), b.close(), b.volume()));
        }
        return new KlinesPage(bars.size(), from, rows);
    }

    public ResultPayload result(String taskId) {
        Task t = get(taskId);
        if (t.state != State.DONE) {
            throw new TaskRejectedException("回测尚未完成");
        }
        BacktestResult r = t.result;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalTrades", r.totalTrades());
        summary.put("wins", r.wins());
        summary.put("losses", r.losses());
        summary.put("winRate", r.winRate());
        summary.put("profitFactor", r.profitFactor());
        summary.put("netProfit", r.netProfit());
        summary.put("totalFees", r.totalFees());
        summary.put("sharpeRatio", r.sharpeRatio());
        summary.put("maxDrawdownPct", r.maxDrawdownPct());
        summary.put("avgHoldBars", r.avgHoldBars());
        summary.put("avgR", r.avgR());
        summary.put("returnPct", r.returnPct());
        summary.put("finalEquity", r.finalEquity());

        List<TradeView> trades = r.getTrades().stream().map(x -> new TradeView(
                x.barIndex(), x.closeBarIndex(),
                x.openTime().toInstant(ZoneOffset.UTC).toEpochMilli(),
                x.closeTime().toInstant(ZoneOffset.UTC).toEpochMilli(),
                x.side(), x.entryPrice(), x.exitPrice(), x.quantity(), x.leverage(),
                x.pnl(), x.fee(), x.rMultiple(), x.exitReason(),
                x.maxFavorableR(), x.maxAdverseR())).toList();

        return new ResultPayload(t.id, t.strategyId, t.symbol, t.warmupBars, summary, trades, equityPoints(t, r));
    }

    /**
     * 权益曲线 [closeTimeMs, equity]：equityCurve 首点=初始资金，其后每处理一根 bar 一点，
     * 收尾强平再补一点（与最后一根同时刻）。按 stride=ceil(n/2000) 降采样，恒含最后一点。
     */
    private static List<List<Number>> equityPoints(Task t, BacktestResult r) {
        List<BigDecimal> curve = r.getEquityCurve();
        List<KlineBar> bars = t.bars;
        int processed = Math.max(1, t.barsDone.get());
        List<List<Number>> pts = new ArrayList<>();
        int n = curve.size() - 1;                    // 去掉首点（初始资金无 bar 时刻）
        int stride = Math.max(1, (int) Math.ceil(n / 2000.0));
        for (int k = 1; k <= n; k++) {
            if (k % stride != 0 && k != n) continue;
            int barIdx = Math.min(k - 1, processed - 1);
            pts.add(List.of(bars.get(barIdx).closeTime(), curve.get(k)));
        }
        return pts;
    }

    public List<BacktestOrchestrator.StrategyMeta> strategies() {
        return orchestrator.strategies();
    }
}
