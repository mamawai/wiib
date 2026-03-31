package com.mawai.wiibservice.agent.quant.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FeatureSnapshot(
        String symbol,
        LocalDateTime snapshotTime,
        BigDecimal lastPrice,

        // 多周期技术指标: timeframe → indicatorName → value
        Map<String, Map<String, Object>> indicatorsByTimeframe,
        // 多周期价格变化: label → pct
        Map<String, BigDecimal> priceChanges,

        // 盘口微结构
        double bidAskImbalance,
        double tradeDelta,
        double oiChangeRate,
        double fundingDeviation,
        double fundingRateTrend,
        double fundingRateExtreme,
        double lsrExtreme,

        // 波动率
        BigDecimal atr1m,
        BigDecimal atr5m,
        BigDecimal bollBandwidth,
        boolean bollSqueeze,

        // 市场状态
        MarketRegime regime,

        // 新闻
        List<NewsItem> newsItems,

        // 数据质量
        List<String> qualityFlags
) {}

