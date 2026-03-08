package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class FuturesStopLossRequest {
    private Long positionId;
    private BigDecimal stopLossPercent; // 保留保证金% 如5=保留5%
}
