package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class FuturesCloseRequest {
    private Long positionId;
    private BigDecimal quantity; // 平仓数量
    private String orderType; // MARKET/LIMIT
    private BigDecimal limitPrice; // 限价时必填
    /** 请求幂等键（internal 通道用）：同键重发不会重复成交；用户端下单不传 */
    private String clientRequestId;
}
