package com.mawai.wiibquant.strategy.backtest;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.TradeFilterDefaults;
import com.mawai.wiibquant.strategy.core.PositionSizer;
import com.mawai.wiibquant.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.strategy.core.StrategySignal;
import com.mawai.wiibquant.strategy.core.TradingStrategySpi;
import com.mawai.wiibquant.strategy.core.WindowedMarketView;
import com.mawai.wiibquant.strategy.core.TradingOperations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 通用策略 K 线回放引擎。
 *
 * <p>信号在 bar i 闭合产生，最早只能在 bar i+1 开盘成交；SL/TP 交给
 * BacktestTradingTools 撮合，同根同时触发时沿用 SL 优先的保守口径。</p>
 */
public final class StrategyKlineBacktestEngine {

    private static final int WINDOW_MAX_BARS = 50_000;

    private final TradingStrategySpi strategy;
    private final String symbol;
    private final List<KlineBar> base5m;
    private final BigDecimal initialBalance;
    private final int leverage;
    private final int warmupBars;
    private final Long tradingStartMs;
    private final Long tradingEndMs;
    private BigDecimal fillEpsilon = BigDecimal.ZERO;  // maker fill 摩擦，由 -Dbacktest.fillEpsilonBp 注入，默认0=原"触及即成交"
    private boolean takerEntry = false;  // true=进场改 taker(触及即成交、无 fill 摩擦、走 taker 费)；-Dbacktest.takerEntry 注入
    private double fixedMarginUsdt = 0.0; // >0=固定每仓保证金(名义=保证金×命令行杠杆)，绕过风险定量与15%上限；-Dbacktest.fixedMarginUsdt 注入
    private double marginPctOfEquity = 0.0; // >0=每仓保证金=权益×此比例(复利/全仓口径，名义=保证金×杠杆)；-Dbacktest.marginPctOfEquity 注入

    // ---- 事件发射（可视化回测页用；listener=null 时全部 no-op，原行为逐字节一致） ----
    private BacktestListener listener;
    private String lastLegFp;      // 腿指纹去重（复刻 StrategyRuntime.lastRecordedLeg 口径：断档后清空，同腿重现再记）
    private int emittedTrades;     // EXIT 事件 diff 游标：closedTrades 只增不删，按下标续发

    public StrategyKlineBacktestEngine(TradingStrategySpi strategy, String symbol, List<KlineBar> base5m,
                                       BigDecimal initialBalance, int leverage, int warmupBars,
                                       Long tradingStartMs, Long tradingEndMs) {
        this.strategy = strategy;
        this.symbol = symbol;
        this.base5m = base5m == null ? List.of() : List.copyOf(base5m);
        this.initialBalance = initialBalance == null ? new BigDecimal("100000") : initialBalance;
        this.leverage = leverage;
        this.warmupBars = Math.max(0, warmupBars);
        this.tradingStartMs = tradingStartMs;
        this.tradingEndMs = tradingEndMs;
    }

    public BacktestResult run() {
        return run(null);
    }

