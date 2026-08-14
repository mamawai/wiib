package com.mawai.wiibquant.strategy.smc;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.strategy.backtest.BacktestResult;
import com.mawai.wiibquant.strategy.backtest.StrategyKlineBacktestEngine;
import com.mawai.wiibquant.strategy.core.WindowedMarketView;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SMC策略3(FVG回补) DB 回测 runner。
 * 显式 -Dtest=SmcStrategyDbRun 时直连 Postgres 读 kline_history 5m，绕开 Spring/Redis。
 *
 * 跑法：mvn -pl wiib-quant -am test -Dtest=SmcStrategyDbRun#runFvgBacktest -DskipTests=false \
 *       -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false \
 *       -Dsmc.bt.symbols=BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT
 */
class SmcStrategyDbRun {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/wiib";
    private static final String DEFAULT_USER = "mawai";
    private static final String DEFAULT_PASSWORD = com.mawai.wiibquant.LocalEnv.dbPassword();
    private static final int[] CHECK_YEARS = {2021, 2022, 2023, 2024, 2025, 2026};

    private record SegResult(boolean hasGap, BacktestResult r) {}

    private record ParamCase(String label, SmcParams params) {}

    /**
     * 稳健性体检：defaults(4h偏向+1h FVG+位移1.5+Discount门) 逐年总览+多空拆分，
     * 再加训练(21-23)/验证(24-26)两个大窗看整体口径。1%风险、5x、10万本金，与fibo体检同口径。
     */
    @Test
    void runFvgBacktest() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("smc.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("smc.bt.balance", "100000"));
        List<ParamCase> cases = List.of(new ParamCase("defaults", SmcParams.defaults()));

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("smc.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("smc.bt.dbUser", DEFAULT_USER),
                    System.getProperty("smc.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过 SMC DB 回测: " + e.getMessage());
            return;
        }

        try (con) {
            System.out.printf("%n#### SMC-FVG 稳健性体检 balance=%s leverage=%d symbols=%s ####%n",
                    balance.toPlainString(), leverage, symbols);
            for (String symbol : symbols) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) {
                    System.out.printf("%-10s (无数据)%n", symbol);
                    continue;
                }
                for (ParamCase cfg : cases) {
                    System.out.printf("%n==== %s  config=%s ====%n", symbol, cfg.label());
                    System.out.printf("%-8s %7s %7s %8s %9s %8s %8s %5s%n",
                            "year", "trades", "win%", "pf", "ret%", "avgR", "maxDD%", "gap");
                    int posYears = 0, validYears = 0;
                    double retSum = 0;
                    for (int year : CHECK_YEARS) {
                        SegResult sr = runWindow(con, symbol, cfg.params(),
                                yearStartUtc(year), yearStartUtc(year + 1), balance, leverage, latest);
                        if (sr == null) continue;
                        BacktestResult r = sr.r();
                        System.out.printf(Locale.ROOT, "%-8d %7d %7.1f %8.3f %9.2f %8.3f %8.1f %5s%n",
                                year, r.totalTrades(), r.winRate() * 100, r.profitFactor(),
                                r.returnPct() * 100, r.avgR(), r.maxDrawdownPct() * 100, sr.hasGap() ? "Y" : "-");
                        printDirSplit(r);
                        validYears++;
                        retSum += r.returnPct() * 100;
                        if (r.returnPct() > 0) posYears++;
                    }
                    System.out.printf(Locale.ROOT, "=> %s posYears=%d/%d retSum=%.2f%%%n",
                            cfg.label(), posYears, validYears, retSum);

                    // 训练/验证两个大窗：整体判据看这里（totalR=avgR×n，先立后跑：VAL四币PF>1才算有基础edge）
                    for (String[] win : new String[][]{{"TRAIN 21-23", "2021", "2024"}, {"VAL   24-26", "2024", "2027"}}) {
                        SegResult sr = runWindow(con, symbol, cfg.params(),
                                yearStartUtc(Integer.parseInt(win[1])), yearStartUtc(Integer.parseInt(win[2])),
                                balance, leverage, latest);
                        if (sr == null) continue;
                        BacktestResult r = sr.r();
                        System.out.printf(Locale.ROOT,
                                "%s trades=%d win=%.1f%% pf=%.3f avgR=%.3f totalR=%.1f ret=%.2f%% maxDD=%.1f%% %s%n",
                                win[0], r.totalTrades(), r.winRate() * 100, r.profitFactor(), r.avgR(),
                                r.avgR() * r.totalTrades(), r.returnPct() * 100, r.maxDrawdownPct() * 100,
                                breakdown(r.getTrades()));
                    }
                }
            }
        }
    }

    /** 多空拆分：验证 edge 是否两侧都有还是被单边趋势抬着走。 */
    private static void printDirSplit(BacktestResult r) {
        List<BacktestResult.Trade> longs = r.getTrades().stream().filter(t -> "LONG".equals(t.side())).toList();
        List<BacktestResult.Trade> shorts = r.getTrades().stream().filter(t -> "SHORT".equals(t.side())).toList();
        System.out.printf(Locale.ROOT, "           L/S: LONG n=%-4d avgR=%+.3f | SHORT n=%-4d avgR=%+.3f%n",
                longs.size(), avgR(longs), shorts.size(), avgR(shorts));
    }

    private static double avgR(List<BacktestResult.Trade> rows) {
        return rows.stream().map(BacktestResult.Trade::rMultiple)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
    }

    private static String breakdown(List<BacktestResult.Trade> trades) {
        Map<String, Integer> byExit = new LinkedHashMap<>();
        for (BacktestResult.Trade t : trades) byExit.merge(t.exitReason(), 1, Integer::sum);
        return "exit=" + byExit;
    }

    /** 跑单个时间窗 [start, end)，窗前 warmup 只喂数据不交易。 */
    private static SegResult runWindow(Connection con, String symbol, SmcParams params,
                                       long tradingStartMs, long tradingEndExclusive,
                                       BigDecimal balance, int leverage, long latestClose) throws Exception {
        long segEnd = Math.min(tradingEndExclusive, latestClose + 1);
        if (tradingStartMs >= segEnd) return null;
        // 预热=偏向回看全长(360根4h≈60天)+ATR余量：保证窗口首日4h结构判定就绪
        long warmupMs = (long) (params.biasLookbackBars() + params.atrPeriod() + 2) * params.biasTfMillis();
        List<KlineBar> bars = loadBars(con, symbol, tradingStartMs - warmupMs, segEnd);
        if (bars.isEmpty()) return null;
        boolean hasGap = WindowedMarketView.firstBaseGapDescription(bars).isPresent();
        int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < tradingStartMs).count();
        FvgFillStrategy strategy = new FvgFillStrategy(params, List.of(symbol));
        BacktestResult r = new StrategyKlineBacktestEngine(
                strategy, symbol, bars, balance, leverage, warmupBars, tradingStartMs, segEnd).run();
        return new SegResult(hasGap, r);
    }

    private static Long latestCloseTime(Connection con, String symbol) throws Exception {
        String sql = """
                SELECT close_time FROM kline_history
                WHERE symbol=? AND interval_code='5m'
                ORDER BY open_time DESC LIMIT 1
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static List<KlineBar> loadBars(Connection con, String symbol, long fromMs, long toMs) throws Exception {
        String sql = """
                SELECT open_time, close_time, open, high, low, close, volume
                FROM kline_history
                WHERE symbol=? AND interval_code='5m' AND open_time>=? AND open_time<?
                ORDER BY open_time
                """;
        List<KlineBar> bars = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            ps.setFetchSize(5000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bars.add(new KlineBar(rs.getLong(1), rs.getLong(2),
                            rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                            rs.getBigDecimal(6), rs.getBigDecimal(7)));
                }
            }
        }
        return bars;
    }

    private static long yearStartUtc(int year) {
        return LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static List<String> symbols() {
        String raw = System.getProperty("smc.bt.symbols", "BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT");
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String symbol = token.trim().toUpperCase(Locale.ROOT);
            if (!symbol.isBlank()) out.add(symbol);
        }
        return out.isEmpty() ? List.of("BTCUSDT") : List.copyOf(out);
    }
}
