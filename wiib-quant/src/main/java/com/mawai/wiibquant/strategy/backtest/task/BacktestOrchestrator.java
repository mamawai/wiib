package com.mawai.wiibquant.strategy.backtest.task;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.strategy.core.TradingStrategySpi;
import com.mawai.wiibquant.strategy.core.WindowedMarketView;
import com.mawai.wiibquant.strategy.fibo.FiboParams;
import com.mawai.wiibquant.strategy.fibo.FiboRetracementStrategy;
import com.mawai.wiibquant.strategy.liq.LiqFadeParams;
import com.mawai.wiibquant.strategy.liq.LiqFadeStrategy;
import com.mawai.wiibquant.strategy.sqzmom.SqueezeMomentumStrategy;
import com.mawai.wiibquant.strategy.sqzmom.SqzMomParams;
import com.mawai.wiibquant.strategy.turtle.TurtleParams;
import com.mawai.wiibquant.strategy.turtle.TurtleStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 回测编排：策略注册表（工厂 + warmup 公式，数值照抄各 DbRun 现值）+ 数据装载 + fail-fast 校验。
 * 从 StrategyBacktestController.runFibo 与各 DbRun 抽出的公共骨架；只读本地 kline_history，不触发回填。
 */
@Service
@RequiredArgsConstructor
public class BacktestOrchestrator {

    /**
     * 可回测的策略 id，与 {@link #warmupMs} / {@link #prepare} 两个 switch 同一批。
     * 展示用的名字与说明全在前端词表里（见 wiib-web 的 lib/strategyCatalog），这一层只认 id。
     */
    private static final Set<String> STRATEGY_IDS = Set.of("FIBO", "TURTLE", "SQZMOM", "LIQFADE");

    /** 引擎开跑所需的全部输入。bars 含预热段，任务服务直接持有同一份供 K 线接口切片。 */
    public record Prepared(TradingStrategySpi strategy, List<KlineBar> bars, int warmupBars) {
    }

    /** 明确给用户看的失败（参数/数据问题），与代码 bug 区分。 */
    public static class BacktestSetupException extends RuntimeException {
        public BacktestSetupException(String message) {
            super(message);
        }
    }

    private final KlineHistoryStore klineHistoryStore;
    private final DbLiqSideData dbLiqSideData;

    public boolean knownStrategy(String strategyId) {
        return STRATEGY_IDS.contains(strategyId);
    }

    /** 预热毫秒（照抄各 DbRun 现值；FIBO 含 SMA200 趋势闸预热，比老 /fibo 端点的取值更足）。 */
    public long warmupMs(String strategyId) {
        return switch (strategyId) {
            case "FIBO" -> {
                FiboParams p = FiboParams.defaults();
                long legWarmup = (long) (p.swingLookbackBars() + p.atrPeriod() + 16) * p.swingTfMillis();
                long trendWarmup = 201L * 4 * p.swingTfMillis();
                yield Math.max(legWarmup, trendWarmup);
            }
            case "TURTLE" -> {
                TurtleParams p = TurtleParams.defaults();
                yield (long) (Math.max(p.entryLookback(), p.atrPeriod()) + 4) * p.decisionTfMillis();
            }
            case "SQZMOM" -> {
                SqzMomParams p = SqzMomParams.defaults();
                yield (2L * p.length() + p.squeezeMinBars() + 40) * p.decisionTfMillis();
            }
            case "LIQFADE" -> 6 * 3_600_000L;   // 策略仅需4根bar，6h 富余
            default -> throw new BacktestSetupException("未知策略: " + strategyId);
        };
    }

    /**
     * 装载数据并构建策略实例。[tradingStartMs, tradingEndMs) 为交易窗（含/不含口径同引擎），
     * 预热段自动前推。数据缺口 / LIQ 覆盖不足 → BacktestSetupException fail-fast。
     */
    public Prepared prepare(String strategyId, String symbol, long tradingStartMs, long tradingEndMs) {
        List<KlineBar> bars = klineHistoryStore.load(
                symbol, KlineHistoryStore.DEFAULT_INTERVAL, tradingStartMs - warmupMs(strategyId), tradingEndMs);
        if (bars.isEmpty()) {
            throw new BacktestSetupException("本地 kline_history 没有该区间的 5m K线: " + symbol);
        }
        WindowedMarketView.firstBaseGapDescription(bars).ifPresent(gap -> {
            throw new BacktestSetupException("本地 5m K线不连续，需先回填: " + symbol + " " + gap);
        });
        int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < tradingStartMs).count();

        TradingStrategySpi strategy = switch (strategyId) {
            case "FIBO" -> new FiboRetracementStrategy(FiboParams.defaults(), List.of(symbol));
            case "TURTLE" -> new TurtleStrategy(TurtleParams.defaults(), List.of(symbol));
            case "SQZMOM" -> new SqueezeMomentumStrategy(SqzMomParams.defaults(), List.of(symbol));
            case "LIQFADE" -> {
                DbLiqSideData.Loaded side = dbLiqSideData.load(symbol);
                double coverage = side.takerCoverage(tradingStartMs, tradingEndMs);
                if (coverage < 0.6) {
                    throw new BacktestSetupException(String.format(
                            "该窗口 liq side data 覆盖率仅 %.0f%%（需≥60%%），跑 LiqDataBackfillRun 回填后再试", coverage * 100));
                }
                yield new LiqFadeStrategy(LiqFadeParams.defaults(), List.of(symbol), side);
            }
            default -> throw new BacktestSetupException("未知策略: " + strategyId);
        };
        return new Prepared(strategy, bars, warmupBars);
    }
}
