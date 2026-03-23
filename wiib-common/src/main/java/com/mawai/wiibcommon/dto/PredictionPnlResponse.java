package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PredictionPnlResponse {

    private int totalBets;
    private int activeBets;
    private int wonBets;
    private int lostBets;
    private BigDecimal totalCost;
    private BigDecimal realizedPnl;
    private BigDecimal activeCost;
    private BigDecimal activeValue;
    private BigDecimal totalPnl;
    private BigDecimal winRate;
}
