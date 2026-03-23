package com.mawai.wiibservice.config;

import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.CryptoOrderService;
import com.mawai.wiibservice.service.FuturesLiquidationService;
import com.mawai.wiibservice.service.FuturesSettlementService;
import com.mawai.wiibservice.service.impl.RedisMessageBroadcastService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceWsClient implements SmartLifecycle {

    private final BinanceProperties props;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageBroadcastService broadcastService;
    private final BinanceRestClient restClient;
    private final CryptoOrderService cryptoOrderService;
    private final FuturesLiquidationService futuresLiquidationService;
    private final FuturesSettlementService futuresSettlementService;
    private final CacheService cacheService;

    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> fallbackTask;
    private ScheduledFuture<?> futuresFallbackTask;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private WsConnection spotWs;
    private WsConnection futuresWs;

    private static final String REDIS_KEY_PREFIX = "market:price:";
    private static final String REDIS_MARK_PRICE_KEY_PREFIX = "market:markprice:";

    @PostConstruct
    public void init() {
        if (props.getSymbols() == null || props.getSymbols().isEmpty()) {
            log.warn("binance.symbols未配置，跳过WS连接");
            return;
        }
        // 虚拟线程工厂做调度池，轻量不占平台线程
        scheduler = Executors.newScheduledThreadPool(3,
                Thread.ofVirtual().name("binance-ws-", 0).factory());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        spotWs = new WsConnection("Spot", this::buildSpotUrl, this::onSpotMessage,
                ws -> onSpotConnected(), this::startFallbackPolling,
                httpClient, scheduler, shutdown);
        futuresWs = new WsConnection("Futures", this::buildFuturesUrl, this::onFuturesMessage,
                ws -> onFuturesConnected(), this::startFuturesFallbackPolling,
                httpClient, scheduler, shutdown);

        // 启动ws
        spotWs.connect();
        futuresWs.connect();
    }

    @Override
    public void stop() {
        shutdown.set(true);
        stopFallbackPolling();
        stopFuturesFallbackPolling();
        if (spotWs != null) spotWs.close();
        if (futuresWs != null) futuresWs.close();
        if (scheduler != null) scheduler.shutdownNow();
        if (httpClient != null) httpClient.close();
    }

    @Override public boolean isRunning() { return !shutdown.get() && scheduler != null; }
    @Override public int getPhase() { return 1; }
    @Override public void start() { /* init via @PostConstruct */ }

    public boolean isConnected() {
        return spotWs != null && spotWs.isConnected();
    }

    public boolean isFuturesConnected() {
        return futuresWs != null && futuresWs.isConnected();
    }

    // ── Spot ──

    private String buildSpotUrl() {
        String streams = props.getSymbols().stream()
                .map(s -> s.toLowerCase() + "@miniTicker")
                .reduce((a, b) -> a + "/" + b).orElse("");
        if (props.getSymbols().size() == 1) {
            return props.getWsUrl() + "/" + streams;
        }
        return props.getWsUrl().replace("/ws", "/stream?streams=" + streams);
    }

    private void onSpotConnected() {
        stopFallbackPolling();
        // 重连后用REST拉最近高低价，补漏离线期间错过的限价单
        Thread.startVirtualThread(this::recoverMissedLimitOrders);
    }

    private void onSpotMessage(String raw) {
        // 手动indexOf解析避免Jackson反序列化开销
        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = extractQuoted(raw, sIdx + 5);

        int cIdx = raw.indexOf("\"c\":\"");
        if (cIdx < 0) return;
        String price = extractQuoted(raw, cIdx + 5);

        // 提取服务端时间戳，缺失则取本地时间
        int eIdx = raw.indexOf("\"E\":");
        long ts = System.currentTimeMillis();
        if (eIdx >= 0) {
            int eStart = eIdx + 4;
            int eEnd = raw.indexOf(',', eStart);
            if (eEnd < 0) eEnd = raw.indexOf('}', eStart);
            ts = Long.parseLong(raw.substring(eStart, eEnd).trim());
        }

        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + symbol, price);
        BigDecimal bd = new BigDecimal(price);
        cacheService.putCryptoPrice(symbol, bd);
        String markPrice = redisTemplate.opsForValue().get(REDIS_MARK_PRICE_KEY_PREFIX + symbol);
        String msg = "{\"price\":\"" + price + "\",\"ts\":" + ts
                + ",\"ws\":" + isConnected() + ",\"fws\":" + isFuturesConnected()
                + (markPrice != null ? ",\"mp\":\"" + markPrice + "\"" : "") + "}";
        broadcastService.broadcastCryptoQuote(symbol, msg);

        // 虚拟线程跑业务逻辑，不阻塞WS接收线程
        Thread.startVirtualThread(() -> {
            try { cryptoOrderService.onPriceUpdate(symbol, bd); }
            catch (Exception e) { log.warn("crypto限价单检查异常 {}: {}", symbol, e.getMessage()); }
        });
        Thread.startVirtualThread(() -> {
            try { futuresSettlementService.onPriceUpdate(symbol, bd); }
            catch (Exception e) { log.warn("futures限价单检查异常 {}: {}", symbol, e.getMessage()); }
        });
    }

    // ── Futures ──

    private void onFuturesConnected() {
        stopFuturesFallbackPolling();
        // 重连后补漏离线期间的强平检查
        Thread.startVirtualThread(this::recoverMissedLiquidations);
    }

    private String buildFuturesUrl() {
        String streams = props.getSymbols().stream()
                .map(s -> s.toLowerCase() + "@markPrice@1s")
                .reduce((a, b) -> a + "/" + b).orElse("");
        String base = props.getFuturesWsUrl();
        if (props.getSymbols().size() == 1) {
            return base + "/" + streams;
        }
        return base.replace("/ws", "/stream?streams=" + streams);
    }

    private void onFuturesMessage(String raw) {
        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = extractQuoted(raw, sIdx + 5);

        int pIdx = raw.indexOf("\"p\":\"");
        if (pIdx < 0) return;
        String markPrice = extractQuoted(raw, pIdx + 5);

        redisTemplate.opsForValue().set(REDIS_MARK_PRICE_KEY_PREFIX + symbol, markPrice);
        BigDecimal mp = new BigDecimal(markPrice);
        cacheService.putMarkPrice(symbol, mp);

        String spotPrice = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + symbol);
        BigDecimal cp = spotPrice != null ? new BigDecimal(spotPrice) : mp;
        Thread.startVirtualThread(() -> {
            try { futuresLiquidationService.checkOnPriceUpdate(symbol, mp, cp); }
            catch (Exception e) { log.warn("futures强平检查异常 {}: {}", symbol, e.getMessage()); }
        });
    }

    // ── REST兜底：WS断开期间切REST轮询保证价格不中断 ──

    private void startFallbackPolling() {
        if (fallbackTask != null && !fallbackTask.isCancelled()) return;
        long interval = props.getFallbackPollInterval();
        fallbackTask = scheduler.scheduleAtFixedRate(() -> {
            for (String symbol : props.getSymbols()) {
                try {
                    String json = restClient.getTickerPrice(symbol);
                    updatePriceFromJson(symbol, json);
                } catch (Exception e) {
                    log.warn("REST轮询{}失败: {}", symbol, e.getMessage());
                }
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
        log.info("启动REST轮询兜底，间隔{}ms", interval);
    }

    private void stopFallbackPolling() {
        if (fallbackTask != null) {
            fallbackTask.cancel(false);
            fallbackTask = null;
            log.info("停止REST轮询兜底");
        }
    }

    private void startFuturesFallbackPolling() {
        if (futuresFallbackTask != null && !futuresFallbackTask.isCancelled()) return;
        long interval = props.getFallbackPollInterval();
        futuresFallbackTask = scheduler.scheduleAtFixedRate(() -> {
            for (String symbol : props.getSymbols()) {
                try {
                    String json = restClient.getMarkPrice(symbol);
                    if (json == null) continue;
                    int idx = json.indexOf("\"markPrice\":\"");
                    if (idx < 0) continue;
                    String markPrice = extractQuoted(json, idx + 13);
                    onFuturesMessage("{\"s\":\"" + symbol + "\",\"p\":\"" + markPrice + "\"}");
                } catch (Exception e) {
                    log.warn("REST轮询Futures {}失败: {}", symbol, e.getMessage());
                }
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
        log.info("启动Futures REST轮询兜底，间隔{}ms", interval);
    }

    private void stopFuturesFallbackPolling() {
        if (futuresFallbackTask != null) {
            futuresFallbackTask.cancel(false);
            futuresFallbackTask = null;
            log.info("停止Futures REST轮询兜底");
        }
    }

    private void updatePriceFromJson(String symbol, String json) {
        int idx = json.indexOf("\"price\":\"");
        if (idx < 0) return;
        String price = extractQuoted(json, idx + 9);
        onSpotMessage("{\"s\":\"" + symbol + "\",\"c\":\"" + price + "\"}");
    }

    // ── 恢复 ──

    private void recoverMissedLimitOrders() {
        try {
            for (String symbol : props.getSymbols()) {
                BigDecimal[] lowHigh = restClient.getRecentHighLow(symbol);
                if (lowHigh != null) {
                    cryptoOrderService.recoverLimitOrders(symbol, lowHigh[0], lowHigh[1]);
                }
                BigDecimal[] markLowHigh = restClient.getRecentMarkPriceHighLow(symbol);
                if (markLowHigh != null) {
                    futuresSettlementService.recoverLimitOrders(symbol, markLowHigh[0], markLowHigh[1]);
                }
            }
        } catch (Exception e) {
            log.error("恢复限价单失败", e);
        }
    }

    private void recoverMissedLiquidations() {
        try {
            for (String symbol : props.getSymbols()) {
                BigDecimal[] lowHigh = restClient.getRecentMarkPriceHighLow(symbol);
                BigDecimal[] spotLowHigh = restClient.getRecentHighLow(symbol);
                if (lowHigh != null) {
                    BigDecimal spotLow = spotLowHigh != null ? spotLowHigh[0] : lowHigh[0];
                    BigDecimal spotHigh = spotLowHigh != null ? spotLowHigh[1] : lowHigh[1];
                    futuresLiquidationService.checkOnPriceUpdate(symbol, lowHigh[0], spotLow);
                    futuresLiquidationService.checkOnPriceUpdate(symbol, lowHigh[1], spotHigh);
                }
            }
        } catch (Exception e) {
            log.error("恢复强平检查失败", e);
        }
    }

    // ── 工具 ──

    private static String extractQuoted(String raw, int start) {
        int end = raw.indexOf('"', start);
        return raw.substring(start, end);
    }

}
