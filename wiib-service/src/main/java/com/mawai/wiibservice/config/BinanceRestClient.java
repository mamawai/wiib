package com.mawai.wiibservice.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;

@Slf4j
@Component
public class BinanceRestClient extends BaseRestTemplateConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final BinanceProperties props;

    public BinanceRestClient(BinanceProperties props) {
        this.props = props;
        this.restTemplate = createRestTemplate(5000, 10000);
    }

    /**
     * 拉取K线数据，直接返回Binance原始JSON字符串
     * @param symbol   交易对，如 BTCUSDT
     * @param interval K线间隔，如 1m, 5m, 1h
     * @param limit    数量，最大1000
     * @param endTime  截止时间戳(ms)，null则取最新
     */
    public String getKlines(String symbol, String interval, int limit, Long endTime) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(props.getRestBaseUrl() + "/api/v3/klines")
                .queryParam("symbol", symbol)
                .queryParam("interval", interval)
                .queryParam("limit", Math.min(limit, 1000));
        if (endTime != null) {
            builder.queryParam("endTime", endTime);
        }
        URI uri = builder.build().toUri();
        log.info("Binance REST klines: {}", uri);
        return restTemplate.getForObject(uri, String.class);
    }

    /**
     * 获取最新价格（WS断线兜底用）
     */
    public String getTickerPrice(String symbol) {
        URI uri = UriComponentsBuilder
                .fromUriString(props.getRestBaseUrl() + "/api/v3/ticker/price")
                .queryParam("symbol", symbol)
                .build().toUri();
        return restTemplate.getForObject(uri, String.class);
    }

    /**
     * 拉取最近1分钟 K线，返回 [periodLow, periodHigh]
     */
    public BigDecimal[] getRecentHighLow(String symbol) {
        try {
            String json = getKlines(symbol, "1s", 60, null);
            if (json == null || json.isBlank()) return null;
            JsonNode root = MAPPER.readTree(json);
            BigDecimal high = null, low = null;
            for (JsonNode kline : root) {
                BigDecimal h = new BigDecimal(kline.get(2).asText());
                BigDecimal l = new BigDecimal(kline.get(3).asText());
                if (high == null || h.compareTo(high) > 0) high = h;
                if (low == null || l.compareTo(low) < 0) low = l;
            }
            return high != null ? new BigDecimal[]{low, high} : null;
        } catch (Exception e) {
            log.error("解析klines高低价失败 symbol={}", symbol, e);
            return null;
        }
    }
}
