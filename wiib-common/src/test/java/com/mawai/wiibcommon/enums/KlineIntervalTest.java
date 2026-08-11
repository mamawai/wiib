package com.mawai.wiibcommon.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KlineIntervalTest {

    @Test
    void fromCodeReturnsMatchingInterval() {
        assertThat(KlineInterval.fromCode("1m")).isEqualTo(KlineInterval.M1);
        assertThat(KlineInterval.fromCode("5m")).isEqualTo(KlineInterval.M5);
        assertThat(KlineInterval.fromCode("15m")).isEqualTo(KlineInterval.M15);
        assertThat(KlineInterval.fromCode("1h")).isEqualTo(KlineInterval.H1);
    }

    @Test
    void fromCodeRejectsUnknownCode() {
        assertThatThrownBy(() -> KlineInterval.fromCode("4h"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知周期");
    }

    /**
     * 3m 不是合法的决策周期：采集链路不拉它，选中会让 atr/bollBw 静默变 null。
     * 这条钉住"枚举取值 ⊆ 采集周期表"这个约束，谁想加回来会先在这里红。
     */
    @Test
    void uncollectedIntervalIsNotSelectable() {
        assertThatThrownBy(() -> KlineInterval.fromCode("3m"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知周期");
    }
}
