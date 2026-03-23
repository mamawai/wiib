package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PredictionBetResponse {

    private Long id;
    private Long roundId;
    private Long windowStart;
    private String side;
    private BigDecimal contracts;
    private BigDecimal cost;
    private BigDecimal avgPrice;
    private BigDecimal payout;
    private BigDecimal currentValue;
    private String status;
    private LocalDateTime createdAt;
}
