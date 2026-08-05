package com.mawai.wiibquant.agent.toolkit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndicatorToolkitTest {

    @Test
    void parseBinanceKlinesToCalcInput() {
        // Binance 数组格式：[openTime,open,high,low,close,volume,closeTime,...]
        String json = "[[1720000000000,\"100\",\"110\",\"95\",\"105\",\"1000\",1720000299999,\"0\",0,\"0\",\"0\",\"0\"]]";

        List<BigDecimal[]> bars = IndicatorToolkit.parseKlines(json);

        assertThat(bars).hasSize(1);
        // calcAll 契约：每行 [high, low, close, volume]
        assertThat(bars.get(0)[0]).isEqualByComparingTo("110");
        assertThat(bars.get(0)[1]).isEqualByComparingTo("95");
        assertThat(bars.get(0)[2]).isEqualByComparingTo("105");
        assertThat(bars.get(0)[3]).isEqualByComparingTo("1000");
    }

    @Test
    void parseGarbageReturnsEmpty() {
        assertThat(IndicatorToolkit.parseKlines("not json")).isEmpty();
        assertThat(IndicatorToolkit.parseKlines(null)).isEmpty();
    }

    @Test
    void intervalValidation() {
        assertThat(IndicatorToolkit.validateInterval("1h")).isNull();
        assertThat(IndicatorToolkit.validateInterval("15m")).isNull();
        assertThat(IndicatorToolkit.validateInterval("3m")).contains("interval");
        assertThat(IndicatorToolkit.validateInterval(null)).contains("interval");
    }

    @Test
    void compactKlineRowsKeepOhlcvOrder() {
        String json = "[[1720000000000,\"100\",\"110\",\"95\",\"105\",\"1000\",1720000299999,\"0\",0,\"0\",\"0\",\"0\"]]";

        String out = IndicatorToolkit.toCompactRows(json);

        // [openTime,open,high,low,close,volume]
        assertThat(out).isEqualTo("[[1720000000000,100,110,95,105,1000]]");
    }
}
