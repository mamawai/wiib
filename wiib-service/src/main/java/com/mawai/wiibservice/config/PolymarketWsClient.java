package com.mawai.wiibservice.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.PredictionService;
import com.mawai.wiibservice.service.impl.RedisMessageBroadcastService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polymarket WS 客户端
 * <p>
 * 1. live-data WS: Chainlink BTC价格 + Polymarket真实交易动态(orders_matched)<br/>
 * 2. CLOB WS: UP/DOWN 实时盘口价格(price_change)
 * <p>
 * 我们透传这些真实数据到前端展示，模拟交易按 Polymarket 实时价格成交。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolymarketWsClient implements SmartLifecycle {

    private final RedisMessageBroadcastService broadcastService;
    private final CacheService cacheService;
    private final PredictionService predictionService;

    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private WsConnection liveDataWs;
    private WsConnection clobWs;

    // 当前回合
    private volatile String lastSubscribedSlug;
    private volatile String currentUpAssetId;
    private volatile String currentDownAssetId;

    private static final String LIVE_DATA_URL = "wss://ws-live-data.polymarket.com/";
    private static final String CLOB_URL = "wss://ws-subscriptions-frontend-clob.polymarket.com/ws/market";
    private static final int WINDOW_SECONDS = 300;
    private static final String CHAINLINK_REDIS_KEY = "chainlink:price:btcusd";

    @PostConstruct
    public void init() {
        scheduler = Executors.newScheduledThreadPool(2,
                Thread.ofVirtual().name("polymarket-ws-", 0).factory());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        liveDataWs = new WsConnection("LiveData", () -> LIVE_DATA_URL, this::onLiveDataMessage,
                this::onLiveDataConnected, null, httpClient, scheduler, shutdown);

        clobWs = new WsConnection("CLOB", () -> CLOB_URL, this::onClobMessage,
                this::onClobConnected, null, httpClient, scheduler, shutdown);

        liveDataWs.connect();
        scheduler.scheduleAtFixedRate(this::checkRoundRotation, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        shutdown.set(true);
        if (liveDataWs != null) liveDataWs.close();
        if (clobWs != null) clobWs.close();
        if (scheduler != null) scheduler.shutdownNow();
        if (httpClient != null) httpClient.close();
    }

    @Override public boolean isRunning() { return !shutdown.get() && scheduler != null; }
    @Override public int getPhase() { return 1; }
    @Override public void start() { /* init via @PostConstruct */ }

    // ==================== WS 连接/重新连接 ============

    private void onLiveDataConnected(WebSocket ws) {
        String slug = eventSlug(currentWindowStart());
        lastSubscribedSlug = slug;
        ws.sendText(buildFullSubscribeMsg(slug), true);
        log.info("已发送订阅: chainlink + activity({})", slug);
    }

    private void onClobConnected(WebSocket ws) {
        String upId = currentUpAssetId;
        if (upId == null) { clobWs.close(); return; }
        String msg = "{\"assets_ids\":[\"" + upId + "\"],\"type\":\"markets\"}";
        ws.sendText(msg, true);
        log.info("已订阅CLOB market: upAssetId={}", upId);
    }

    // ==================== 回合轮换 ====================

    private static long currentWindowStart() {
        long now = Instant.now().getEpochSecond();
        return now - (now % WINDOW_SECONDS);
    }

    private static String eventSlug(long windowStart) {
        return "btc-updown-5m-" + windowStart;
    }

    private void checkRoundRotation() {
        try {
            long windowStart = currentWindowStart();
            String newSlug = eventSlug(windowStart);
            if (newSlug.equals(lastSubscribedSlug)) return;

            String oldSlug = lastSubscribedSlug;
            lastSubscribedSlug = newSlug;
            long prevWindowStart = windowStart - WINDOW_SECONDS;
            // 不清空currentUpAssetId：旧在途activity匹配旧值不会触发重连，
            // 新round的activity带不同assetId才会更新并连接CLOB
            currentDownAssetId = null;

            // 立即锁定上一轮 OPEN→LOCKED，禁止sell
            predictionService.lockRound(prevWindowStart);

            // 取消订阅旧slug，订阅新slug
            WebSocket ws = liveDataWs.ws();
            if (ws != null && liveDataWs.isConnected()) {
                if (oldSlug != null) {
                    ws.sendText(buildActivityUnsubscribeMsg(oldSlug), true);
                }
                ws.sendText(buildActivitySubscribeMsg(newSlug), true);
                log.info("订阅新回合activity: {}", newSlug);
            }

            // 关闭旧的 CLOB WS，清除旧盘口价格并通知前端
            clobWs.close();
            cacheService.clearPredictionPrices();
            broadcastPriceUpdate();

            // 创建新回合（startPrice可能为null，等openPrice回填）
            predictionService.createNewRound();

            // 独立线程轮询openPrice: 每5s查一次，最多12次，有数据即停
            Thread.startVirtualThread(() -> pollOpenPrice(windowStart));
            // 独立线程轮询closePrice: 60s后开始，每10s查一次，最多6次，有数据即停
            Thread.startVirtualThread(() -> pollClosePrice(prevWindowStart));
        } catch (Exception e) {
            log.warn("回合轮换检查失败", e);
        }
    }

    private static final String CRYPTO_PRICE_API = "https://polymarket.com/api/crypto/crypto-price";

    private JSONObject fetchCryptoPrice(long windowStart) {
        try {
            Instant start = Instant.ofEpochSecond(windowStart);
            Instant end = start.plusSeconds(WINDOW_SECONDS);
            URI uri = URI.create(CRYPTO_PRICE_API + "?symbol=BTC&variant=fiveminute"
                    + "&eventStartTime=" + start + "&endDate=" + end);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            return JSON.parseObject(resp.body());
        } catch (Exception e) {
            log.warn("Polymarket API调用失败: windowStart={}, err={}", windowStart, e.getMessage());
            return null;
        }
    }

    private void pollOpenPrice(long windowStart) {
        for (int i = 0; i < 12; i++) {
            if (shutdown.get()) return;
            try { Thread.sleep(5_000); } catch (InterruptedException e) { return; }
            if (cacheService.getPolymarketOpenPrice(windowStart) != null) {
                log.info("openPrice已存在,跳过: windowStart={}", windowStart);
                return;
            }
            JSONObject json = fetchCryptoPrice(windowStart);
            if (json == null) continue;
            BigDecimal openPrice = json.getBigDecimal("openPrice");
            if (openPrice != null) {
                cacheService.putPolymarketOpenPrice(windowStart, openPrice);
                predictionService.syncOpenPrice();
                log.info("openPrice获取成功: windowStart={}, price={}, 第{}次", windowStart, openPrice, i + 1);
                return;
            }
        }
        log.warn("openPrice轮询12次均未获取到: windowStart={}", windowStart);
    }

    private void pollClosePrice(long prevWindowStart) {
        try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
        for (int i = 0; i < 6; i++) {
            if (shutdown.get()) return;
            if (i > 0) {
                try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }
            }
            if (cacheService.getPolymarketClosePrice(prevWindowStart) != null) {
                log.info("closePrice已存在,跳过: windowStart={}", prevWindowStart);
                return;
            }
            JSONObject json = fetchCryptoPrice(prevWindowStart);
            if (json == null) continue;
            if (!Boolean.TRUE.equals(json.getBoolean("completed"))) continue;
            BigDecimal closePrice = json.getBigDecimal("closePrice");
            if (closePrice != null) {
                cacheService.putPolymarketClosePrice(prevWindowStart, closePrice);
                predictionService.settlePreviousRound();
                log.info("closePrice获取并结算成功: windowStart={}, price={}, 第{}次", prevWindowStart, closePrice, i + 1);
                return;
            }
        }
        log.warn("closePrice轮询6次均未获取到: windowStart={}", prevWindowStart);
    }

    // ==================== live-data 消息处理 ====================

    private void onLiveDataMessage(String raw) {
        try {
            JSONObject msg = JSON.parseObject(raw);
            if (msg == null) return;
            String topic = msg.getString("topic");
            if (topic == null) return;

            if ("crypto_prices_chainlink".equals(topic)) {
                onChainlinkPrice(msg);
            } else if ("activity".equals(topic)) {
                onActivity(msg);
            }
        } catch (Exception e) {
            log.warn("解析live-data消息失败: {}", e.getMessage());
        }
    }

    private void onChainlinkPrice(JSONObject msg) {
        JSONObject payload = msg.getJSONObject("payload");
        if (payload == null) return;

        BigDecimal value = payload.getBigDecimal("value");
        if (value == null) return;

        long now = System.currentTimeMillis();
        String priceStr = value.toPlainString();
        // redis
        cacheService.set(CHAINLINK_REDIS_KEY, priceStr);
        // caffeine
        cacheService.putChainlinkPrice("btcusd", value);
        cacheService.addBtcPricePoint(now, value);

        String json = "{\"price\":\"" + priceStr + "\",\"ts\":" + now + "}";
        broadcastService.broadcastPrediction("price", json);
    }

    private void onActivity(JSONObject msg) {
        JSONObject payload = msg.getJSONObject("payload");
        if (payload == null) return;

        String outcome = payload.getString("outcome");
        String assetId = payload.getString("asset");
        BigDecimal price = payload.getBigDecimal("price");

        // 从 activity 发现 assetId，触发 CLOB 连接
        // 轮换后3秒内跳过，防止旧订阅在途消息写入过期assetId
        String tradeSide = payload.getString("side");
        if (outcome != null && assetId != null) {
            if ("Up".equals(outcome)) {
                if (!assetId.equals(currentUpAssetId)) {
                    currentUpAssetId = assetId;
                    maybeConnectClobWs();
                }
            } else if ("Down".equals(outcome)) {
                if (!assetId.equals(currentDownAssetId)) {
                    currentDownAssetId = assetId;
                }
            }
        }

        // 广播到前端（只需 outcome + amount）
        BigDecimal size = payload.getBigDecimal("size");
        BigDecimal amount = (price != null && size != null) ? price.multiply(size) : null;
        Long ts = payload.getLong("timestamp");

        String json = "{\"outcome\":\"" + escape(outcome)
                + "\",\"side\":\"" + escape(tradeSide)
                + "\",\"amount\":" + (amount != null ? amount.setScale(2, RoundingMode.HALF_UP) : 0)
                + ",\"ts\":" + (ts != null ? ts * 1000 : System.currentTimeMillis()) + "}";
        broadcastService.broadcastPrediction("activity", json);
    }

    // ==================== CLOB WS ====================

    private void maybeConnectClobWs() {
        if (currentUpAssetId == null || clobWs.isConnected()) return;
        clobWs.connect();
    }

    private void onClobMessage(String raw) {
        try {
            if (raw.startsWith("[") || !raw.contains("price_change")) return;
            onPriceChange(JSON.parseObject(raw));
        } catch (Exception e) {
            log.warn("解析CLOB消息失败: {}", e.getMessage());
        }
    }

    private void onPriceChange(JSONObject msg) {
        JSONArray changes = msg.getJSONArray("price_changes");
        if (changes == null) return;

        for (int i = 0; i < changes.size(); i++) {
            JSONObject change = changes.getJSONObject(i);
            String assetId = change.getString("asset_id");
            if (assetId == null) continue;

            String side;
            if (assetId.equals(currentUpAssetId)) {
                side = "UP";
            } else if (assetId.equals(currentDownAssetId)) {
                side = "DOWN";
            } else {
                if (currentDownAssetId == null && !assetId.equals(currentUpAssetId)) {
                    currentDownAssetId = assetId;
                    side = "DOWN";
                } else {
                    continue;
                }
            }

            BigDecimal bestBid = change.getBigDecimal("best_bid");
            BigDecimal bestAsk = change.getBigDecimal("best_ask");
            if (bestBid != null) cacheService.putPredictionBid(side, bestBid);
            if (bestAsk != null) cacheService.putPredictionAsk(side, bestAsk);
        }

        broadcastPriceUpdate();
    }

    private void broadcastPriceUpdate() {
        BigDecimal upBid = cacheService.getPredictionBid("UP");
        BigDecimal upAsk = cacheService.getPredictionAsk("UP");
        BigDecimal downBid = cacheService.getPredictionBid("DOWN");
        BigDecimal downAsk = cacheService.getPredictionAsk("DOWN");
        String json = "{\"upBid\":" + (upBid != null ? "\"" + upBid + "\"" : "null")
                + ",\"upAsk\":" + (upAsk != null ? "\"" + upAsk + "\"" : "null")
                + ",\"downBid\":" + (downBid != null ? "\"" + downBid + "\"" : "null")
                + ",\"downAsk\":" + (downAsk != null ? "\"" + downAsk + "\"" : "null")
                + ",\"ts\":" + System.currentTimeMillis() + "}";
        broadcastService.broadcastPrediction("market", json);
    }

    // ==================== 订阅消息构建 ====================

    private static String buildFullSubscribeMsg(String eventSlug) {
        return "{\"action\":\"subscribe\",\"subscriptions\":["
                + "{\"topic\":\"activity\",\"type\":\"orders_matched\","
                + "\"filters\":\"{\\\"event_slug\\\":\\\"" + eventSlug + "\\\"}\"}"
                + ",{\"topic\":\"crypto_prices_chainlink\",\"type\":\"update\","
                + "\"filters\":\"{\\\"symbol\\\":\\\"btc/usd\\\"}\"}"
                + "]}";
    }

    private static String buildActivitySubscribeMsg(String eventSlug) {
        return "{\"action\":\"subscribe\",\"subscriptions\":["
                + "{\"topic\":\"activity\",\"type\":\"orders_matched\","
                + "\"filters\":\"{\\\"event_slug\\\":\\\"" + eventSlug + "\\\"}\"}"
                + "]}";
    }

    private static String buildActivityUnsubscribeMsg(String eventSlug) {
        return "{\"action\":\"unsubscribe\",\"subscriptions\":["
                + "{\"topic\":\"activity\",\"type\":\"orders_matched\","
                + "\"filters\":\"{\\\"event_slug\\\":\\\"" + eventSlug + "\\\"}\"}"
                + "]}";
    }

    // ==================== 工具 ====================

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
