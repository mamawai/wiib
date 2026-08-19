package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补漏两件事：
 * 1) liq-recover 事件的全仓语义——断连空窗里的插针藏在区间高低两端，
 * 低端抓多头重的账户、高端抓空头重的（equity 对单 symbol 价格是线性的，端点即最坏），一端都不能少；
 * 2) sim 启动补漏的窗口选择——有 last-tick 就按真实停机时长回看，没有才退回固定短窗。
 */
class MatchPriceRecoverTest {

    private static final String LAST_TICK_KEY = "sim:match:last-tick-ms";

    private final CryptoOrderService cryptoOrderService = mock(CryptoOrderService.class);
    private final FuturesLiquidationService liquidationService = mock(FuturesLiquidationService.class);
    private final FuturesSettlementService settlementService = mock(FuturesSettlementService.class);
    private final CrossLiquidationService crossLiq = mock(CrossLiquidationService.class);
    private final BinanceRestClient restClient = mock(BinanceRestClient.class);
    private final BinanceProperties props = mock(BinanceProperties.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private MatchPriceConsumer consumer() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        return new MatchPriceConsumer(mock(RedisMessageListenerContainer.class), redisTemplate,
                cryptoOrderService, liquidationService, settlementService, crossLiq, restClient, props);
    }

    @Test
    void liq_recover_全仓高低两端都触发() {
        String body = """
                {"symbol":"BTCUSDT","type":"liq-recover","markLow":"48000","markHigh":"52000",\
                "futLow":"47900","futHigh":"52100"}""";
        consumer().onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), null);

        verify(crossLiq, timeout(2000)).onPriceTick("BTCUSDT", new BigDecimal("48000"));
        verify(crossLiq, timeout(2000)).onPriceTick("BTCUSDT", new BigDecimal("52000"));
    }

    @Test
    void 启动补漏_有last_tick按停机时长回看K线() {
        when(props.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(props.getAllFuturesSymbols()).thenReturn(List.of("BTCUSDT"));
        when(valueOps.get(LAST_TICK_KEY)).thenReturn(String.valueOf(System.currentTimeMillis() - 3 * 60_000L));

        consumer().init();

        // 停机 3 分钟 + 2 根对齐缓冲；卡在整分边界上会多算一根，故 5/6 都算对
        verify(restClient, timeout(2000)).getSpotHighLowByMinutes(eq("BTCUSDT"), intThat(m -> m == 5 || m == 6));
        verify(restClient, timeout(2000)).getFuturesHighLowByMinutes(eq("BTCUSDT"), intThat(m -> m == 5 || m == 6));
        verify(restClient, timeout(2000)).getMarkPriceHighLowByMinutes(eq("BTCUSDT"), intThat(m -> m == 5 || m == 6));
        verify(restClient, never()).getRecentHighLow(any());
        verify(restClient, never()).getRecentFuturesHighLow(any());
    }

    @Test
    void 启动补漏_无last_tick退回固定短窗() {
        when(props.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(props.getAllFuturesSymbols()).thenReturn(List.of("BTCUSDT"));
        when(valueOps.get(LAST_TICK_KEY)).thenReturn(null);

        consumer().init();

        verify(restClient, timeout(2000)).getRecentHighLow("BTCUSDT");
        verify(restClient, timeout(2000)).getRecentFuturesHighLow("BTCUSDT");
        verify(restClient, timeout(2000)).getRecentMarkPriceHighLow("BTCUSDT");
        verify(restClient, never()).getSpotHighLowByMinutes(any(), anyInt());
        verify(restClient, never()).getFuturesHighLowByMinutes(any(), anyInt());
    }
}
