package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 交易请求
 */
@Data
public class OrderRequest {

    /** 股票代码 */
    private String stockCode;

    /** 交易数量 */
    private Integer quantity;

    /** 订单类型 MARKET/LIMIT */
    private String orderType;

    /** 限价（限价单必填） */
    private BigDecimal limitPrice;

    /** 请求ID（幂等性，防重复提交） */
    private String requestId;

    /** 客户端时间戳ms（防作弊，与服务端时间差不超过3秒） */
    private Long clientTimestamp;
}
