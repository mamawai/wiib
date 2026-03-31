package com.mawai.wiibservice.agent.quant.domain;

import java.math.BigDecimal;

public record HorizonForecast(
        String horizon,           // 0_10 / 10_20 / 20_30
        Direction direction,      // LONG / SHORT / NO_TRADE
        double confidence,        // 综合置信度 [0, 1]
        double weightedScore,     // 加权得分，正=多头优势
        double disagreement,      // 分歧度 [0, 1]，越高信号越分裂
        BigDecimal entryLow,      // 入场区间下沿
        BigDecimal entryHigh,     // 入场区间上沿
        BigDecimal invalidationPrice, // 失效价（止损触发价）
        BigDecimal tp1,           // 止盈目标1
        BigDecimal tp2,           // 止盈目标2
        int maxLeverage,          // 最大杠杆倍数
        double maxPositionPct     // 最大仓位比例，如0.08=8%
) {
    public static HorizonForecast noTrade(String horizon, double disagreement) {
        return new HorizonForecast(horizon, Direction.NO_TRADE, 0, 0, disagreement,
                null, null, null, null, null, 0, 0);
    }
}
