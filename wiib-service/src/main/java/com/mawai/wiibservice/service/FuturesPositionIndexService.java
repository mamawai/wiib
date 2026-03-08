package com.mawai.wiibservice.service;

import java.math.BigDecimal;

public interface FuturesPositionIndexService {

    void register(Long positionId, String symbol, String side, BigDecimal liqPrice);

    void unregister(Long positionId, String symbol, String side);

    void updateLiquidationPrice(Long positionId, String symbol, String side, BigDecimal liqPrice);

    void setStopLoss(Long positionId, String symbol, String side, BigDecimal stopLossPrice, boolean fromLiqKey);

    BigDecimal calcStaticLiqPrice(String side, BigDecimal entryPrice, BigDecimal margin, BigDecimal quantity);
}
