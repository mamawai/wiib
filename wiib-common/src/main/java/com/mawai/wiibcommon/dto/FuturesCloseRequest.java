package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class FuturesCloseRequest {
    private Long positionId;
    private BigDecimal quantity; // 平仓数量
    private String orderType; // MARKET/LIMIT
    private BigDecimal limitPrice; // 限价时必填
}
