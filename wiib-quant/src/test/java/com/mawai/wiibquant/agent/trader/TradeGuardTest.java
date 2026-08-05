package com.mawai.wiibquant.agent.trader;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TradeGuardTest {

    private static final Set<String> WL = Set.of("BTCUSDT", "ETHUSDT");
    private static final BigDecimal EQUITY = new BigDecimal("10000");
    private static final BigDecimal MARK = new BigDecimal("100000");

    /** 合法基准单：LONG MARKET 0.01BTC 10x 止损95000 → 保证金100 远低于50%上限 */
    private TradeGuard.OpenReq ok() {
        return new TradeGuard.OpenReq("BTCUSDT", "LONG", "MARKET", new BigDecimal("0.01"), 10,
                null, new BigDecimal("95000"), null, "BREAKOUT", "突破前高+量比1.8");
    }

    @Test
    void passesValidOrder() {
        assertThat(TradeGuard.validateOpen(ok(), EQUITY, MARK, WL)).isNull();
    }

    @Test
    void rejectsSymbolOutsideWhitelist() {
        assertThat(TradeGuard.validateOpen(ok().withSymbol("PEPEUSDT"), EQUITY, MARK, WL))
                .contains("白名单");
    }

    @Test
    void rejectsOverLeverage() {
        assertThat(TradeGuard.validateOpen(ok().withLeverage(50), EQUITY, MARK, WL))
                .contains("杠杆");
    }

    @Test
    void rejectsMarginOverHalfEquity() {
        // 0.6 BTC × 100000 / 10x = 6000 保证金 > 10000×50%
        assertThat(TradeGuard.validateOpen(ok().withQuantity(new BigDecimal("0.6")), EQUITY, MARK, WL))
                .contains("保证金");
    }

    @Test
    void rejectsLimitPriceTooFarFromMark() {
        TradeGuard.OpenReq req = ok().withOrderType("LIMIT").withLimitPrice(new BigDecimal("90000")); // 偏离10%
        assertThat(TradeGuard.validateOpen(req, EQUITY, MARK, WL)).contains("偏离");
    }

    @Test
    void rejectsMissingStopLoss() {
        assertThat(TradeGuard.validateOpen(ok().withStopLossPrice(null), EQUITY, MARK, WL))
                .contains("止损");
    }

    @Test
    void rejectsStopLossOnWrongSide() {
        // LONG 止损必须低于入场参考价
        assertThat(TradeGuard.validateOpen(ok().withStopLossPrice(new BigDecimal("105000")), EQUITY, MARK, WL))
                .contains("止损");
        // SHORT 止损必须高于入场参考价
        TradeGuard.OpenReq shortReq = ok().withSide("SHORT").withStopLossPrice(new BigDecimal("95000"));
        assertThat(TradeGuard.validateOpen(shortReq, EQUITY, MARK, WL)).contains("止损");
    }

    @Test
    void rejectsTakeProfitOnWrongSide() {
        assertThat(TradeGuard.validateOpen(ok().withTakeProfitPrice(new BigDecimal("95000")), EQUITY, MARK, WL))
                .contains("止盈");
    }

    @Test
    void rejectsUnknownPlayType() {
        assertThat(TradeGuard.validateOpen(ok().withPlayType("YOLO"), EQUITY, MARK, WL))
                .contains("playType");
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThat(TradeGuard.validateOpen(ok().withQuantity(BigDecimal.ZERO), EQUITY, MARK, WL))
                .contains("数量");
    }

    @Test
    void limitOrderMarginUsesLimitPrice() {
        // 限价 98000×0.05/1x = 4900 ≤ 5000 通过；市价按 mark 100000×0.05=5000 也恰好通过
        TradeGuard.OpenReq req = new TradeGuard.OpenReq("BTCUSDT", "LONG", "LIMIT", new BigDecimal("0.05"), 1,
                new BigDecimal("98000"), new BigDecimal("90000"), null, "PULLBACK", "回踩支撑");
        assertThat(TradeGuard.validateOpen(req, EQUITY, MARK, WL)).isNull();
    }
}
