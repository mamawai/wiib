package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

/**
 * K线代理的 Redis 缓存层：多人同刷/切周期不再放大到 Binance（权重限频、418 封 IP 是全站行情单点风险）。
 * <p>一致性依据：已闭合 bar 不可变，历史翻页（带 endTime）可长缓存；最新页只有最后一根会变，
 * 而图表最后一根由 WS 流实时驱动、REST 仅作进页快照，短 TTL 的滞后会被 WS 首帧立即覆盖。
 * <p>Redis 故障直接穿透打 Binance，不影响可用性；非 JSON 数组的错误响应不进缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KlineCacheService {

    /** 最新页（不带 endTime）：只保"同一时刻大家看同一份"，滞后由 WS 兜底 */
    private static final Duration LATEST_TTL = Duration.ofSeconds(10);
    /** 历史翻页（带 endTime）：闭合 bar 不可变，1h 纯为控内存 */
    private static final Duration HISTORY_TTL = Duration.ofHours(1);
    /**
     * limit 上限。三个 controller 都把用户传的 limit 原样透传币安，不夹住的话
     * limit=100000 会让请求权重从 2 跳到 10，返回体还会被塞进 Redis 挂一小时。
     * 币安上限现货 1000、合约 1500，前端最多要 500 —— 统一收到 1000。
     */
    private static final int MAX_LIMIT = 1000;
    /** 币安认的周期。乱传的 symbol/interval 币安回错误，错误不进缓存，每次都会回源，所以先挡在这 */
    private static final Set<String> INTERVALS = Set.of(
            "1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M");

    private final BinanceRestClient binanceRestClient;
    private final StringRedisTemplate redisTemplate;
    private final TradeFilterRegistry tradeFilterRegistry;
    private final BStockService bStockService;

    /** 现货K线（crypto 现货 / bStock 共用）：只认上架的现货币种和 bStock */
    public String spotKlines(String symbol, String interval, int limit, Long endTime) {
        String s = symbol.toUpperCase();
        check(interval, tradeFilterRegistry.allSpot().containsKey(s) || bStockService.isBStockSymbol(s));
        // 先夹再建闭包：loader 捕获的是这个变量，夹在 cached() 里面对回源无效
        int n = clamp(limit);
        return cached("spot", s, interval, n, endTime,
                () -> binanceRestClient.getKlinesLight(s, interval, n, endTime));
    }

    /** 合约K线：只认上架的合约币种 */
    public String futuresKlines(String symbol, String interval, int limit, Long endTime) {
        String s = symbol.toUpperCase();
        check(interval, tradeFilterRegistry.allFutures().containsKey(s));
        int n = clamp(limit);
        return cached("fut", s, interval, n, endTime,
                () -> binanceRestClient.getFuturesKlinesLight(s, interval, n, endTime));
    }

    private static void check(String interval, boolean symbolKnown) {
        if (!symbolKnown || !INTERVALS.contains(interval)) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    private String cached(String market, String symbol, String interval, int limit, Long endTime, Supplier<String> loader) {
        String key = "kline:" + market + ":" + symbol + ":" + interval + ":" + limit
                + ":" + (endTime == null ? "latest" : endTime);
        try {
            String hit = redisTemplate.opsForValue().get(key);
            if (hit != null) return hit;
        } catch (Exception e) {
            log.warn("[KlineCache] Redis 读失败，穿透直连 key={}: {}", key, e.getMessage());
        }
        String fresh = loader.get();
        if (fresh != null && fresh.startsWith("[")) {
            try {
                redisTemplate.opsForValue().set(key, fresh, endTime == null ? LATEST_TTL : HISTORY_TTL);
            } catch (Exception e) {
                log.warn("[KlineCache] Redis 写失败 key={}: {}", key, e.getMessage());
            }
        }
        return fresh;
    }
}