    public BacktestResult run(BacktestListener listener) {
        this.listener = listener;
        BacktestTradingTools tools = new BacktestTradingTools(initialBalance, symbol);
        // fill 摩擦压力测试：默认 0(原行为)；>0 时 maker 限价进场需价格穿过挂单价 ε 才成交。
        this.fillEpsilon = BigDecimal.valueOf(
                Double.parseDouble(System.getProperty("backtest.fillEpsilonBp", "0")) / 10_000.0);
        this.takerEntry = Boolean.parseBoolean(System.getProperty("backtest.takerEntry", "false"));
        this.fixedMarginUsdt = Double.parseDouble(System.getProperty("backtest.fixedMarginUsdt", "0"));
        this.marginPctOfEquity = Double.parseDouble(System.getProperty("backtest.marginPctOfEquity", "0"));
        BacktestResult result = new BacktestResult(initialBalance);
        WindowedMarketView view = new WindowedMarketView(WINDOW_MAX_BARS);
        StrategySignal pending = null;        // 市价单：下一根开盘成交
        StrategySignal pendingLimit = null;   // 限价单：挂单跨根等价格触及(maker)
        StrategySignal pendingStop = null;    // 触价单：挂单跨根等价格突破(taker)，生命周期与 pendingLimit 同款 reaffirm

        long lastBarOpenTime = 0;   // 循环可能因 tradingEndMs 提前 break，收尾强平事件挂在最后处理的 bar 上
        for (int i = 0; i < base5m.size(); i++) {
            KlineBar bar = base5m.get(i);
            long nowMs = bar.closeTime();
            if (tradingEndMs != null && nowMs >= tradingEndMs) break;
            lastBarOpenTime = bar.openTime();

            tools.setCurrentTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.of("UTC")));
            tools.setCurrentBarIndex(i);

            if (pending != null) {
                openFill(tools, pending, bar.open(), "MARKET", i, view);
                pending = null;
            }
            // 限价挂单：本根 high/low 触及挂单价即 maker 成交（盘中触发，置于 tickBar 之前）
            if (pendingLimit != null && tools.getOpenPositions(symbol).isEmpty()
                    && limitTouched(pendingLimit, bar)) {
                // takerEntry：在挂单价主动市价吃进(走 taker 费)；否则 maker 被动成交(走 maker 费)。成交价同为挂单价。
                openFill(tools, pendingLimit, pendingLimit.entryRefPrice(),
                        takerEntry ? "MARKET" : "LIMIT", i, view);
                pendingLimit = null;
            }
            // 触价挂单：本根突破触发价即 taker 成交；开盘已跳空穿越触发价时按开盘劣价成交（拿不到比市场更好的价）
            if (pendingStop != null && tools.getOpenPositions(symbol).isEmpty()
                    && stopTouched(pendingStop, bar)) {
                openFill(tools, pendingStop, stopFillPrice(pendingStop, bar), "MARKET", i, view);
                pendingStop = null;
            }

            tools.tickBar(bar.open(), bar.high(), bar.low(), bar.close(), i);
            view.append(bar);

            // 持仓管理钩子：当根 SL/TP 撮合完再回调（时间出场按收盘价成交），之后才评估新信号
            for (FuturesPositionDTO pos : tools.getOpenPositions(symbol)) {
                strategy.onPositionBarClosed(symbol, pos, view, tools);
            }

            Optional<StrategySignal> signal = strategy.onBarClosed(symbol, view);
            // 撤单判定基准：成交消费后仍挂着的单；评估完若没了/换了腿即为撤单（同腿 reaffirm 不算）
            StrategySignal heldLimit = pendingLimit;
            StrategySignal heldStop = pendingStop;
            boolean canTrade = inTradingWindow(nowMs, i)
                    && pending == null
                    && tools.getOpenPositions(symbol).isEmpty();
            if (signal.isPresent()) {
                StrategySignal s = signal.get();
                emitSignal(s, bar.openTime());
                if ("LIMIT".equals(s.orderType())) {
                    pendingLimit = canTrade ? s : null;   // reaffirm：腿有效则刷新挂单，否则(有仓/不可交易)清挂单
                    pendingStop = null;                   // 单挂单不变量：新信号换类型时清掉另一侧 stale 挂单
                } else if ("STOP".equals(s.orderType())) {
                    pendingStop = canTrade ? s : null;    // reaffirm 语义同 LIMIT
                    pendingLimit = null;
                } else if (canTrade) {
                    pending = s;
                }
            } else {
                lastLegFp = null;                         // 腿断档：同腿此后重现按新腿再记（同 StrategyRuntime.remove）
                pendingLimit = null;                      // 无信号=腿失效，撤挂单
                pendingStop = null;
            }
            emitCancel(heldLimit, pendingLimit, bar.openTime());
            emitCancel(heldStop, pendingStop, bar.openTime());
            emitExits(tools, bar.openTime());

