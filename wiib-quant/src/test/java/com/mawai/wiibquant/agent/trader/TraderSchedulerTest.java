package com.mawai.wiibquant.agent.trader;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TraderSchedulerTest {

    // 2026-08-05 09:00:00 UTC 整点前 1ms —— Binance closeTime 口径 xx:59:59.999
    private static final long H1_CLOSE = 1785171599999L;
    private static final long H1_BOUNDARY = 1785171600000L;

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final TraderWakeupRunner runner = mock(TraderWakeupRunner.class);

    private AiTrader trader1h() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setIntervalCode("1h");
        return t;
    }

    private AlertTrigger trig() {
        return new AlertTrigger("BTCUSDT", new BigDecimal("1.2"), new BigDecimal("63120"), "下跌", H1_BOUNDARY);
    }

    // ---------- 警报唤醒准入 ----------

    @Test
    void alertWakePassesAdmissionAndRunsRunner() {
        TraderScheduler s = new TraderScheduler(traderMapper, runner);
        s.nowMs = () -> H1_BOUNDARY + 600_000L; // 1h 周期中段，预算充足

        AlertTrigger trig = trig();
        s.tryAlertWake(trader1h(), trig);

        verify(runner, timeout(2_000)).wakeAlert(any(AiTrader.class), any(AlertTrigger.class));
    }

    /** 冷静期从任何唤醒算起：刚醒过的 trader 5 分钟内不再被警报打扰 */
    @Test
    void alertBlockedDuringCooldown() {
        TraderScheduler s = new TraderScheduler(traderMapper, runner);
        s.nowMs = () -> H1_BOUNDARY + 600_000L;

        s.tryAlertWake(trader1h(), trig());
        verify(runner, timeout(2_000)).wakeAlert(any(), any());

        s.tryAlertWake(trader1h(), trig());
        verify(runner, after(300).times(1)).wakeAlert(any(), any()); // 第二次被冷静期拦下
    }

    /** 例行唤醒将至（距边界<30s）警报不抢戏：马上就有新鲜K线信号 */
    @Test
    void alertBlockedWhenRoutineWakeImminent() {
        TraderScheduler s = new TraderScheduler(traderMapper, runner);
        s.nowMs = () -> H1_BOUNDARY + 3_590_000L; // 距下一 1h 边界仅 10s

        s.tryAlertWake(trader1h(), trig());

        verify(runner, after(300).never()).wakeAlert(any(), any());
    }

    @Test
    void alignedCloseYieldsBoundary() {
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE, "1h")).isEqualTo(1785171600000L);
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE, "15m")).isEqualTo(1785171600000L); // 整点也是15m边界
    }

    @Test
    void nonAlignedCloseYieldsMinusOne() {
        // 整点前 5 分钟收盘的 5m bar：不是 1h 边界，但是 15m 边界也不是（xx:55）
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE - 300_000, "1h")).isEqualTo(-1);
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE - 300_000, "15m")).isEqualTo(-1);
        // xx:45 收盘：是 15m 边界不是 1h 边界
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE - 900_000, "15m")).isEqualTo(1785170700000L);
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE - 900_000, "1h")).isEqualTo(-1);
    }

    @Test
    void dailyBoundary() {
        long midnightClose = 1785110400000L - 1; // 2026-07-27 00:00:00 UTC 前 1ms
        assertThat(TraderScheduler.boundaryOf(midnightClose, "1d")).isEqualTo(1785110400000L);
        assertThat(TraderScheduler.boundaryOf(midnightClose, "4h")).isEqualTo(1785110400000L);
    }

    @Test
    void unknownIntervalYieldsMinusOne() {
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE, "3m")).isEqualTo(-1);
    }

    /** 5m 是时钟本身的滴答粒度：任意 5m 收盘都是 5m trader 的边界 */
    @Test
    void fiveMinuteBoundary() {
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE, "5m")).isEqualTo(1785171600000L);
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE - 300_000, "5m")).isEqualTo(1785171300000L);
    }
}
