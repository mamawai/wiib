package com.mawai.wiibquant.research.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 标准正态 Φ 数值近似对拍已知值（DM 检验的 p 值靠它）。 */
class NormalDistributionTest {

    @Test
    void cdfMatchesKnownStandardNormalValues() {
        assertThat(NormalDistribution.cdf(0.0)).isCloseTo(0.5, within(1e-9));
        assertThat(NormalDistribution.cdf(1.0)).isCloseTo(0.8413447460685429, within(1e-6));
        assertThat(NormalDistribution.cdf(-1.0)).isCloseTo(0.15865525393145707, within(1e-6));
        assertThat(NormalDistribution.cdf(1.959963984540054)).isCloseTo(0.975, within(1e-6));   // 双侧95%
        assertThat(NormalDistribution.cdf(2.5758293035489004)).isCloseTo(0.995, within(1e-6));  // 双侧99%
    }

    @Test
    void cdfSaturatesAtTails() {
        assertThat(NormalDistribution.cdf(-40.0)).isCloseTo(0.0, within(1e-9));
        assertThat(NormalDistribution.cdf(40.0)).isCloseTo(1.0, within(1e-9));
    }
}
