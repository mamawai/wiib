package com.mawai.wiibquant.agent.strategy.smc;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.StrategyMarketView;
import com.mawai.wiibquant.agent.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.SwingDetector;
import com.mawai.wiibquant.agent.strategy.core.TradingOperations;
import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SMC策略3：FVG回补（HTF偏向 + 溢价/折价过滤，限价回补范式）。
 *
 * <p>4h结构HH+HL(或LH+LL)定方向；1h上找顺方向、带位移、未缓解且中位从未被触及的最新FVG，
 * Discount(做多)/Premium(做空)区内限价挂FVG中位等首次回补(maker)，不追确认。
 * 止损=FVG外沿±ATR缓冲(完全穿越=缺口叙事失效)；止盈=1h摆动区间对面(前高/前低=对面流动性)。
 * 一FVG只交易一次；中位被抢先触及/完全缓解/滑出窗口/挂单超时 → 作废。</p>
 *
 * <p>性能：闭合4h/1h集合只在各自桶边界变化，故偏向按4h桶、挂单计划按1h桶缓存；
 * 5m级只做超时与穿价守门。语义与逐根全量重算完全一致，只省掉无效重复聚合。</p>
 */
public final class FvgFillStrategy implements TradingStrategySpi {

    private static final String ID = "SMC_FVG";
    private static final int CONSUMED_CAP = 128;   // 已消费FVG键上限，超出淘汰最老（远超窗口内FVG数量）

    private final SmcParams params;
    private final List<String> symbols;
    private final StrategyRiskPolicy riskPolicy = StrategyRiskPolicy.defaults();
    private final Map<String, SymbolState> states = new ConcurrentHashMap<>();

    public FvgFillStrategy(SmcParams params, List<String> symbols) {
        this.params = params == null ? SmcParams.defaults() : params;
        this.symbols = List.copyOf(symbols);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<String> symbols() {
        return symbols;
    }

    @Override
    public StrategyRiskPolicy riskPolicy() {
        return riskPolicy;
    }

    @Override
    public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
        if (!symbols.contains(symbol)) return Optional.empty();
        if (view.hasBaseGap()) return Optional.empty();
        SymbolState state = states.computeIfAbsent(symbol, ignored -> new SymbolState());
        long now = view.nowMs();

        // 4h偏向：闭合4h集合只在桶边界变化，按桶缓存
        long biasBucket = (now + 1) / params.biasTfMillis();
        if (biasBucket != state.biasBucket) {
            state.biasBucket = biasBucket;
            List<KlineBar> biasBars = view.closedBars(params.biasTfMillis(), params.biasLookbackBars());
            state.bias = biasBars.size() <= params.atrPeriod() + 2 ? 0
                    : SmcStructures.structureBias(biasBars, params.atrPeriod(), params.reversalAtrMult());
        }

        // 1h挂单计划：按桶缓存；桶内消费(成交/超时/穿价)后 plan 置空，下个桶再选新候选
        long structBucket = (now + 1) / params.structTfMillis();
        if (structBucket != state.structBucket) {
            state.structBucket = structBucket;
            state.plan = state.bias == 0 ? null : buildPlan(state, view);
        }
        Plan plan = state.plan;
        if (plan == null) return Optional.empty();

        // 挂单超时：候选装上后 orderTimeoutBars 根5m未成交 → 作废本FVG（久等的回补已脱离信号语境）
        if (now - state.armedAtMs > (long) params.orderTimeoutBars() * StrategyMarketView.BASE_INTERVAL_MILLIS) {
            consume(state, plan.fvgKey());
            return Optional.empty();
        }

        // 首次回补守门（5m粒度兜底）：收盘已越过中位=首触机会被市场用掉，作废不追。
        // 挂单在场时首触即成交轮不到这里；兜的是计划被阻塞期间发生的穿越。
        List<KlineBar> base = view.closedBars(StrategyMarketView.BASE_INTERVAL_MILLIS, 1);
        if (base.isEmpty()) return Optional.empty();
        KlineBar last5m = base.getLast();
        boolean passed = plan.isLong()
                ? last5m.close().compareTo(plan.limit()) <= 0
                : last5m.close().compareTo(plan.limit()) >= 0;
        if (passed) {
            consume(state, plan.fvgKey());
            return Optional.empty();
        }

        double score = Math.min(1.0, plan.rr().doubleValue() / 3.0);
        // LIMIT挂单信号：计划有效期间每根5m reaffirm（幂等），返回empty即撤单
        return Optional.of(new StrategySignal(ID, symbol, plan.isLong() ? "LONG" : "SHORT", plan.isLong(),
                plan.limit(), plan.stop(), plan.takeProfit(), score, plan.reason(), last5m.closeTime(), "LIMIT"));
    }

