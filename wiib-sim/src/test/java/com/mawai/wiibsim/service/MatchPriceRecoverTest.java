package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * liq-recover 补漏事件的全仓语义：断连空窗里的插针藏在区间高低两端，
 * 低端抓多头重的账户、高端抓空头重的（equity 对单 symbol 价格是线性的，端点即最坏），一端都不能少。
 */
class MatchPriceRecoverTest {

    @Test
    void liq_recover_全仓高低两端都触发() {
        var crossLiq = mock(CrossLiquidationService.class);
        var consumer = new MatchPriceConsumer(mock(RedisMessageListenerContainer.class),
                mock(StringRedisTemplate.class), mock(CryptoOrderService.class),
                mock(FuturesLiquidationService.class), mock(FuturesSettlementService.class),
                crossLiq, mock(BinanceRestClient.class), mock(BinanceProperties.class));

        String body = """
                {"symbol":"BTCUSDT","type":"liq-recover","markLow":"48000","markHigh":"52000",\
                "futLow":"47900","futHigh":"52100"}""";
        consumer.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), null);

        verify(crossLiq, timeout(2000)).onPriceTick("BTCUSDT", new BigDecimal("48000"));
        verify(crossLiq, timeout(2000)).onPriceTick("BTCUSDT", new BigDecimal("52000"));
    }
}
