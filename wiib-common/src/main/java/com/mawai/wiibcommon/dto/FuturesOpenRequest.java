package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class FuturesOpenRequest {
    private String symbol;
    private String side; // LONG/SHORT
    private BigDecimal quantity;
    private Integer leverage;
    private String orderType; // MARKET/LIMIT
    private BigDecimal limitPrice; // 限价时必填
    private BigDecimal stopLossPercent; // 可选止损百分比(保留保证金%) 如5=保留5%
}
