package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.node.BuildFeaturesNode;
import com.mawai.wiibquant.agent.quant.node.CollectDataNode;
import com.mawai.wiibquant.config.DeribitClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市场数据组装服务：采集 → 特征快照 一条链，带 TTL 缓存。
 * 工具层（对话 agent / MCP / AI Trader）共用这条链，避免各处重复采集。
 * 规则信号面板与脆弱度合成层已随预测管线下线（2026-08：生产验证无前瞻信息，原料字段仍在快照里原样提供）。
 */
@Slf4j
@Service
public class MarketDataService {

    private final CollectDataNode collectNode;
    private final BuildFeaturesNode featuresNode;
    private final long ttlMillis;
    private final Map<String, MarketAssembly> cache = new ConcurrentHashMap<>();

    public MarketDataService(BinanceRestClient binanceRestClient,
                             ForceOrderService forceOrderService,
                             DepthStreamCache depthStreamCache,
                             DeribitClient deribitClient,
                             OrderFlowAggregator orderFlowAggregator,
                             @Value("${trading.decision-interval:M5}") KlineInterval decisionInterval,
                             @Value("${quant.toolkit.assembly-ttl-ms:60000}") long ttlMillis) {
        this.collectNode = new CollectDataNode(binanceRestClient, forceOrderService, depthStreamCache, deribitClient);
        this.featuresNode = new BuildFeaturesNode(orderFlowAggregator, decisionInterval);
        this.ttlMillis = ttlMillis;
    }

    /** 工具层统一入口：TTL 内直接复用，避免一轮对话多个工具各采一遍。 */
    public MarketAssembly assemble(String symbol) {
        String normalized = QuantConstants.normalizeSymbolLenient(symbol);
        MarketAssembly cached = cache.get(normalized);
        if (cached != null && Instant.now().toEpochMilli() - cached.assembledAt().toEpochMilli() < ttlMillis) {
            return cached;
        }
        MarketAssembly fresh = assembleFresh(normalized);
        cache.put(normalized, fresh);
        return fresh;
    }

    MarketAssembly assembleFresh(String symbol) {
        long startMs = System.currentTimeMillis();
        Map<String, Object> raw = collectNode.collect(symbol, null);
        if (!Boolean.TRUE.equals(raw.get("data_available"))) {
            log.warn("[Toolkit] 采集不可用 symbol={}", symbol);
            return MarketAssembly.unavailable(symbol, raw);
        }
        Map<String, Object> featureOut = featuresNode.buildFeatures(symbol, raw);
        FeatureSnapshot snapshot = (FeatureSnapshot) featureOut.get("feature_snapshot");
        if (snapshot == null) {
            return MarketAssembly.unavailable(symbol, raw);
        }
        log.info("[Toolkit] 组装完成 symbol={} 耗时{}ms", symbol, System.currentTimeMillis() - startMs);
        return new MarketAssembly(symbol, true, raw, featureOut, snapshot, Instant.now());
    }

}
