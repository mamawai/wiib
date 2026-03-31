package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibservice.agent.quant.domain.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class MicrostructureAgent implements FactorAgent {

    @Override
    public String name() { return "microstructure"; }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot s) {
        List<String> flags = new ArrayList<>();

        double bia = s.bidAskImbalance();
        double td = s.tradeDelta();
        double oi = s.oiChangeRate();
        BigDecimal lastPrice = s.lastPrice();
        Map<String, BigDecimal> pc = s.priceChanges();
        double price1mBps = pc != null && pc.containsKey("5m")
                ? pc.get("5m").doubleValue() * 100 : 0;

        // 盘口偏向
        double bidAskScore = clamp(bia * 2.5);
        if (Math.abs(bia) > 0.3) flags.add(bia > 0 ? "BID_DOMINANT" : "ASK_DOMINANT");

        // 主动买卖差
        double deltaScore = clamp(td);
        if (Math.abs(td) > 0.3) flags.add(td > 0 ? "AGGRESSIVE_BUY" : "AGGRESSIVE_SELL");

        // OI-价格共振: OI增+价涨=新多, OI增+价跌=新空
        double oiScore = 0;
        if (Math.abs(oi) > 0.01) {
            boolean oiUp = oi > 0;
            boolean priceUp = price1mBps > 0;
            if (oiUp && priceUp) { oiScore = 0.6; flags.add("OI_UP_PRICE_UP"); }
            else if (oiUp && !priceUp) { oiScore = -0.6; flags.add("OI_UP_PRICE_DOWN"); }
            else if (!oiUp && priceUp) { oiScore = -0.3; flags.add("OI_DOWN_PRICE_UP"); }
            else { oiScore = 0.3; flags.add("OI_DOWN_PRICE_DOWN"); }
        }

        // 按文档6.4：每个区间独立信号权重
        double raw0 = 0.40 * bidAskScore + 0.35 * deltaScore + 0.25 * oiScore;  // 0-10: 盘口主导
        double raw1 = 0.15 * bidAskScore + 0.15 * deltaScore + 0.20 * oiScore;  // 10-20: OI升权
        double raw2 = 0.05 * bidAskScore + 0.05 * deltaScore + 0.10 * oiScore;  // 20-30: 微结构弱化

        double conf = Math.min(1.0, (Math.abs(bia) + Math.abs(td)) / 1.2);

        int volBps = s.atr1m() != null && lastPrice != null && lastPrice.signum() > 0
                ? s.atr1m().multiply(BigDecimal.valueOf(10000))
                    .divide(lastPrice, 0, java.math.RoundingMode.HALF_UP).intValue()
                : 20;

        List<AgentVote> votes = new ArrayList<>(3);
        votes.add(buildVote("0_10", raw0, conf, volBps, flags));
        votes.add(buildVote("10_20", raw1, conf * 0.6, volBps, flags));  // 微结构信号在10-20min置信度降低
        votes.add(buildVote("20_30", raw2, conf * 0.3, volBps, flags));

        log.info("[Q3.micro] bia={} td={} oi={} → scores[{},{},{}] conf={} flags={}",
                String.format("%.3f", bia), String.format("%.3f", td), String.format("%.3f", oi),
                String.format("%.3f", raw0), String.format("%.3f", raw1), String.format("%.3f", raw2),
                String.format("%.2f", conf), flags);
        return votes;
    }

    private AgentVote buildVote(String horizon, double score, double conf, int volBps, List<String> reasons) {
        double s = clamp(score);
        Direction dir = Math.abs(s) < 0.05 ? Direction.NO_TRADE : (s > 0 ? Direction.LONG : Direction.SHORT);
        int moveBps = (int) (Math.abs(s) * volBps * 0.6);
        return new AgentVote(name(), horizon, dir, s, Math.clamp(conf, 0, 1),
                moveBps, volBps, List.copyOf(reasons), List.of());
    }

    private static double clamp(double v) { return Math.clamp(v, -1, 1); }
}
