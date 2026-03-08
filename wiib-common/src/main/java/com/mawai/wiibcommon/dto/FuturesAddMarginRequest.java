package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class FuturesAddMarginRequest {
    private Long positionId;
    private BigDecimal amount;
}
