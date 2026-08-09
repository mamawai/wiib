package com.mawai.wiibcommon.market;

import com.mawai.wiibcommon.config.BinanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流熔断：Binance 的 429 是"慢点"，不理它会升级成 418 直接封 IP（最长 3 天），
 * 而封的是整个进程的出口 IP——策略执行轨跟对话轨共用同一个客户端，一起瘫。
 */
class BinanceRestClientCircuitTest {

    private static BinanceProperties props() {
        BinanceProperties p = new BinanceProperties();
        p.setRestBaseUrl("http://localhost:1");
        p.setFuturesRestBaseUrl("http://localhost:1");
        return p;
    }

    /** 撞到 429 之后，冷却期内不许再发请求——继续打只会把 429 催成 418 */
    @Test
    void 撞到429后冷却期内不再发请求() {
        AtomicInteger calls = new AtomicInteger();
        BinanceRestClient client = new BinanceRestClient(props()) {
            @Override
            protected String get(String uri) {
                calls.incrementAndGet();
                throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", null, null, null);
            }
        };

        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();
        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();
        assertThat(client.getFutures24hTicker("BTCUSDT")).isNull();

        // 只有第一次真打出去，后两次被熔断直接短路
        assertThat(calls.get()).isEqualTo(1);
    }

    /** 冷却期过后要自动恢复——熔断是暂停不是永久关闭 */
    @Test
    void 冷却期过后恢复请求() {
        AtomicInteger calls = new AtomicInteger();
        BinanceRestClient client = new BinanceRestClient(props()) {
            @Override
            protected String get(String uri) {
                if (calls.incrementAndGet() == 1) {
                    throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                            "Too Many Requests", null, null, null);
                }
                return "[]";
            }
        };
        client.nowMs = fixedClock(0L);

        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();
        client.nowMs = fixedClock(BinanceRestClient.COOLDOWN_MS + 1);

        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isEqualTo("[]");
        assertThat(calls.get()).isEqualTo(2);
    }

    /** 普通 4xx（比如参数错）不该触发熔断，否则一个笔误就把全局行情停了 */
    @Test
    void 普通4xx不触发熔断() {
        AtomicInteger calls = new AtomicInteger();
        BinanceRestClient client = new BinanceRestClient(props()) {
            @Override
            protected String get(String uri) {
                calls.incrementAndGet();
                throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST,
                        "Bad Request", null, null, null);
            }
        };

        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();
        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();

        assertThat(calls.get()).isEqualTo(2);
    }

    private static java.util.function.LongSupplier fixedClock(long value) {
        return () -> value;
    }
}
