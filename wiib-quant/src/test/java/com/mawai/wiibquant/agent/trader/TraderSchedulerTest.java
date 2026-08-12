package com.mawai.wiibquant.agent.trader;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibquant.agent.learning.ReviewRunner;
import com.mawai.wiibquant.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraderSchedulerTest {

    // 2026-08-05 09:00:00 UTC 整点前 1ms —— Binance closeTime 口径 xx:59:59.999
    private static final long H1_CLOSE = 1785171599999L;
    private static final long H1_BOUNDARY = 1785171600000L;
    // 2026-07-27 00:00:00 UTC（日线边界）
    private static final long DAY_BOUNDARY = 1785110400000L;

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final TraderWakeupRunner runner = mock(TraderWakeupRunner.class);
    private final ReviewRunner reviewRunner = mock(ReviewRunner.class);

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
        TraderScheduler s = new TraderScheduler(traderMapper, runner, reviewRunner);
        s.nowMs = () -> H1_BOUNDARY + 600_000L; // 1h 周期中段，预算充足

        AlertTrigger trig = trig();
        s.tryAlertWake(trader1h(), trig);

        verify(runner, timeout(2_000)).wakeAlert(any(AiTrader.class), any(AlertTrigger.class));
    }

    /** 冷静期从任何唤醒算起：刚醒过的 trader 5 分钟内不再被警报打扰 */
    @Test
    void alertBlockedDuringCooldown() {
        TraderScheduler s = new TraderScheduler(traderMapper, runner, reviewRunner);
        s.nowMs = () -> H1_BOUNDARY + 600_000L;

        s.tryAlertWake(trader1h(), trig());
        verify(runner, timeout(2_000)).wakeAlert(any(), any());

        s.tryAlertWake(trader1h(), trig());
        verify(runner, after(300).times(1)).wakeAlert(any(), any()); // 第二次被冷静期拦下
    }

    /** 例行唤醒将至（距边界<30s）警报不抢戏：马上就有新鲜K线信号 */
    @Test
    void alertBlockedWhenRoutineWakeImminent() {
        TraderScheduler s = new TraderScheduler(traderMapper, runner, reviewRunner);
        s.nowMs = () -> H1_BOUNDARY + 3_590_000L; // 距下一 1h 边界仅 10s

        s.tryAlertWake(trader1h(), trig());

        verify(runner, after(300).never()).wakeAlert(any(), any());
    }

    // ---------- 手动唤醒（对话轨 wake_trader，已过 HITL） ----------

    /** 手动唤醒走的是例行入口 wake()：它就是一次普通的交易决策，只是扳机在人手里 */
    @Test
    void manualWakeRunsRoutineWake() {
        TraderScheduler s = new TraderScheduler(traderMapper, runner, reviewRunner);
        s.nowMs = () -> H1_BOUNDARY + 600_000L; // 1h 周期中段，预算充足

        assertThat(s.tryManualWake(trader1h())).isNull();   // null=已触发

        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(H1_BOUNDARY));
    }

    /**
     * 同一 trader 不并行：上一轮还在跑时手动唤醒必须落空。
     * 两个唤醒会话同时对着一个 sim 子账户下单，仓位会被重复开。
     */
    @Test
    void manualWakeBlockedWhileAnotherWakeInFlight() {
        TraderScheduler s = new TraderScheduler(traderMapper, runner, reviewRunner);
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        AiTrader t = trader1h();
        // 第一次占住互斥位；runner 是 mock 会立刻返回，所以卡住它来维持"还在跑"
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            hold.await();
            return null;
        }).when(runner).wake(any(), anyLong());
        s.tryManualWake(t);
        verify(runner, timeout(2_000)).wake(any(), anyLong());

        String why = s.tryManualWake(t);

        assertThat(why).contains("上一轮唤醒还在跑");
        verify(runner, after(300).times(1)).wake(any(), anyLong());
        hold.countDown();
    }

    /** 距下一根K线太近：例行唤醒马上到，这一次手动的省下来（同 alert 的判据） */
    @Test
    void manualWakeBlockedWhenRoutineWakeImminent() {
        TraderScheduler s = new TraderScheduler(traderMapper, runner, reviewRunner);
        s.nowMs = () -> H1_BOUNDARY + 3_590_000L; // 距下一 1h 边界仅 10s

        String why = s.tryManualWake(trader1h());

        assertThat(why).contains("距下一次例行唤醒不足");
        verify(runner, after(300).never()).wake(any(), anyLong());
    }

    /** 手动唤醒也记进冷静期基准：刚被手动叫醒过，紧接着的波动警报没有增量价值 */
    @Test
    void manualWakeFeedsAlertCooldown() {
        TraderScheduler s = new TraderScheduler(traderMapper, runner, reviewRunner);
        s.nowMs = () -> H1_BOUNDARY + 600_000L;

        s.tryManualWake(trader1h());
        verify(runner, timeout(2_000)).wake(any(), anyLong());

        s.tryAlertWake(trader1h(), trig());

        verify(runner, after(300).never()).wakeAlert(any(), any());
    }

    // ---------- 日线边界复盘接入 ----------

    /** 日线边界：例行唤醒完成后同一虚拟线程接复盘（先交易后复盘，共用互斥绝不并行） */
    @Test
    void dailyBoundaryChainsReviewAfterWake() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = new TraderScheduler(traderMapper, runner, reviewRunner);

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(reviewRunner, timeout(2_000)).review(any(AiTrader.class), eq(DAY_BOUNDARY));
        verify(runner).wake(any(AiTrader.class), eq(DAY_BOUNDARY));
    }

    /** 非日线边界（普通整点）只有例行唤醒，没有复盘 */
    @Test
    void nonDailyBoundaryDoesNotReview() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = new TraderScheduler(traderMapper, runner, reviewRunner);

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", H1_CLOSE));

        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(H1_BOUNDARY));
        verify(reviewRunner, after(300).never()).review(any(), anyLong());
    }

    /** review_enabled=false：交易照常，复盘不跑 */
    @Test
    void reviewDisabledSkipsReview() {
        AiTrader t = trader1h();
        t.setReviewEnabled(false);
        when(traderMapper.selectList(any())).thenReturn(List.of(t));
        TraderScheduler s = new TraderScheduler(traderMapper, runner, reviewRunner);

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(DAY_BOUNDARY));
        verify(reviewRunner, after(300).never()).review(any(), anyLong());
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
