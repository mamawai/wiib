package com.mawai.wiibsim.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibsim.mapper.BlackjackAccountMapper;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.MinesGameMapper;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.mapper.UserAssetSnapshotMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.mapper.VideoPokerGameMapper;
import com.mawai.wiibsim.service.BStockService;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.UserService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 行为分析合约统计的分品类拆解：与资产五分类同源的符号集归桶——
 * crypto 永续 / 大宗商品(金油) / TradFi(美股ETF永续) 各归各桶，不再混成一个"合约"总数。
 */
class FuturesStatsCategoryTest {

    private static final Long UID = 7L;

    @Test
    void 合约统计分品类_加密大宗tradfi各归各桶() {
        var futuresOrderMapper = mock(FuturesOrderMapper.class);
        var futuresPositionMapper = mock(FuturesPositionMapper.class);

        when(futuresOrderMapper.sumRealizedPnl(UID)).thenReturn(new BigDecimal("60"));
        when(futuresOrderMapper.countFilledOrders(UID)).thenReturn(6L);
        when(futuresOrderMapper.selectDirectionPreference(UID)).thenReturn("LONG");
        when(futuresOrderMapper.selectAvgLeverage(UID)).thenReturn(new BigDecimal("20"));
        when(futuresPositionMapper.selectStopLossRate(UID)).thenReturn(BigDecimal.ZERO);
        when(futuresPositionMapper.countLiquidatedPositions(UID)).thenReturn(0);

        when(futuresOrderMapper.sumRealizedPnlBySymbol(UID)).thenReturn(List.of(
                Map.of("symbol", "BTCUSDT", "amount", new BigDecimal("100")),
                Map.of("symbol", "XAUUSDT", "amount", new BigDecimal("-30")),
                Map.of("symbol", "SNDKUSDT", "amount", new BigDecimal("-10"))));
        when(futuresOrderMapper.countFilledOrdersBySymbol(UID)).thenReturn(List.of(
                Map.of("symbol", "BTCUSDT", "cnt", 3L),
                Map.of("symbol", "XAUUSDT", "cnt", 2L),
                Map.of("symbol", "SNDKUSDT", "cnt", 1L)));

        BinanceProperties props = new BinanceProperties();
        props.setSymbols(List.of("BTCUSDT", "ETHUSDT"));
        props.setCommoditySymbols(List.of("XAUUSDT", "CLUSDT"));
        props.setTradfiSymbols(List.of("SNDKUSDT", "SOXLUSDT"));

        var controller = new BehaviorDataController(mock(UserMapper.class),
                mock(UserAssetSnapshotMapper.class), mock(CryptoOrderMapper.class),
                mock(CryptoPositionService.class), mock(BStockService.class),
                futuresOrderMapper, futuresPositionMapper, mock(PredictionBetMapper.class),
                mock(BlackjackAccountMapper.class), mock(MinesGameMapper.class),
                mock(VideoPokerGameMapper.class), mock(UserService.class), props);

        JSONObject json = JSON.parseObject(controller.getFuturesTradeStats(UID));

        // 总量指标不变
        assertThat(json.getBigDecimal("realizedPnl")).isEqualByComparingTo("60");
        assertThat(json.getLongValue("orderCount")).isEqualTo(6);

        // 分品类：BTC→crypto、XAU→commodity、SNDK→tradfi
        JSONObject by = json.getJSONObject("byCategory");
        assertThat(by.getJSONObject("crypto").getBigDecimal("realizedPnl")).isEqualByComparingTo("100");
        assertThat(by.getJSONObject("crypto").getIntValue("orderCount")).isEqualTo(3);
        assertThat(by.getJSONObject("commodity").getBigDecimal("realizedPnl")).isEqualByComparingTo("-30");
        assertThat(by.getJSONObject("commodity").getIntValue("orderCount")).isEqualTo(2);
        assertThat(by.getJSONObject("tradfi").getBigDecimal("realizedPnl")).isEqualByComparingTo("-10");
        assertThat(by.getJSONObject("tradfi").getIntValue("orderCount")).isEqualTo(1);
    }

    @Test
    void 未知符号归入crypto默认桶_三桶恒在() {
        var futuresOrderMapper = mock(FuturesOrderMapper.class);
        var futuresPositionMapper = mock(FuturesPositionMapper.class);

        when(futuresOrderMapper.sumRealizedPnl(UID)).thenReturn(BigDecimal.ZERO);
        when(futuresOrderMapper.countFilledOrders(UID)).thenReturn(1L);
        when(futuresOrderMapper.selectDirectionPreference(UID)).thenReturn("NONE");
        when(futuresOrderMapper.selectAvgLeverage(UID)).thenReturn(BigDecimal.ZERO);
        when(futuresPositionMapper.selectStopLossRate(UID)).thenReturn(BigDecimal.ZERO);
        when(futuresPositionMapper.countLiquidatedPositions(UID)).thenReturn(0);
        // 已下架符号不在任何清单里 → 默认归 crypto，别丢数据
        when(futuresOrderMapper.sumRealizedPnlBySymbol(UID)).thenReturn(List.of(
                Map.of("symbol", "DELISTEDUSDT", "amount", new BigDecimal("5"))));
        when(futuresOrderMapper.countFilledOrdersBySymbol(UID)).thenReturn(List.of(
                Map.of("symbol", "DELISTEDUSDT", "cnt", 1L)));

        BinanceProperties props = new BinanceProperties();
        props.setSymbols(List.of("BTCUSDT"));

        var controller = new BehaviorDataController(mock(UserMapper.class),
                mock(UserAssetSnapshotMapper.class), mock(CryptoOrderMapper.class),
                mock(CryptoPositionService.class), mock(BStockService.class),
                futuresOrderMapper, futuresPositionMapper, mock(PredictionBetMapper.class),
                mock(BlackjackAccountMapper.class), mock(MinesGameMapper.class),
                mock(VideoPokerGameMapper.class), mock(UserService.class), props);

        JSONObject by = JSON.parseObject(controller.getFuturesTradeStats(UID)).getJSONObject("byCategory");
        assertThat(by.getJSONObject("crypto").getBigDecimal("realizedPnl")).isEqualByComparingTo("5");
        // 没交易过的品类也要有零值桶：LLM/前端拿到的结构恒定
        assertThat(by.getJSONObject("commodity").getIntValue("orderCount")).isZero();
        assertThat(by.getJSONObject("tradfi").getIntValue("orderCount")).isZero();
    }
}
