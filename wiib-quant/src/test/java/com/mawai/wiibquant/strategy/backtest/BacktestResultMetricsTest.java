package com.mawai.wiibquant.strategy.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最大回撤的口径：逐点算 (峰值-权益)/峰值 再取最大。
 * 回测页、AI 复盘、策略评估都读这一个数，算错了整条风险叙述跟着错，而且不会有任何报错。
 */
class BacktestResultMetricsTest {

    @Test
    void 后期新高不许稀释掉早期的深回撤() {
        BacktestResult r = new BacktestResult(new BigDecimal("100"));
        r.recordEquity(new BigDecimal("50"));     // 从 100 砸到 50：这一段就是 50%
        r.recordEquity(new BigDecimal("1000"));
        r.recordEquity(new BigDecimal("900"));    // 从 1000 回到 900：这一段只有 10%

        // 先取最大绝对回撤（100）再除全局峰值（1000）会得出 10%，把最惨那段抹平
        assertThat(r.maxDrawdownPct()).isEqualTo(0.5);
    }

    @Test
    void 一路新高就没有回撤() {
        BacktestResult r = new BacktestResult(new BigDecimal("100"));
        r.recordEquity(new BigDecimal("120"));
        r.recordEquity(new BigDecimal("150"));

        assertThat(r.maxDrawdownPct()).isZero();
    }
}
