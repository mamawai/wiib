package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibservice.agent.quant.domain.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class RegimeAgent implements FactorAgent {

    @Override
    public String name() { return "regime"; }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot s) {
        Map<String, Map<String, Object>> indicators = s.indicatorsByTimeframe();
        if (indicators == null || indicators.isEmpty()) {
            return List.of(
                    AgentVote.noTrade(name(), "0_10", "NO_DATA"),
                    AgentVote.noTrade(name(), "10_20", "NO_DATA"),
                    AgentVote.noTrade(name(), "20_30", "NO_DATA"));
        }

        MarketRegime regime = s.regime();
        List<String> reasons = new ArrayList<>();
        reasons.add("REGIME_" + regime.name());

        // 根据regime给出方向偏好
        double dirScore = calcDirectionScore(indicators, regime, reasons);

        // 趋势市: 顺势信号强, 全区间有效
        // 震荡市: 信号弱, 主要看超短线反转
        // SQUEEZE: 不给方向, 等突破
        // SHOCK: 极端谨慎
        double conf0, conf1, conf2;
        double s0, s1, s2;
        List<String> riskFlags = new ArrayList<>();

        switch (regime) {
            case TREND_UP, TREND_DOWN -> {
                s0 = dirScore * 0.6;
                s1 = dirScore * 0.8;
                s2 = dirScore * 1.0;
                conf0 = 0.5;
                conf1 = 0.65;
                conf2 = 0.7;
            }
            case RANGE -> {
                // 震荡市：均值回归逻辑，方向反转
                s0 = dirScore * 0.3;
                s1 = dirScore * 0.2;
                s2 = dirScore * 0.1;
                conf0 = 0.4;
                conf1 = 0.35;
                conf2 = 0.3;
                riskFlags.add("RANGE_BOUND");
            }
            case SQUEEZE -> {
                // 收缩不等于没有方向，只是要求更严格地等待确认。
                s0 = dirScore * 0.45;
                s1 = dirScore * 0.65;
                s2 = dirScore * 0.85;
                conf0 = Math.min(0.45, 0.24 + Math.abs(dirScore) * 0.20);
                conf1 = Math.min(0.50, 0.28 + Math.abs(dirScore) * 0.22);
                conf2 = Math.min(0.55, 0.32 + Math.abs(dirScore) * 0.24);
                riskFlags.add("SQUEEZE_WAIT_BREAKOUT");
                riskFlags.add("ONLY_TRADE_WITH_BREAK_CONFIRMATION");
            }
            case SHOCK -> {
                s0 = dirScore * 0.2;
                s1 = dirScore * 0.1;
                s2 = 0;
                conf0 = 0.2;
                conf1 = 0.1;
                conf2 = 0.05;
                riskFlags.add("EXTREME_VOLATILITY");
            }
            default -> {
                s0 = 0; s1 = 0; s2 = 0;
                conf0 = 0.3; conf1 = 0.3; conf2 = 0.3;
            }
        }

        int volBps = estimateVolBps(s);

        log.info("[Q3.regime] regime={} dirScore={} → scores[{},{},{}] riskFlags={}",
                regime, String.format("%.3f", dirScore),
                String.format("%.3f", s0), String.format("%.3f", s1),
                String.format("%.3f", s2), riskFlags);

        return List.of(
                buildVote("0_10", s0, conf0, volBps, reasons, riskFlags),
                buildVote("10_20", s1, conf1, volBps, reasons, riskFlags),
                buildVote("20_30", s2, conf2, volBps, reasons, riskFlags));
    }

    private double calcDirectionScore(Map<String, Map<String, Object>> indicators,
                                       MarketRegime regime, List<String> reasons) {
        // 从中周期方向判断，15m缺失时退化为5m/1h，避免单个周期缺失直接失明。
        double score = 0;
        boolean fallbackUsed = false;
        List<String> timeframes = new ArrayList<>(2);
        timeframes.add(indicators.containsKey("15m") ? "15m" : "5m");
        timeframes.add(indicators.containsKey("1h") ? "1h" : "4h");

        for (String tf : timeframes) {
            Map<String, Object> ind = indicators.get(tf);
            if (ind == null) continue;
            if (!"15m".equals(tf) && !"1h".equals(tf)) {
                fallbackUsed = true;
            }

            BigDecimal plusDi = toBd(ind.get("plus_di"));
            BigDecimal minusDi = toBd(ind.get("minus_di"));
            if (plusDi != null && minusDi != null) {
                double diff = plusDi.doubleValue() - minusDi.doubleValue();
                double normalized = Math.clamp(diff / 30.0, -1, 1);
                score += normalized * 0.5;
                if (diff > 10) reasons.add("DI_BULLISH_" + tf.toUpperCase());
                else if (diff < -10) reasons.add("DI_BEARISH_" + tf.toUpperCase());
            }

            // 均线排列辅助
            int maAlign = toInt(ind.get("ma_alignment"));
            score += maAlign * 0.15;
        }

        if (fallbackUsed) {
            reasons.add("REGIME_TF_FALLBACK");
            score *= 0.92;
        }

        return Math.clamp(score, -1, 1);
    }

    private int estimateVolBps(FeatureSnapshot s) {
        BigDecimal lastPrice = s.lastPrice();
        BigDecimal atr = s.atr5m() != null ? s.atr5m() : s.atr1m();
        if (atr != null && lastPrice != null && lastPrice.signum() > 0) {
            return atr.multiply(BigDecimal.valueOf(10000))
                    .divide(lastPrice, 0, java.math.RoundingMode.HALF_UP).intValue();
        }
        return 30;
    }

    private AgentVote buildVote(String horizon, double score, double conf,
                                 int volBps, List<String> reasons, List<String> riskFlags) {
        double s = Math.clamp(score, -1, 1);
        Direction dir = Math.abs(s) < 0.05 ? Direction.NO_TRADE : (s > 0 ? Direction.LONG : Direction.SHORT);
        int moveBps = (int) (Math.abs(s) * volBps * 0.4);
        return new AgentVote(name(), horizon, dir, s, conf, moveBps, volBps,
                List.copyOf(reasons), List.copyOf(riskFlags));
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static BigDecimal toBd(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }
}
