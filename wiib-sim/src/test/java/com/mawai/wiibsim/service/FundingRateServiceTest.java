package com.mawai.wiibsim.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FundingRateServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** premiumIndex 全量返回几百个合约，只留我们订阅的那几个 */
    @Test
    void parseFundingRates_只留我们的合约标的() {
        String json = """
                [
                  {"symbol":"BTCUSDT","markPrice":"63000.0","lastFundingRate":"0.00010000"},
                  {"symbol":"ETHUSDT","markPrice":"2400.0","lastFundingRate":"-0.00023000"},
                  {"symbol":"PEPEUSDT","markPrice":"0.00001","lastFundingRate":"0.00050000"},
                  {"symbol":"XAUUSDT","markPrice":"3486.0","lastFundingRate":"0.00002500"}
                ]
                """;
        Map<String, BigDecimal> rates = FundingRateService.parseFundingRates(
                json, List.of("BTCUSDT", "ETHUSDT", "XAUUSDT", "SPCXUSDT"));

        assertThat(rates).containsOnlyKeys("BTCUSDT", "ETHUSDT", "XAUUSDT");
        assertThat(rates.get("BTCUSDT")).isEqualByComparingTo("0.0001");
        assertThat(rates.get("ETHUSDT")).isEqualByComparingTo("-0.00023");
        // 返回里没有的标的不会凭空出现
        assertThat(rates).doesNotContainKey("SPCXUSDT");
    }

    @Test
    void parseFundingRates_空响应与坏响应都给空表() {
        List<String> symbols = List.of("BTCUSDT");
        assertThat(FundingRateService.parseFundingRates(null, symbols)).isEmpty();
        assertThat(FundingRateService.parseFundingRates("", symbols)).isEmpty();
        assertThat(FundingRateService.parseFundingRates("{\"code\":-1121}", symbols)).isEmpty();
        // lastFundingRate 缺字段的条目跳过，不写 null 进表
        assertThat(FundingRateService.parseFundingRates("[{\"symbol\":\"BTCUSDT\"}]", symbols)).isEmpty();
    }

    @Test
    void nextFundingTime_落在下一个0_8_16点() {
        assertThat(next("2026-09-06T09:13:20")).isEqualTo(at("2026-09-06T16:00:00"));
        assertThat(next("2026-09-06T00:00:01")).isEqualTo(at("2026-09-06T08:00:00"));
        assertThat(next("2026-09-06T16:30:00")).isEqualTo(at("2026-09-07T00:00:00"));
        assertThat(next("2026-09-06T23:59:59")).isEqualTo(at("2026-09-07T00:00:00"));
    }

    /** 正卡在结算点上：这一轮刚扣完，下一个是 8 小时后 */
    @Test
    void nextFundingTime_整点当刻算下一个() {
        assertThat(next("2026-09-06T08:00:00")).isEqualTo(at("2026-09-06T16:00:00"));
    }

    private static long next(String localDateTime) {
        return FundingRateService.nextFundingTime(at(localDateTime), ZONE);
    }

    private static long at(String localDateTime) {
        return ZonedDateTime.parse(localDateTime + "+08:00[Asia/Shanghai]").toInstant().toEpochMilli();
    }
}
