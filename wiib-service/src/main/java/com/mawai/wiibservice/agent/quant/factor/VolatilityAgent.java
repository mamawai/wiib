package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibservice.agent.quant.domain.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class VolatilityAgent implements FactorAgent {

    @Override
    public String name() { return "volatility"; }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot s) {
        Map<String, Map<String, Object>> indicators = s.indicatorsByTimeframe();
        if (indicators == null || indicators.isEmpty()) {
            return List.of(
                    AgentVote.noTrade(name(), "0_10", "NO_DATA"),
                    AgentVote.noTrade(name(), "10_20", "NO_DATA"),
                    AgentVote.noTrade(name(), "20_30", "NO_DATA"));
        }

        BigDecimal lastPrice = s.lastPrice();
        if (lastPrice == null || lastPrice.signum() <= 0) {
            return List.of(
                    AgentVote.noTrade(name(), "0_10", "NO_PRICE"),
                    AgentVote.noTrade(name(), "10_20", "NO_PRICE"),
                    AgentVote.noTrade(name(), "20_30", "NO_PRICE"));
        }

        List<String> reasons = new ArrayList<>();
        List<String> riskFlags = new ArrayList<>();

        // 从布林带判断波动状态
        double bollScore = calcBollScore(indicators, reasons, riskFlags);

        // 从ATR判断波动是否在放大/收缩
        double atrScore = calcAtrTrendScore(indicators, reasons);

        // 综合: 波动率因子不直接给方向,而是给"波动率扩张=趋势延续"或"波动率收缩=变盘"的信号
        // 扩张+有方向 → 顺势, 收缩 → 中性观望
        double rawScore = bollScore * 0.6 + atrScore * 0.4;

        // 波动率估计(基点)
        int vol0 = estimateVolBps(s, "1m", 10);    // 0-10min: 用1m ATR × sqrt(10)
        int vol1 = estimateVolBps(s, "5m", 4);     // 10-20min: 用5m ATR × sqrt(4)
        int vol2 = estimateVolBps(s, "5m", 6);     // 20-30min: 用5m ATR × sqrt(6)

        // 波动率因子给的方向信号很弱，主要贡献是volatilityBps和expectedMoveBps
        double conf = 0.3 + Math.min(0.4, Math.abs(rawScore) * 0.5);

        log.info("[Q3.vol] bollScore={} atrScore={} rawScore={} → volBps[{},{},{}] reasons={} riskFlags={}",
                String.format("%.3f", bollScore), String.format("%.3f", atrScore),
                String.format("%.3f", rawScore), vol0, vol1, vol2, reasons, riskFlags);

        return List.of(
                buildVote("0_10", rawScore * 0.5, conf * 0.8, vol0, reasons, riskFlags),
                buildVote("10_20", rawScore * 0.4, conf * 0.7, vol1, reasons, riskFlags),
                buildVote("20_30", rawScore * 0.3, conf * 0.6, vol2, reasons, riskFlags));
    }

    private double calcBollScore(Map<String, Map<String, Object>> indicators,
                                  List<String> reasons, List<String> riskFlags) {
        double score = 0;
        // 看5m布林带
        Map<String, Object> ind5m = indicators.get("5m");
        if (ind5m != null) {
            BigDecimal pb = toBd(ind5m.get("boll_pb"));
            BigDecimal bw = toBd(ind5m.get("boll_bandwidth"));

            if (pb != null) {
                double p = pb.doubleValue();
                // 贴上轨(>85)偏空, 贴下轨(<15)偏多
                if (p > 85) { score -= 0.3; reasons.add("BOLL_UPPER_TOUCH_5M"); }
                else if (p < 15) { score += 0.3; reasons.add("BOLL_LOWER_TOUCH_5M"); }
                // 中轨附近偏中性
                else if (p > 55) score += 0.05;
                else if (p < 45) score -= 0.05;
            }

            if (bw != null) {
                double b = bw.doubleValue();
                if (b < 1.5) { reasons.add("BOLL_SQUEEZE_5M"); riskFlags.add("VOLATILITY_COMPRESSED"); }
                else if (b > 5.0) { reasons.add("BOLL_EXPANSION_5M"); }
            }
        }

        // 看15m布林带辅助
        Map<String, Object> ind15m = indicators.get("15m");
        if (ind15m != null) {
            BigDecimal bw15 = toBd(ind15m.get("boll_bandwidth"));
            if (bw15 != null && bw15.doubleValue() < 1.2) {
                riskFlags.add("BOLL_SQUEEZE_15M");
            }
        }

        return Math.max(-1, Math.min(1, score));
    }

    private double calcAtrTrendScore(Map<String, Map<String, Object>> indicators,
                                      List<String> reasons) {
        // 看ATR是否在放大: 通过比较1m和5m的ATR占比
        Map<String, Object> ind1m = indicators.get("1m");
        Map<String, Object> ind5m = indicators.get("5m");

        BigDecimal atr1m = ind1m != null ? toBd(ind1m.get("atr14")) : null;
        BigDecimal atr5m = ind5m != null ? toBd(ind5m.get("atr14")) : null;

        if (atr1m != null && atr5m != null && atr5m.signum() > 0) {
            // 1m ATR 相对于 5m ATR 的比例, 如果1m ATR偏高说明波动在加速
            double ratio = atr1m.multiply(BigDecimal.valueOf(5))
                    .divide(atr5m, 4, RoundingMode.HALF_UP).doubleValue();
            if (ratio > 1.5) { reasons.add("ATR_ACCELERATING"); return 0.2; }
            else if (ratio < 0.6) { reasons.add("ATR_DECELERATING"); return -0.1; }
        }
        return 0;
    }

    private int estimateVolBps(FeatureSnapshot s, String timeframe, int periods) {
        BigDecimal lastPrice = s.lastPrice();
        Map<String, Map<String, Object>> indicators = s.indicatorsByTimeframe();
        if (indicators == null || lastPrice == null || lastPrice.signum() <= 0) return 30;

        Map<String, Object> ind = indicators.get(timeframe);
        if (ind == null) return 30;

        BigDecimal atr = toBd(ind.get("atr14"));
        if (atr == null) return 30;

        // ATR × sqrt(periods) 估算区间波动
        double scaledAtr = atr.doubleValue() * Math.sqrt(periods);
        return Math.max(5, (int) (scaledAtr / lastPrice.doubleValue() * 10000));
    }

    private AgentVote buildVote(String horizon, double score, double conf,
                                 int volBps, List<String> reasons, List<String> riskFlags) {
        double s = Math.max(-1, Math.min(1, score));
        Direction dir = Math.abs(s) < 0.05 ? Direction.NO_TRADE : (s > 0 ? Direction.LONG : Direction.SHORT);
        int moveBps = (int) (Math.abs(s) * volBps * 0.4);
        return new AgentVote(name(), horizon, dir, s, Math.max(0, Math.min(1, conf)),
                moveBps, volBps, List.copyOf(reasons), List.copyOf(riskFlags));
    }

    private static BigDecimal toBd(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }
}
