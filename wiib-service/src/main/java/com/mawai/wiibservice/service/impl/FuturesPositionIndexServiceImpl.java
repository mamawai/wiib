package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesPositionIndexService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesPositionIndexServiceImpl implements FuturesPositionIndexService {

    private final FuturesPositionMapper positionMapper;
    private final CacheService cacheService;
    private final TradingConfig tradingConfig;

    private static final String LIQ_LONG_PREFIX = "futures:liq:long:";
    private static final String LIQ_SHORT_PREFIX = "futures:liq:short:";
    private static final String SL_LONG_PREFIX = "futures:sl:long:";
    private static final String SL_SHORT_PREFIX = "futures:sl:short:";

    @PostConstruct
    void init() {
        List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getStatus, "OPEN"));

        for (FuturesPosition pos : positions) {
            if (pos.getStopLossPrice() != null) {
                String slKey = "LONG".equals(pos.getSide()) ? SL_LONG_PREFIX + pos.getSymbol() : SL_SHORT_PREFIX + pos.getSymbol();
                cacheService.zAdd(slKey, pos.getId().toString(), pos.getStopLossPrice().doubleValue());
            } else {
                BigDecimal liqPrice = calcStaticLiqPrice(pos.getSide(), pos.getEntryPrice(), pos.getMargin(), pos.getQuantity());
                register(pos.getId(), pos.getSymbol(), pos.getSide(), liqPrice);
            }
        }

        log.info("重建futures ZSet索引 共{}个仓位", positions.size());
    }

    @Override
    public void register(Long positionId, String symbol, String side, BigDecimal liqPrice) {
        String key = "LONG".equals(side) ? LIQ_LONG_PREFIX + symbol : LIQ_SHORT_PREFIX + symbol;
        cacheService.zAdd(key, positionId.toString(), liqPrice.doubleValue());
    }

    @Override
    public void unregister(Long positionId, String symbol, String side) {
        String id = positionId.toString();
        String slKey = "LONG".equals(side) ? SL_LONG_PREFIX + symbol : SL_SHORT_PREFIX + symbol;
        Long removed = cacheService.zRemove(slKey, id);
        if (removed == null || removed == 0) {
            String liqKey = "LONG".equals(side) ? LIQ_LONG_PREFIX + symbol : LIQ_SHORT_PREFIX + symbol;
            cacheService.zRemove(liqKey, id);
        }
    }

    @Override
    public void updateLiquidationPrice(Long positionId, String symbol, String side, BigDecimal liqPrice) {
        String key = "LONG".equals(side) ? LIQ_LONG_PREFIX + symbol : LIQ_SHORT_PREFIX + symbol;
        Double existing = cacheService.zScore(key, positionId.toString());
        if (existing != null) {
            cacheService.zAdd(key, positionId.toString(), liqPrice.doubleValue());
        }
    }

    @Override
    public void setStopLoss(Long positionId, String symbol, String side, BigDecimal stopLossPrice, boolean fromLiqKey) {
        String slKey = "LONG".equals(side) ? SL_LONG_PREFIX + symbol : SL_SHORT_PREFIX + symbol;
        if (fromLiqKey) {
            String liqKey = "LONG".equals(side) ? LIQ_LONG_PREFIX + symbol : LIQ_SHORT_PREFIX + symbol;
            cacheService.zRemove(liqKey, positionId.toString());
        }
        cacheService.zAdd(slKey, positionId.toString(), stopLossPrice.doubleValue());
    }

    /**
     * 强平价计算
     * <p>
     * 强平条件: 有效保证金 = 维持保证金
     * 即: margin + unrealizedPnl = liqPrice * qty * mmr
     * <p>
     * LONG (价格下跌亏损):
     *   margin + (liqPrice - entryPrice) * qty = liqPrice * qty * mmr
     *   margin = liqPrice * qty * mmr - liqPrice * qty + entryPrice * qty
     *   margin = liqPrice * qty * (mmr - 1) + entryPrice * qty
     *   entryPrice * qty - margin = liqPrice * qty * (1 - mmr)
     *   liqPrice = (entryPrice * qty - margin) / (qty * (1 - mmr))
     * <p>
     * SHORT (价格上涨亏损):
     *   margin + (entryPrice - liqPrice) * qty = liqPrice * qty * mmr
     *   margin = liqPrice * qty * mmr + liqPrice * qty - entryPrice * qty
     *   margin = liqPrice * qty * (mmr + 1) - entryPrice * qty
     *   entryPrice * qty + margin = liqPrice * qty * (1 + mmr)
     *   liqPrice = (entryPrice * qty + margin) / (qty * (1 + mmr))
     */
    public BigDecimal calcStaticLiqPrice(String side, BigDecimal entryPrice, BigDecimal margin, BigDecimal quantity) {
        BigDecimal mmr = tradingConfig.getFutures().getMaintenanceMarginRate();
        BigDecimal num;
        BigDecimal den;
        if ("LONG".equals(side)) {
            num = entryPrice.multiply(quantity).subtract(margin);
            den = quantity.multiply(BigDecimal.ONE.subtract(mmr));
        } else {
            num = entryPrice.multiply(quantity).add(margin);
            den = quantity.multiply(BigDecimal.ONE.add(mmr));
        }
        return num.divide(den, 2, RoundingMode.HALF_UP);
    }
}
