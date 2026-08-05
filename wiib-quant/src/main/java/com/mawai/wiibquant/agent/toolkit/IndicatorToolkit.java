package com.mawai.wiibquant.agent.toolkit;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibquant.agent.tool.CryptoIndicatorCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * K线与技术指标工具（AI Trader 数据面核心）：原始 OHLCV 是 AI 的眼睛，指标由
 * {@link CryptoIndicatorCalculator} 全套现算。interval 由 AI 自选——交易 15m 的 trader
 * 可以主动查 1h/4h 做多周期确认。数据直连 Binance 期货 REST（免费、任意 symbol），60s 缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndicatorToolkit {

    private static final Set<String> INTERVALS = Set.of("5m", "15m", "1h", "4h", "1d");
    private static final int MAX_KLINES = 100;
    /** 指标计算取 120 根：MA99 等长周期指标要求样本 ≥99，留余量 */
    private static final int INDICATOR_BARS = 120;
    private static final long CACHE_TTL_MS = 60_000;

    private record CacheVal(long at, String value) {
    }

    private final BinanceRestClient binanceRestClient;
    private final Map<String, CacheVal> cache = new ConcurrentHashMap<>();

    @Tool(name = "klines", description = """
            Get raw OHLCV candlesticks for a crypto perpetual symbol from Binance futures.
            Returns rows [openTime(ms), open, high, low, close, volume], oldest first;
            the last row is the current still-forming candle.
            interval: 5m/15m/1h/4h/1d. Use higher intervals for multi-timeframe context.""")
    public String klines(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                         @ToolParam(description = "Interval: 5m/15m/1h/4h/1d") String interval,
                         @ToolParam(description = "Number of candles, max 100") int limit) {
        String err = validateInterval(interval);
        if (err != null) {
            return error(err);
        }
        int n = Math.clamp(limit, 1, MAX_KLINES);
        String sym = QuantConstants.normalizeSymbolLenient(symbol);
        return cached("klines:" + sym + ":" + interval + ":" + n,
                () -> toCompactRows(binanceRestClient.getFuturesKlines(sym, interval, n, null)));
    }

    @Tool(name = "indicators", description = """
            Get the full classic technical indicator set for a crypto perpetual symbol, computed
            on the requested interval (5m/15m/1h/4h/1d) over the last 120 candles. Fields:
            ma7/ma25/ma99 + ema12/ema20/ema26 (moving averages; ma_alignment=bullish/bearish stacking),
            rsi14 + rsi14_trend (>70 overbought, <30 oversold),
            macd_dif/dea/hist + macd_cross(golden/death) + macd_hist_trend (momentum),
            boll_upper/mid/lower + boll_pb + boll_bandwidth (bandwidth shrinking = squeeze, expanding = trending),
            atr14 (volatility; common stop-distance unit),
            kdj_k/d/j, adx + plus_di/minus_di (adx>25 trending, <15 ranging),
            obv/obv_ma20/obv_trend + volume_ma20/volume_ratio (volume confirmation),
            close_trend (rising_5 = 5 consecutive up closes). The last candle is still forming.""")
    public String indicators(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                             @ToolParam(description = "Interval: 5m/15m/1h/4h/1d") String interval) {
        String err = validateInterval(interval);
        if (err != null) {
            return error(err);
        }
        String sym = QuantConstants.normalizeSymbolLenient(symbol);
        return cached("indicators:" + sym + ":" + interval, () -> {
            List<BigDecimal[]> bars = parseKlines(binanceRestClient.getFuturesKlines(sym, interval, INDICATOR_BARS, null));
            // 末根仍在跳动（实盘快照语义），calcAll 内部按 lastClosed=false 处理
            Map<String, Object> out = CryptoIndicatorCalculator.calcAll(bars, false);
            return JSON.toJSONString(out);
        });
    }

    /** Binance 数组 JSON → calcAll 契约行 [high, low, close, volume]；解析失败返回空表（下游报数据不足）。 */
    static List<BigDecimal[]> parseKlines(String json) {
        List<BigDecimal[]> rows = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return rows;
        }
        try {
            JSONArray arr = JSON.parseArray(json);
            for (int i = 0; i < arr.size(); i++) {
                JSONArray k = arr.getJSONArray(i);
                rows.add(new BigDecimal[]{
                        new BigDecimal(k.getString(2)),   // high
                        new BigDecimal(k.getString(3)),   // low
                        new BigDecimal(k.getString(4)),   // close
                        new BigDecimal(k.getString(5))}); // volume
            }
        } catch (Exception e) {
            log.warn("[Indicator] K线解析失败 msg={}", e.getMessage());
            return List.of();
        }
        return rows;
    }

    /** Binance 数组 JSON → 紧凑行 [openTime,open,high,low,close,volume]（数值不带引号省 token）。 */
    static String toCompactRows(String json) {
        JSONArray arr = JSON.parseArray(json);
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.size(); i++) {
            JSONArray k = arr.getJSONArray(i);
            JSONArray row = new JSONArray();
            row.add(k.getLong(0));
            for (int c = 1; c <= 5; c++) {
                // stripTrailingZeros 会产生 1E+2 科学计数法，过一遍 toPlainString 恢复普通标度
                row.add(new BigDecimal(new BigDecimal(k.getString(c)).stripTrailingZeros().toPlainString()));
            }
            out.add(row);
        }
        return out.toJSONString();
    }

    /** 返回 null=合法；否则给模型看的错误说明。 */
    static String validateInterval(String interval) {
        return interval != null && INTERVALS.contains(interval) ? null
                : "invalid interval, allowed: 5m/15m/1h/4h/1d";
    }

    private String cached(String key, java.util.function.Supplier<String> loader) {
        CacheVal hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at() < CACHE_TTL_MS) {
            return hit.value();
        }
        try {
            String value = loader.get();
            cache.put(key, new CacheVal(now, value));
            return value;
        } catch (Exception e) {
            log.warn("[Indicator] 数据获取失败 key={} msg={}", key, e.getMessage());
            return error("data unavailable: " + e.getMessage());
        }
    }

    private static String error(String reason) {
        JSONObject o = new JSONObject();
        o.put("available", false);
        o.put("reason", reason);
        return o.toJSONString();
    }
}