            result.recordEquity(tools.getTotalEquity());
            if (listener != null) listener.onBar(i, base5m.size());
        }

        forceCloseAll(tools);
        emitExits(tools, lastBarOpenTime);
        result.recordEquity(tools.getTotalEquity());
        copyClosedTrades(tools, result);
        return result;
    }

    // ==================== 事件发射（listener=null 时全部 no-op） ====================

    /** 腿指纹：同 StrategyRuntime 落库去重口径。 */
    private static String legFp(StrategySignal s) {
        return s.orderType() + ":" + s.side() + ":" + s.entryRefPrice().stripTrailingZeros().toPlainString();
    }

    private void emitSignal(StrategySignal s, long barTimeMs) {
        if (listener == null) return;
        String fp = legFp(s);
        if (fp.equals(lastLegFp)) return;   // 同腿每 5m reaffirm，只记首条
        lastLegFp = fp;
        listener.onEvent("SIGNAL", barTimeMs, dataMap(
                "orderType", s.orderType(), "side", s.side(), "entryRef", s.entryRefPrice(),
                "sl", s.stopLossPrice(), "tp", s.takeProfitPrice(), "reason", s.reason()));
    }

    private void emitCancel(StrategySignal held, StrategySignal now, long barTimeMs) {
        if (listener == null || held == null) return;
        if (now != null && legFp(now).equals(legFp(held))) return;
        listener.onEvent("ORDER_CANCELLED", barTimeMs, dataMap(
                "orderType", held.orderType(), "side", held.side(), "entryRef", held.entryRefPrice()));
    }

    /** 出场事件全部由 ClosedTrade 增量还原，撮合代码零触碰。 */
    private void emitExits(BacktestTradingTools tools, long barTimeMs) {
        if (listener == null) return;
        List<BacktestTradingTools.ClosedTrade> all = tools.getClosedTrades();
        for (; emittedTrades < all.size(); emittedTrades++) {
            BacktestTradingTools.ClosedTrade ct = all.get(emittedTrades);
            listener.onEvent("EXIT", barTimeMs, dataMap(
                    "reason", ct.exitReason(), "side", ct.side(), "entry", ct.entryPrice(),
                    "exit", ct.exitPrice(), "pnl", ct.pnl(), "rMultiple", ct.rMultiple(),
                    "holdBars", ct.closeBarIndex() - ct.openBarIndex()));
        }
    }

    private void emitFill(StrategySignal s, BigDecimal price, BigDecimal qty, int lev, long barTimeMs) {
        if (listener == null) return;
        listener.onEvent("ENTRY_FILL", barTimeMs, dataMap(
                "orderType", s.orderType(), "side", s.side(), "price", price, "qty", qty,
                "leverage", lev, "sl", s.stopLossPrice(), "tp", s.takeProfitPrice()));
    }

    private void emitReject(StrategySignal s, BigDecimal price, String reason, long barTimeMs) {
        if (listener == null || s == null) return;
        listener.onEvent("ENTRY_REJECTED", barTimeMs, dataMap(
                "reason", reason, "orderType", s.orderType(), "side", s.side(), "price", price));
    }

    /** 变长键值对 → 有序 map；null 值跳过（tp/rMultiple 可缺，Map.of 不收 null）。 */
    private static Map<String, Object> dataMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private boolean inTradingWindow(long nowMs, int barIndex) {
        return barIndex >= warmupBars
                && (tradingStartMs == null || nowMs >= tradingStartMs)
                && (tradingEndMs == null || nowMs < tradingEndMs);
    }

    /** 限价单是否被本根触及：做多看 low≤挂单价，做空看 high≥挂单价。 */
    private boolean limitTouched(StrategySignal s, KlineBar bar) {
        BigDecimal limit = s.entryRefPrice();
        // taker 进场=触及即成交(无 fill 摩擦)；maker 进场需价格穿过挂单价 ε 才成交。
        BigDecimal eps = takerEntry ? BigDecimal.ZERO : fillEpsilon;
        return s.isLong()
                ? bar.low().compareTo(limit.multiply(BigDecimal.ONE.subtract(eps))) <= 0
                : bar.high().compareTo(limit.multiply(BigDecimal.ONE.add(eps))) >= 0;
    }

    /** 触价单是否被本根触发：做多看 high≥触发价，做空看 low≤触发价。 */
    private boolean stopTouched(StrategySignal s, KlineBar bar) {
        BigDecimal trigger = s.entryRefPrice();
        return s.isLong()
                ? bar.high().compareTo(trigger) >= 0
                : bar.low().compareTo(trigger) <= 0;
    }

    /** 触价单成交价：正常=触发价；开盘已跳空穿越时按开盘劣价（多头取大、空头取小）。 */
    private static BigDecimal stopFillPrice(StrategySignal s, KlineBar bar) {
        BigDecimal trigger = s.entryRefPrice();
        return s.isLong() ? bar.open().max(trigger) : bar.open().min(trigger);
    }

    /** 成交建仓：市价用下一根开盘价(taker)、限价用挂单价(maker)，由 orderType 区分。 */
    private void openFill(BacktestTradingTools tools, StrategySignal signal, BigDecimal fillPrice,
                          String orderType, int barIndex, WindowedMarketView view) {
        long barTimeMs = base5m.get(barIndex).openTime();
        if (signal == null || fillPrice == null || fillPrice.signum() <= 0) {
            emitReject(signal, fillPrice, "PRICE_INVALID", barTimeMs);
            return;
        }
        // 回测填单模型的保守门：市价单按下一根开盘成交，滑点比实盘秒级延迟严苛。
        // 实盘无法"拒绝"已成交的市价/挂单，此差异有界于秒级滑点，方向上回测更严格。
        if (!directionalPricesStillValid(signal, fillPrice)) {
            emitReject(signal, fillPrice, "DIRECTIONAL", barTimeMs);
            return;
        }
        if (gappedBeyondStop(signal, fillPrice)) {
            emitReject(signal, fillPrice, "GAPPED_BEYOND_STOP", barTimeMs);
            return;
        }
        if (!rrStillAcceptable(signal, fillPrice)) {
            emitReject(signal, fillPrice, "RR_TOO_LOW", barTimeMs);
            return;
        }

        StrategyRiskPolicy policy = strategy.riskPolicy() == null
                ? StrategyRiskPolicy.defaults()
                : strategy.riskPolicy();
        // 自定义仓位(复利%保证金 / 固定保证金)绕过风险定量与 15% 上限、尊重命令行杠杆；否则按风控钳。
        boolean customSizing = marginPctOfEquity > 0 || fixedMarginUsdt > 0;
        int effectiveLeverage = customSizing
                ? Math.max(1, leverage)
                : PositionSizer.effectiveLeverage(leverage, policy);
        BigDecimal quantity;
        if (marginPctOfEquity > 0) {
            // 复利全仓口径：每仓保证金=权益×比例，名义=保证金×杠杆。
            quantity = tools.getTotalEquity()
                    .multiply(BigDecimal.valueOf(marginPctOfEquity))
                    .multiply(BigDecimal.valueOf(effectiveLeverage))
                    .divide(fillPrice, 8, RoundingMode.DOWN);
        } else if (fixedMarginUsdt > 0) {
            quantity = BigDecimal.valueOf(fixedMarginUsdt)
                    .multiply(BigDecimal.valueOf(effectiveLeverage))
                    .divide(fillPrice, 8, RoundingMode.DOWN);
        } else {
            // 风险定量与实盘执行共用 PositionSizer，公式漂移即破坏"回测判决对实盘有效"
            quantity = PositionSizer.riskSizedQty(tools.getTotalEquity(), fillPrice,
                    signal.stopLossPrice(), policy, effectiveLeverage, TradeFilterDefaults.futures(symbol));
        }
        // 自定义仓位口径同样按交易过滤器落地（步长/最小额），回测约束与实盘一致
        quantity = PositionSizer.applyFilter(quantity, fillPrice, TradeFilterDefaults.futures(symbol));
        if (quantity == null || quantity.signum() <= 0) {
            emitReject(signal, fillPrice, "QTY_ZERO", barTimeMs);
            return;
        }

        tools.setCurrentPrice(fillPrice);
        TradingOperations.OpenResult opened = tools.openPositionWithResult(signal.side(), quantity, effectiveLeverage,
                orderType, fillPrice, signal.stopLossPrice(), signal.takeProfitPrice(), signal.strategyId());
        if (opened.success()) {
            tools.markOpenBarIndex(barIndex);
            emitFill(signal, fillPrice, quantity, effectiveLeverage, barTimeMs);
            strategy.onPositionOpened(symbol, signal, opened.positionId(), fillPrice, view, tools);
        } else {
            // 三道门都过了还开不了仓 = 余额不足（qty/价格无效已在前面拦截）
            emitReject(signal, fillPrice, "BALANCE_REJECTED", barTimeMs);
        }
    }

    private boolean directionalPricesStillValid(StrategySignal signal, BigDecimal entry) {
        if (signal.stopLossPrice() == null) return false;
        boolean stopValid = signal.isLong()
                ? signal.stopLossPrice().compareTo(entry) < 0
                : signal.stopLossPrice().compareTo(entry) > 0;
        if (!stopValid || signal.takeProfitPrice() == null) return stopValid;
        return signal.isLong()
                ? signal.takeProfitPrice().compareTo(entry) > 0
                : signal.takeProfitPrice().compareTo(entry) < 0;
    }

    private boolean gappedBeyondStop(StrategySignal signal, BigDecimal entry) {
        return signal.isLong()
                ? entry.compareTo(signal.stopLossPrice()) <= 0
                : entry.compareTo(signal.stopLossPrice()) >= 0;
    }

    private boolean rrStillAcceptable(StrategySignal signal, BigDecimal entry) {
        // 无固定 TP 的趋势策略由持仓回调动态退出，无法在入场时定义静态 RR；仍由固定 SL 完成风险定量。
        if (signal.takeProfitPrice() == null) return true;
        BigDecimal risk = entry.subtract(signal.stopLossPrice()).abs();
        if (risk.signum() <= 0) return false;
        BigDecimal reward = signal.takeProfitPrice().subtract(entry).abs();
        BigDecimal rr = reward.divide(risk, 4, RoundingMode.HALF_UP);
        BigDecimal minRr = strategy.riskPolicy() == null
                ? StrategyRiskPolicy.defaults().minRiskReward()
                : strategy.riskPolicy().minRiskReward();
        return rr.compareTo(minRr) >= 0;
    }

    private void forceCloseAll(BacktestTradingTools tools) {
        for (FuturesPositionDTO pos : new ArrayList<>(tools.getOpenPositions(symbol))) {
            tools.closePositionWithReason(pos.getId(), pos.getQuantity(), "FORCE_CLOSE");
        }
    }

    private void copyClosedTrades(BacktestTradingTools tools, BacktestResult result) {
        for (BacktestTradingTools.ClosedTrade ct : tools.getClosedTrades()) {
            result.addTrade(new BacktestResult.Trade(
                    ct.openBarIndex(), ct.closeBarIndex(), ct.openTime(), ct.closeTime(),
                    ct.side(), ct.strategy(),
                    ct.entryPrice(), ct.exitPrice(), ct.quantity(), ct.leverage(),
                    ct.pnl(), ct.fee(), ct.rMultiple(), ct.exitReason(),
                    ct.maxFavorableR(), ct.maxAdverseR()
            ));
        }
    }
}
