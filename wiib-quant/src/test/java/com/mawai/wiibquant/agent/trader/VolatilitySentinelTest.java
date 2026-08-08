package com.mawai.wiibquant.agent.trader;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 哨兵探测层的纯逻辑：滚动窗口振幅/方向/过期剔除 + 生效阈值（基准×系数，只能调高）。 */
class VolatilitySentinelTest {

    @Test
    void windowAmplitudeAndDirection() {
        VolatilitySentinel.PriceWindow w = new VolatilitySentinel.PriceWindow();
        long t0 = 1_786_200_000_000L;
        w.add(t0, new BigDecimal("100000"));
        w.add(t0 + 60_000, new BigDecimal("100600"));
        w.add(t0 + 120_000, new BigDecimal("99800"));

        // (100600-99800)/99800 = 0.8016%
        assertThat(w.amplitudePct()).isEqualByComparingTo("0.8016");
        assertThat(w.direction()).isEqualTo("下跌"); // 现价 99800 < 窗口首价 100000
    }

    /** 超过 5 分钟的 tick 剔除出窗：旧尖峰不许一直撑着振幅 */
    @Test
    void ticksOlderThanWindowEvicted() {
        VolatilitySentinel.PriceWindow w = new VolatilitySentinel.PriceWindow();
        long t0 = 1_786_200_000_000L;
        w.add(t0, new BigDecimal("99000"));                                  // 将过期的低点
        w.add(t0 + VolatilitySentinel.WINDOW_MS + 1_000, new BigDecimal("100000"));
        w.add(t0 + VolatilitySentinel.WINDOW_MS + 2_000, new BigDecimal("100100"));

        // 99000 已出窗：振幅只剩 (100100-100000)/100000 = 0.1%
        assertThat(w.amplitudePct()).isEqualByComparingTo("0.1");
    }

    @Test
    void singleTickWindowIsZero() {
        VolatilitySentinel.PriceWindow w = new VolatilitySentinel.PriceWindow();
        w.add(1_786_200_000_000L, new BigDecimal("100000"));

        assertThat(w.amplitudePct()).isEqualByComparingTo("0");
        assertThat(w.direction()).isEqualTo("波动");
    }

    /** 生效阈值 = 币基准 × 系数；系数 null/低于 1 回落 1.0（只能调高的兜底） */
    @Test
    void effectiveThresholdOnlyRaisable() {
        assertThat(VolatilitySentinel.effectiveThreshold("BTCUSDT", null))
                .isEqualByComparingTo("0.6");
        assertThat(VolatilitySentinel.effectiveThreshold("BTCUSDT", new BigDecimal("2")))
                .isEqualByComparingTo("1.2");
        assertThat(VolatilitySentinel.effectiveThreshold("BTCUSDT", new BigDecimal("0.5")))
                .isEqualByComparingTo("0.6");
        assertThat(VolatilitySentinel.effectiveThreshold("DOGEUSDT", null))
                .isEqualByComparingTo("1.0");
    }
}
