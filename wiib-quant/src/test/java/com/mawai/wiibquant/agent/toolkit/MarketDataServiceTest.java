package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import com.mawai.wiibquant.config.DeribitClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MarketDataServiceTest {

    private final BinanceRestClient binanceRestClient = mock(BinanceRestClient.class);
    private final ForceOrderService forceOrderService = mock(ForceOrderService.class);
    private final DepthStreamCache depthStreamCache = mock(DepthStreamCache.class);
    private final DeribitClient deribitClient = mock(DeribitClient.class);
    private final OrderFlowAggregator orderFlowAggregator = mock(OrderFlowAggregator.class);

    private MarketDataService service(long ttlMillis) {
        return new MarketDataService(binanceRestClient, forceOrderService, depthStreamCache,
                deribitClient, orderFlowAggregator, KlineInterval.M5, ttlMillis);
    }

    @Test
    void unavailableWhenCollectReturnsNothing() {
        // 全部 mock 默认返回 null → collect 判 data_available=false → 组装降级为 unavailable
        MarketAssembly assembly = service(60_000).assemble("BTCUSDT");

        assertThat(assembly.available()).isFalse();
        assertThat(assembly.snapshot()).isNull();
    }

    @Test
    void cachesAssemblyWithinTtl() {
        MarketDataService service = service(60_000);

        MarketAssembly first = service.assemble("BTCUSDT");
        MarketAssembly second = service.assemble("BTCUSDT");

        // TTL 内同一实例直接复用，不重复采集
        assertThat(second).isSameAs(first);
    }

    @Test
    void refreshesWhenTtlExpired() {
        MarketDataService service = service(0); // ttl=0 → 每次都过期

        MarketAssembly first = service.assemble("BTCUSDT");
        MarketAssembly second = service.assemble("BTCUSDT");

        assertThat(second).isNotSameAs(first);
    }

    @Test
    void normalizesSymbol() {
        MarketDataService service = service(60_000);

        MarketAssembly a = service.assemble(" btcusdt ");
        MarketAssembly b = service.assemble("BTCUSDT");

        assertThat(a.symbol()).isEqualTo("BTCUSDT");
        assertThat(b).isSameAs(a); // 归一化后命中同一缓存
    }

    /**
     * TTL 过期瞬间的并发击穿：无锁的 check-then-act 会让 N 个线程各打一整套采集
     * （一套 19 个 HTTP 请求）。single-flight 后只允许一个线程真采集，其余等它的结果。
     */
    @Test
    void 并发未命中时只真采集一次() throws Exception {
        MarketDataService service = service(60_000);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<MarketAssembly> results = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    results.add(service.assemble("BTCUSDT"));
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
            th.setDaemon(true);
            th.start();
        }
        start.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
        // 八个线程拿到的必须是同一个实例——说明只有一次真采集，其余复用
        assertThat(results).hasSize(threads);
        MarketAssembly first = results.getFirst();
        assertThat(results).allMatch(a -> a == first);
    }
}
