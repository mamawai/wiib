package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PredictionRoundResponse {

    private Long id;
    private Long windowStart;
    private BigDecimal startPrice;
    private BigDecimal endPrice;
    private String outcome;
    /** Polymarket 实时 UP 价格 */
    private BigDecimal upPrice;
    /** Polymarket 实时 DOWN 价格 */
    private BigDecimal downPrice;
    private String status;
    private int remainingSeconds;
}
