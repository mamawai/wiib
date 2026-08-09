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
import java.util.function.Supplier;

/**
 * 市场数据组装服务：采集 → 特征快照 一条链，带 TTL 缓存。
 * 工具层（对话 agent / MCP / AI Trader）共用这条链，避免各处重复采集。
 * 规则信号面板与脆弱度合成层已随预测管线下线（2026-08：生产验证无前瞻信息，原料字段仍在快照里原样提供）。
 */
@Slf4j
@Service
public class MarketDataService {

    private final BinanceRestClient binanceRestClient;
    private final DepthStreamCache depthStreamCache;
    private final CollectDataNode collectNode;
    private final BuildFeaturesNode featuresNode;
    private final long ttlMillis;
    private final Map<String, MarketAssembly> cache = new ConcurrentHashMap<>();
    /** 在途采集：TTL 过期瞬间多个线程同时 miss，只放一个去真采集，其余等它的结果 */
    private final Map<String, CompletableFuture<MarketAssembly>> inFlight = new ConcurrentHashMap<>();

    /** 裸 REST 结果的 TTL 缓存：资金费历史/盘口这类"工具直取"的数据，与整装快照分开老化 */
    private record Cached(String value, long at) {}

    private final Map<String, Cached> rawCache = new ConcurrentHashMap<>();

    public MarketDataService(BinanceRestClient binanceRestClient,
                             ForceOrderService forceOrderService,
                             DepthStreamCache depthStreamCache,
                             DeribitClient deribitClient,
                             OrderFlowAggregator orderFlowAggregator,
                             @Value("${trading.decision-interval:M5}") KlineInterval decisionInterval,
                             @Value("${quant.toolkit.assembly-ttl-ms:60000}") long ttlMillis) {
        this.binanceRestClient = binanceRestClient;
        this.depthStreamCache = depthStreamCache;
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
        // putIfAbsent 原子选出唯一"采集者"：抢到的去真采集，没抢到的等它的 future。
        // 不用 computeIfAbsent——mapping 函数期间会锁住整个 bin，同 bin 的别的 symbol 也跟着卡
        CompletableFuture<MarketAssembly> mine = new CompletableFuture<>();
        CompletableFuture<MarketAssembly> running = inFlight.putIfAbsent(normalized, mine);
        if (running != null) {
            return join(running, normalized);
        }
        try {
            // 抢到采集权后复查：cache.get 到 putIfAbsent 之间可能已经有人放了新快照进来，别白打一轮
            MarketAssembly again = cache.get(normalized);
            if (fresh(again)) {
                mine.complete(again);
                return again;
            }
            MarketAssembly assembled = assembleFresh(normalized);
            cache.put(normalized, assembled);
            mine.complete(assembled);
            return assembled;
        } catch (Throwable t) {
            // join() 无超时且不可中断，漏一次 complete 就留下一批杀不掉的僵尸线程，所以 Error 也要给终局。
            // 一处 catch 覆盖全部逃逸路径，等待者日志里拿到的就是原始根因（爆栈/OOM 本尊）；
            // 换成 finally 里补一个壳异常，真凶会被盖住，而且每次采集都白建一个带栈对象——OOM 时它自己就分配不出来
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inFlight.remove(normalized, mine);
        }
    }

    /**
     * 资金费历史（近 30 条）。失败返回 null，调用方按"数据不可用"处理。
     * 取不到新数据时兜过期那份：资金费 8 小时才结算一次，两分钟前的历史与实时没有差别，
     * 而熔断冷却 120s 比 TTL 60s 长，不兜就会白白黑掉中间那 60 秒。
     * <p>
     * 这份兜底要真生效，得和 {@link #premiumIndex} 同进同退：两者共用同一个熔断器，
     * funding_history 工具是"历史 + 资金费上下文"一起出的，任一边空了整条工具就报不可用。
     */
    public String fundingHistory(String symbol) {
        String normalized = QuantConstants.normalizeSymbolLenient(symbol);
        return rawCached("funding:" + normalized, true,
                () -> binanceRestClient.getFundingRateHistory(normalized, 30));
    }

    /**
     * 资金费上下文（下次结算时间 / 上次费率 / 标记价）。仅供 @Tool 层给 LLM 读，失败返回 null。
     * <p>
     * 允许兜过期那份：这三个字段在资金费语境下陈旧几分钟无害——费率按 8 小时结算，
     * 两分钟前的值与实时没有差别。反过来若不兜，熔断期这里一 null 就把整条 funding_history
     * 拖垮，历史那边的兜底也就白做了。
     * <p>
     * <b>算钱的路径不许走这里</b>：trader 下单量（TraderWakeupRunner）和结算价
     * （FuturesSettlementServiceImpl）各自直调 BinanceRestClient 取实时 markPrice，
     * BTC 一分钟就能走 0.1~0.3%，拿缓存价格算钱是真会出偏差的。
     */
    public String premiumIndex(String symbol) {
        String normalized = QuantConstants.normalizeSymbolLenient(symbol);
        return rawCached("premium:" + normalized, true,
                () -> binanceRestClient.getPremiumIndex(normalized));
    }

    /**
     * 合约盘口深度。优先 WS 快照，断流才回退 REST；失败返回 null。
     * 返回的是原始档位（WS 为 top20），截到工具描述承诺的档数由工具层做。
     */
    public String orderbook(String symbol) {
        String normalized = QuantConstants.normalizeSymbolLenient(symbol);
        // WS depth20@100ms 比 REST 新鲜三个数量级且不吃 Binance 配额，优先用它；
        // 字段名 feed 侧已统一成 bids/asks，与 REST 同构（见 DepthStreamHandler）
        String ws = depthStreamCache.getFreshDepth(normalized, 2000);
        if (ws != null) {
            return ws;
        }
        // 盘口不兜过期数据：agent 拿它挂限价单，BTC 一分钟就能走 0.1~0.3%，宁可报不可用
        return rawCached("depth:" + normalized, false,
                () -> binanceRestClient.getFuturesOrderbook(normalized, 10));
    }

    /**
     * 裸 REST 取数 + TTL 缓存。
     * @param serveStale 取不到新数据（熔断/网络故障）时是否回退到过期条目——只对老化慢的数据开
     */
    private String rawCached(String key, boolean serveStale, Supplier<String> loader) {
        Cached hit = rawCache.get(key);
        if (hit != null && Instant.now().toEpochMilli() - hit.at() < ttlMillis) {
            return hit.value();
        }
        try {
            String value = loader.get();
            if (value != null) {
                rawCache.put(key, new Cached(value, Instant.now().toEpochMilli()));
                return value;
            }
        } catch (Exception e) {
            log.warn("[Toolkit] 取数失败 key={}", key, e);
        }
        return serveStale && hit != null ? hit.value() : null;
    }

    private boolean fresh(MarketAssembly cached) {
        return cached != null
                && Instant.now().toEpochMilli() - cached.assembledAt().toEpochMilli() < ttlMillis;
    }

    /**
     * 等在途采集的结果。采集失败时等待者拿到的是降级的 unavailable——异常不跨线程传播，
     * 只在采集线程自己那里往上抛；一个 symbol 采集炸了不该把等它的对话/交易线程一起打断。
     */
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
