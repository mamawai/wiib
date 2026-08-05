package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;

import java.time.Instant;
import java.util.Map;

/**
 * 一次市场数据组装的完整产物：采集原始数据 + 特征快照。
 * available=false 时 snapshot 为 null，工具层据此输出"数据不可用"。
 */
public record MarketAssembly(
        String symbol,
        boolean available,
        Map<String, Object> rawData,
        Map<String, Object> featureOutput,
        FeatureSnapshot snapshot,
        Instant assembledAt
) {
    public static MarketAssembly unavailable(String symbol, Map<String, Object> rawData) {
        return new MarketAssembly(symbol, false, rawData == null ? Map.of() : rawData,
                Map.of(), null, Instant.now());
    }
}
