package com.mawai.wiibservice.config;

import com.mawai.wiibservice.service.CryptoOrderService;
import com.mawai.wiibservice.service.impl.RedisMessageBroadcastService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Binance WebSocket客户端
 * 订阅 miniTicker 获取实时最新价，写Redis + STOMP推前端
 * 断线自动重连，重连期间REST轮询兜底
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceWsClient {

    private final BinanceProperties props;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageBroadcastService broadcastService;
    private final BinanceRestClient restClient;
    private final CryptoOrderService cryptoOrderService;

    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> fallbackTask;

    private static final int[] BACKOFF_SECONDS = {1, 2, 5, 10, 30};
    private static final String REDIS_KEY_PREFIX = "market:price:";

    @PostConstruct
    public void init() {
        if (props.getSymbols() == null || props.getSymbols().isEmpty()) {
            log.warn("binance.symbols未配置，跳过WS连接");
            return;
        }
        scheduler = Executors.newScheduledThreadPool(2,
                Thread.ofVirtual().name("binance-ws-", 0).factory());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        connect();
    }

    @PreDestroy
    public void destroy() {
        shutdown.set(true);
        stopFallbackPolling();
        WebSocket ws = wsRef.get();
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (httpClient != null) {
            httpClient.close();
        }
    }

    private void connect() {
        if (shutdown.get()) return;

        String streams = props.getSymbols().stream()
                .map(s -> s.toLowerCase() + "@miniTicker")
                .reduce((a, b) -> a + "/" + b)
                .orElse("");

        // 单流: /ws/stream  多流: /stream?streams=a/b
        String url;
        if (props.getSymbols().size() == 1) {
            url = props.getWsUrl() + "/" + streams;
        } else {
            url = props.getWsUrl().replace("/ws", "/stream?streams=" + streams);
        }
        log.info("连接Binance WS: {}", url);

        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), new WsListener())
                .thenAccept(ws -> {
                    wsRef.set(ws);
                    connected.set(true);
                    reconnecting.set(false);
                    reconnectAttempt.set(0);
                    stopFallbackPolling();
                    log.info("Binance WS已连接");
                    Thread.startVirtualThread(this::recoverMissedLimitOrders);
                })
                .exceptionally(ex -> {
                    log.error("Binance WS连接失败: {}", ex.getMessage());
                    reconnecting.set(false);
                    scheduleReconnect();
                    return null;
                });
    }

    private void scheduleReconnect() {
        if (shutdown.get()) return;
        // CAS闸门：防止onClose和onError重复触发
        if (!reconnecting.compareAndSet(false, true)) return;

        connected.set(false);
        startFallbackPolling();
        int attempt = reconnectAttempt.getAndIncrement();
        int delay = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
        log.info("{}秒后重连Binance WS（第{}次）", delay, attempt + 1);
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    /** WS断线期间，REST轮询兜底 */
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

    /**
     * 解析REST ticker响应并更新Redis+推送
     * {"symbol":"BTCUSDT","price":"68878.50000000"}
     */
    private void updatePriceFromJson(String symbol, String json) {
        int idx = json.indexOf("\"price\":\"");
        if (idx < 0) return;
        int start = idx + 9;
        int end = json.indexOf('"', start);
        if (end < 0) return;
        String price = json.substring(start, end);
        long ts = System.currentTimeMillis();
        writeRedisAndPush(symbol, price, ts);
    }

    /** 写Redis + STOMP广播 + 限价单检查 */
    private void writeRedisAndPush(String symbol, String price, long ts) {
        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + symbol, price);
        String msg = "{\"price\":\"" + price + "\",\"ts\":" + ts + "}";
        broadcastService.broadcastCryptoQuote(symbol, msg);
        Thread.startVirtualThread(() -> {
            try {
                cryptoOrderService.onPriceUpdate(symbol, new BigDecimal(price));
            } catch (Exception e) {
                log.warn("限价单检查异常 {}: {}", symbol, e.getMessage());
            }
        });
    }

    public boolean isConnected() {
        return connected.get();
    }

    /**
     * 恢复重启/短线后一分钟前的crypto限价单触发检测
     */
    private void recoverMissedLimitOrders() {
        try {
            for (String symbol : props.getSymbols()) {
                BigDecimal[] lowHigh = restClient.getRecentHighLow(symbol);
                if (lowHigh != null) {
                    cryptoOrderService.recoverLimitOrders(symbol, lowHigh[0], lowHigh[1]);
                }
            }
        } catch (Exception e) {
            log.error("恢复限价单失败", e);
        }
    }

    /** WS监听器 */
    private class WsListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("Binance WS onOpen");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                processMessage(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.sendPong(message);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("Binance WS关闭: code={} reason={}", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("Binance WS错误: {}", error.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * 解析miniTicker消息
     * <p>
     * {"e":"24hrMiniTicker","E":1700000000000,"s":"BTCUSDT","c":"68878.50",...}
     */
    private void processMessage(String raw) {
        try {
            int sIdx = raw.indexOf("\"s\":\"");
            if (sIdx < 0) return;
            int sStart = sIdx + 5;
            int sEnd = raw.indexOf('"', sStart);
            String symbol = raw.substring(sStart, sEnd);

            int cIdx = raw.indexOf("\"c\":\"");
            if (cIdx < 0) return;
            int cStart = cIdx + 5;
            int cEnd = raw.indexOf('"', cStart);
            String price = raw.substring(cStart, cEnd);

            int eIdx = raw.indexOf("\"E\":");
            long ts = System.currentTimeMillis();
            if (eIdx >= 0) {
                int eStart = eIdx + 4;
                int eEnd = raw.indexOf(',', eStart);
                if (eEnd < 0) eEnd = raw.indexOf('}', eStart);
                ts = Long.parseLong(raw.substring(eStart, eEnd).trim());
            }
            writeRedisAndPush(symbol, price, ts);
        } catch (Exception e) {
            log.warn("解析Binance WS消息失败: {}", e.getMessage());
        }
    }
}
