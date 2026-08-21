package com.mawai.wiibquant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.strategy.backtest.task.BacktestEvent;
import com.mawai.wiibquant.strategy.backtest.task.BacktestOrchestrator;
import com.mawai.wiibquant.strategy.backtest.task.BacktestTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 可视化回测页 API：异步任务六端点（模式1 策略回测）+ 本地历史 K 线两端点（模式2 手动复盘）。
 * 鉴权口径与 /api/ai 其余端点一致（satoken 透传）；数据只读本地 kline_history，不触发外部下载。
 */
@Slf4j
@Tag(name = "可视化回测")
@RestController
@RequestMapping("/api/ai/backtest")
@RequiredArgsConstructor
public class AiBacktestController {

    /** 单任务交易窗上限：3 年。护住 LRU 内存预算（一年 5m ≈ 5MB/任务）。 */
    private static final long MAX_SPAN_MS = 1096L * 86_400_000L;
    private static final int HISTORY_ROWS_MAX = 20_000;

    private final BacktestTaskService taskService;
    private final KlineHistoryStore klineHistoryStore;

    public record RunReq(String strategyId, String symbol, Long fromMs, Long toMs,
                         BigDecimal initialBalance, Integer leverage) {
    }

    public record CoverageView(String symbol, long earliestMs, long latestMs) {
    }

    public record HistoryKlines(int total, List<List<Number>> rows) {
    }

    @Operation(summary = "提交回测（异步；同参数指纹直接复用既有任务）")
    @PostMapping("/run")
    public Result<Map<String, String>> run(@RequestBody RunReq req) {
        StpUtil.checkLogin();
        long userId = StpUtil.getLoginIdAsLong();
        try {
            String symbol = QuantConstants.normalizeSymbol(req.symbol());
            if (!QuantConstants.WATCH_SYMBOLS.contains(symbol)) {
                return Result.fail("仅支持 BTCUSDT/ETHUSDT");
            }
            if (req.fromMs() == null || req.toMs() == null || req.fromMs() >= req.toMs()) {
                return Result.fail("时间范围无效");
            }
            if (req.toMs() - req.fromMs() > MAX_SPAN_MS) {
                return Result.fail("时间范围过长，最多 3 年");
            }
            BigDecimal balance = req.initialBalance() == null ? new BigDecimal("100000") : req.initialBalance();
            if (balance.signum() <= 0 || balance.compareTo(new BigDecimal("1000000000")) > 0) {
                return Result.fail("初始资金无效");
            }
            int leverage = req.leverage() == null ? 5 : req.leverage();
            if (leverage < 1 || leverage > 100) {
                return Result.fail("杠杆需在 1~100");
            }
            String strategyId = req.strategyId();
            if (strategyId == null || !taskService.knownStrategy(strategyId)) {
                return Result.fail("未知策略: " + strategyId);
            }
            String taskId = taskService.submit(userId, strategyId, symbol,
                    req.fromMs(), req.toMs(), balance, leverage);
            return Result.ok(Map.of("taskId", taskId));
        } catch (BacktestTaskService.TaskRejectedException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "任务状态（含排队位次）")
    @GetMapping("/tasks/{id}/status")
    public Result<BacktestTaskService.StatusView> status(@PathVariable String id) {
        StpUtil.checkLogin();
        try {
            return Result.ok(taskService.status(id));
        } catch (BacktestTaskService.TaskRejectedException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "工作记录增量拉取（seq 游标）")
    @GetMapping("/tasks/{id}/events")
    public Result<BacktestTaskService.EventsPage> events(@PathVariable String id,
                                                         @RequestParam(defaultValue = "-1") long after,
                                                         @RequestParam(defaultValue = "500") int limit) {
        StpUtil.checkLogin();
        try {
            return Result.ok(taskService.events(id, after, limit));
        } catch (BacktestTaskService.TaskRejectedException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "任务 K 线分段（含预热段，紧凑数组）")
    @GetMapping("/tasks/{id}/klines")
    public Result<BacktestTaskService.KlinesPage> klines(@PathVariable String id,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         @RequestParam(defaultValue = "20000") int limit) {
        StpUtil.checkLogin();
        try {
            return Result.ok(taskService.klines(id, offset, limit));
        } catch (BacktestTaskService.TaskRejectedException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "回测结果（DONE 后：summary+trades+降采样权益）")
    @GetMapping("/tasks/{id}/result")
    public Result<BacktestTaskService.ResultPayload> result(@PathVariable String id) {
        StpUtil.checkLogin();
        try {
            return Result.ok(taskService.result(id));
        } catch (BacktestTaskService.TaskRejectedException e) {
            return Result.fail(e.getMessage());
        }
    }

    // ==================== 模式2 手动复盘：本地历史 K 线 ====================

    @Operation(summary = "本地 5m K 线覆盖范围（复盘选随机起点用）")
    @GetMapping("/history/coverage")
    public Result<List<CoverageView>> coverage() {
        StpUtil.checkLogin();
        List<CoverageView> out = new ArrayList<>();
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            Long earliest = klineHistoryStore.earliestOpenTime(symbol, KlineHistoryStore.DEFAULT_INTERVAL);
            Long latest = klineHistoryStore.latestOpenTime(symbol, KlineHistoryStore.DEFAULT_INTERVAL);
            if (earliest != null && latest != null) {
                out.add(new CoverageView(symbol, earliest, latest));
            }
        }
        return Result.ok(out);
    }

    @Operation(summary = "本地 5m K 线区间拉取（复盘数据源，[fromMs,toMs) 上限 2 万根）")
    @GetMapping("/history/klines")
    public Result<HistoryKlines> historyKlines(@RequestParam String symbol,
                                               @RequestParam long fromMs,
                                               @RequestParam long toMs) {
        StpUtil.checkLogin();
        String normalized = QuantConstants.normalizeSymbol(symbol);
        if (!QuantConstants.WATCH_SYMBOLS.contains(normalized)) {
            return Result.fail("仅支持 BTCUSDT/ETHUSDT");
        }
        if (fromMs >= toMs || toMs - fromMs > HISTORY_ROWS_MAX * KlineHistoryStore.DEFAULT_BAR_MILLIS) {
            return Result.fail("时间范围无效（上限 2 万根 5m）");
        }
        List<KlineBar> bars = klineHistoryStore.load(normalized, KlineHistoryStore.DEFAULT_INTERVAL, fromMs, toMs);
        List<List<Number>> rows = new ArrayList<>(bars.size());
        for (KlineBar b : bars) {
            rows.add(List.of(b.openTime(), b.open(), b.high(), b.low(), b.close(), b.volume()));
        }
        return Result.ok(new HistoryKlines(rows.size(), rows));
    }
}
