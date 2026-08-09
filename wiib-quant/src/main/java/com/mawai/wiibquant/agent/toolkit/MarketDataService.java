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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    /** 在途采集：TTL 过期瞬间多个线程同时 miss，只放一个去真采集，其余等它的结果 */
    private final Map<String, CompletableFuture<MarketAssembly>> inFlight = new ConcurrentHashMap<>();

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

    /** 工具层统一入口：TTL 内直接复用；过期时同 symbol 只允许一个线程真采集（防击穿）。 */
    public MarketAssembly assemble(String symbol) {
        String normalized = QuantConstants.normalizeSymbolLenient(symbol);
        MarketAssembly cached = cache.get(normalized);
        if (fresh(cached)) {
            return cached;
        }
        // computeIfAbsent 的 mapping 函数对同一 key 互斥，天然选出唯一的"采集者"。
        // 采集本身放在函数外做（见下），函数内只登记 future——否则采集期间整个桶被锁住，
        // 其他 symbol 的请求也会被卡（ConcurrentHashMap 是分段锁，同桶不同 key 也会互等）
        CompletableFuture<MarketAssembly> mine = new CompletableFuture<>();
        CompletableFuture<MarketAssembly> running = inFlight.putIfAbsent(normalized, mine);
        if (running != null) {
            return join(running, normalized);
        }
        try {
            MarketAssembly fresh = assembleFresh(normalized);
            cache.put(normalized, fresh);
            mine.complete(fresh);
            return fresh;
        } catch (RuntimeException e) {
            // 失败也要唤醒等待者，否则它们挂到超时；异常原样传播给每一个等待者
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(normalized, mine);
        }
    }

    private boolean fresh(MarketAssembly cached) {
        return cached != null
                && Instant.now().toEpochMilli() - cached.assembledAt().toEpochMilli() < ttlMillis;
    }

    /** 等在途采集的结果。等待者不该因为别人的失败而卡死，也不该吞掉异常。 */
    private MarketAssembly join(CompletableFuture<MarketAssembly> running, String symbol) {
        try {
            return running.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[Toolkit] 等待在途采集失败 symbol={}", symbol, cause);
            return MarketAssembly.unavailable(symbol, Map.of());
        }
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
