package com.mawai.wiibservice.agent.quant.judge;

import com.mawai.wiibservice.agent.quant.domain.Direction;
import com.mawai.wiibservice.agent.quant.domain.HorizonForecast;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 汇总3个区间裁决结果，生成overallDecision。
 * <p>
 * 优先级：优先推荐置信度最高且非NO_TRADE的区间。
 * 如果全部NO_TRADE，输出FLAT。
 */
@Slf4j
public class ConsensusBuilder {

    /**
     * 生成总体决策摘要字符串。
     * 格式：PRIORITIZE_{horizon}_{direction}，或 FLAT
     */
    public static String buildDecision(List<HorizonForecast> forecasts) {
        if (forecasts == null || forecasts.isEmpty()) {
            return "FLAT";
        }

        HorizonForecast best = null;
        for (HorizonForecast f : forecasts) {
            if (f.direction() == Direction.NO_TRADE) continue;
            if (best == null || f.confidence() > best.confidence()) {
                best = f;
            }
        }

        if (best == null) {
            log.info("[Q4.consensus] 全部NO_TRADE → FLAT");
            return "FLAT";
        }

        String decision = "PRIORITIZE_" + best.horizon() + "_" + best.direction().name();
        log.info("[Q4.consensus] best={}_{} conf={} → {}",
                best.horizon(), best.direction(), String.format("%.2f", best.confidence()), decision);
        return decision;
    }

    /**
     * 判断总体风险状态。
     */
    public static String buildRiskStatus(List<HorizonForecast> forecasts) {
        if (forecasts == null || forecasts.isEmpty()) return "UNKNOWN";

        long noTradeCount = forecasts.stream()
                .filter(f -> f.direction() == Direction.NO_TRADE)
                .count();

        double maxDisagreement = forecasts.stream()
                .mapToDouble(HorizonForecast::disagreement)
                .max().orElse(0);

        String status;
        if (noTradeCount == forecasts.size()) status = "ALL_NO_TRADE";
        else if (maxDisagreement >= 0.35) status = "HIGH_DISAGREEMENT";
        else if (noTradeCount >= 1 || maxDisagreement >= 0.25) status = "CAUTIOUS";
        else status = "NORMAL";

        log.info("[Q4.riskStatus] noTrade={}/{} maxDisagree={} → {}",
                noTradeCount, forecasts.size(), String.format("%.2f", maxDisagreement), status);
        return status;
    }
}