    @Override
    public void onPositionOpened(String symbol, StrategySignal signal, Long positionId,
                                 BigDecimal actualEntryPrice, StrategyMarketView view,
                                 TradingOperations tools) {
        // 成交即消费当前FVG，一FVG只交易一次；SL/TP已在挂单信号里固定
        SymbolState state = states.computeIfAbsent(symbol, ignored -> new SymbolState());
        if (state.armedKey != null) {
            consume(state, state.armedKey);
        }
    }

    /** 1h级计划构建：选候选FVG→PD门→算价位→RR门；任一环节不过返回null(本小时不挂单)。 */
    private Plan buildPlan(SymbolState state, StrategyMarketView view) {
        boolean wantLong = state.bias > 0;
        List<KlineBar> structBars = view.closedBars(params.structTfMillis(), params.fvgLookbackBars());
        if (structBars.size() <= params.atrPeriod() + 2) return null;

        // 候选=顺偏向、未消费、未缓解、中位未被触及的最新FVG；窗口本身就是龄上限
        List<SmcStructures.Fvg> fvgs = SmcStructures.findFvgs(
                structBars, params.atrPeriod(), params.displacementAtrMult());
        SmcStructures.Fvg cand = null;
        for (int i = fvgs.size() - 1; i >= 0; i--) {
            SmcStructures.Fvg f = fvgs.get(i);
            if (f.bullish() != wantLong || state.consumedKeys.contains(f.key())) continue;
            if (SmcStructures.mitigated(f, structBars) || SmcStructures.midTouched(f, structBars)) continue;
            cand = f;
            break;
        }
        if (cand == null) {
            state.armedKey = null;
            return null;
        }

        Optional<SmcStructures.SwingRange> rangeOpt = SmcStructures.swingRange(
                structBars, params.atrPeriod(), params.reversalAtrMult());
        if (rangeOpt.isEmpty()) return null;
        SmcStructures.SwingRange range = rangeOpt.get();

        BigDecimal limit = cand.mid();
        // Premium/Discount门：做多只在折价区接、做空只在溢价区卖（便宜买贵卖，不追中段）
        if (params.discountFilterOn()) {
            boolean inZone = wantLong
                    ? limit.compareTo(range.equilibrium()) < 0
                    : limit.compareTo(range.equilibrium()) > 0;
            if (!inZone) return null;
        }

        double[] atr = SwingDetector.atrSeries(structBars, params.atrPeriod());
        double lastAtr = atr[atr.length - 1];
        BigDecimal buffer = Double.isFinite(lastAtr) && lastAtr > 0
                ? BigDecimal.valueOf(lastAtr * params.slBufferAtrMult()).setScale(8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal stop = wantLong ? cand.lower().subtract(buffer) : cand.upper().add(buffer);
        BigDecimal takeProfit = wantLong ? range.high() : range.low();

        BigDecimal risk = limit.subtract(stop).abs();
        if (risk.signum() <= 0) return null;
        boolean sane = wantLong
                ? stop.compareTo(limit) < 0 && takeProfit.compareTo(limit) > 0
                : stop.compareTo(limit) > 0 && takeProfit.compareTo(limit) < 0;
        if (!sane) return null;
        BigDecimal rr = takeProfit.subtract(limit).abs().divide(risk, 4, RoundingMode.HALF_UP);
        if (rr.compareTo(riskPolicy.minRiskReward()) < 0) return null;

        // 装上候选：换新FVG才重置超时计时，同一FVG跨小时重建计划不重置
        if (state.armedKey == null || state.armedKey != cand.key()) {
            state.armedKey = cand.key();
            state.armedAtMs = view.nowMs();
        }
        String side = wantLong ? "LONG" : "SHORT";
        String reason = "FVG回补" + side
                + " zone=[" + plain(cand.lower()) + "," + plain(cand.upper()) + "]"
                + " limit=" + plain(limit) + " rr=" + rr.toPlainString();
        return new Plan(cand.key(), wantLong, limit, stop, takeProfit, rr, reason);
    }

    private static void consume(SymbolState state, long key) {
        state.consumedKeys.add(key);
        if (state.consumedKeys.size() > CONSUMED_CAP) {
            Iterator<Long> it = state.consumedKeys.iterator();
            it.next();
            it.remove();
        }
        state.armedKey = null;
        state.plan = null;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** 1h级挂单计划（一小时内价位不变，5m级只做守门与reaffirm）。 */
    private record Plan(long fvgKey, boolean isLong, BigDecimal limit, BigDecimal stop,
                        BigDecimal takeProfit, BigDecimal rr, String reason) {
    }

    private static final class SymbolState {
        final LinkedHashSet<Long> consumedKeys = new LinkedHashSet<>();
        Long armedKey;                         // 当前装上的候选FVG键
        long armedAtMs;                        // 装上时刻=挂单超时计时起点
        long biasBucket = Long.MIN_VALUE;      // 4h偏向缓存桶号
        int bias;
        long structBucket = Long.MIN_VALUE;    // 1h计划缓存桶号
        Plan plan;
    }
}
