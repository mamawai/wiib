package com.mawai.wiibquant.agent.strategy.smc;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.WindowedMarketView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FvgFillStrategyTest {

    private static final long M5 = 5 * 60_000L;
    private static final String SYM = "BTCUSDT";
    private static final BigDecimal FVG_MID = new BigDecimal("152.5");

    /**
     * 快测参数：bias/struct 都用 5m 基础周期(每根即一桶，便于精确控盘)，
     * reversal=1.0 配±4影线/步长4刚好可控确认pivot；位移过滤与 Discount 门默认关，聚焦核心管线。
     */
    private static SmcParams fast() {
        return new SmcParams(M5, M5, 3, 1.0, 0.0, false, 0.1, 100_000, 500, 500);
    }

    private static SmcParams withDiscount() {
        return new SmcParams(M5, M5, 3, 1.0, 0.0, true, 0.1, 100_000, 500, 500);
    }

    private static SmcParams withTimeout(int bars) {
        return new SmcParams(M5, M5, 3, 1.0, 0.0, false, 0.1, bars, 500, 500);
    }

    @Test
    void emitsLongLimitAtFvgMidUnderBullishStructure() {
        Ctx ctx = new Ctx(fast());
        Optional<StrategySignal> sig = ctx.playMainScenario();

        assertTrue(sig.isPresent(), "多头结构+看涨FVG形成后应挂出限价回补单");
        StrategySignal s = sig.get();
        assertEquals("LIMIT", s.orderType(), "回补范式应挂限价单(maker)");
        assertEquals("LONG", s.side());
        assertEquals(0, s.entryRefPrice().compareTo(FVG_MID), "挂单价=FVG中位(151.6+153.4)/2");
        assertTrue(s.stopLossPrice().compareTo(s.entryRefPrice()) < 0, "止损在FVG下沿之下");
        assertTrue(s.takeProfitPrice().compareTo(s.entryRefPrice()) > 0, "止盈=摆动区间前高，在入场上方");
        assertTrue(s.reason().contains("FVG回补"));
    }

    @Test
    void noSignalWithoutStructureBias() {
        Ctx ctx = new Ctx(fast());
        // 单边上行：确认不出两组pivot → 无偏向 → 全程无信号
        for (int i = 0; i <= 14; i++) {
            assertTrue(ctx.feedClose(100 + i * 4).isEmpty(), "无偏向时不应有任何信号");
        }
    }

    @Test
    void consumesFvgAfterPositionOpened() {
        Ctx ctx = new Ctx(fast());
        Optional<StrategySignal> sig = ctx.playMainScenario();
        assertTrue(sig.isPresent());

        ctx.strategy.onPositionOpened(SYM, sig.get(), 1L, sig.get().entryRefPrice(), ctx.view, null);
        Optional<StrategySignal> next = ctx.feed(153.6, 154, 153, 153.8);

        assertTrue(next.isEmpty() || next.get().entryRefPrice().compareTo(FVG_MID) != 0,
                "成交后同一FVG不得再挂单(一FVG一次)");
    }

    @Test
    void consumesFvgWhenMidPassedWithoutFill() {
        Ctx ctx = new Ctx(fast());
        assertTrue(ctx.playMainScenario().isPresent());

        // 盘中触及中位=首次回补机会已被市场用掉 → 该FVG作废且不追回抽
        Optional<StrategySignal> touched = ctx.feed(153.6, 153.7, 151.9, 152.2);
        assertTrue(touched.isEmpty() || touched.get().entryRefPrice().compareTo(FVG_MID) != 0,
                "中位被触及当根起不得再挂该FVG");
        Optional<StrategySignal> after = ctx.feed(152.2, 153.5, 152.1, 153.2);
        assertTrue(after.isEmpty() || after.get().entryRefPrice().compareTo(FVG_MID) != 0,
                "被抢先触及的FVG不得再挂单");
    }

    @Test
    void ordersExpireAfterTimeout() {
        Ctx ctx = new Ctx(withTimeout(2));
        assertTrue(ctx.playMainScenario().isPresent());

        Optional<StrategySignal> bar1 = ctx.feed(153.6, 154.1, 153.2, 153.8);
        assertTrue(bar1.isPresent() && bar1.get().entryRefPrice().compareTo(FVG_MID) == 0,
                "超时前应持续reaffirm同一挂单");
        ctx.feed(153.8, 154.2, 153.3, 153.9);
        Optional<StrategySignal> expired = ctx.feed(153.9, 154.3, 153.4, 154.0);
        assertTrue(expired.isEmpty() || expired.get().entryRefPrice().compareTo(FVG_MID) != 0,
                "挂单超时后本FVG应作废");
    }

    @Test
    void discountGateBlocksPremiumZoneFvg() {
        Ctx ctx = new Ctx(withDiscount());
        Optional<StrategySignal> sig = ctx.playMainScenario();

        // 主场景FVG中位152.5在EQ上方(Premium)：开Discount门后不得在此挂单
        assertTrue(sig.isEmpty() || sig.get().entryRefPrice().compareTo(FVG_MID) != 0,
                "Premium区的看涨FVG应被Discount门拦下");
    }

    // ---- 场景与工具 ----

    /** 策略+view组合，维护接续时间戳。 */
    private static final class Ctx {
        final FvgFillStrategy strategy;
        final WindowedMarketView view = new WindowedMarketView(20_000);

        Ctx(SmcParams params) {
            strategy = new FvgFillStrategy(params, List.of(SYM));
        }

        /**
         * 主场景：21根摆动路径建立HH+HL多头结构(pivot …H144,L126,H156,L140)，
         * 随后三根推动留下看涨FVG[151.6,153.4]，返回FVG确认根的信号。
         */
        Optional<StrategySignal> playMainScenario() {
            double[] closes = {100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140,
                    134, 130, 136, 140, 144, 148, 152, 148, 144, 150};
            for (double c : closes) feedClose(c);
            feed(150, 151.6, 147.6, 151.5);          // FVG bar1: high=151.6
            feed(151.5, 157, 151.4, 156.8);          // bar2: 推动
            return feed(156.8, 157.5, 153.4, 153.6); // bar3: low=153.4 → FVG确认
        }

        /** 喂一根 h=c+4/l=c-4 的摆动bar。 */
        Optional<StrategySignal> feedClose(double close) {
            double open = view.closedBars(M5, 1).isEmpty()
                    ? close
                    : view.closedBars(M5, 1).getLast().close().doubleValue();
            return feed(open, close + 4, close - 4, close);
        }

        Optional<StrategySignal> feed(double open, double high, double low, double close) {
            long t = view.closedBars(M5, 1).isEmpty()
                    ? 0
                    : view.closedBars(M5, 1).getLast().openTime() + M5;
            view.append(new KlineBar(t, t + M5 - 1,
                    BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                    BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.ONE));
            return strategy.onBarClosed(SYM, view);
        }
    }
}
