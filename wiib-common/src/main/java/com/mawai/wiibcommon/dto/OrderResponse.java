package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单响应
 */
@Data
public class OrderResponse {

    /** 订单ID */
    private Long orderId;

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 订单方向 BUY/SELL */
    private String orderSide;

    /** 订单类型 MARKET/LIMIT */
    private String orderType;

    /** 委托数量 */
    private Integer quantity;

    /** 限价（限价单） */
    private BigDecimal limitPrice;

    /** 成交价格（已成交时有值） */
    private BigDecimal filledPrice;

    /** 成交金额（已成交时有值） */
    private BigDecimal filledAmount;

    /** 触发价格（限价单触发时有值） */
    private BigDecimal triggerPrice;

    /** 触发时间（限价单触发时有值） */
    private LocalDateTime triggeredAt;

    /** 订单状态 PENDING/TRIGGERED/FILLED/CANCELLED/EXPIRED */
    private String status;

    /** 过期时间（限价单） */
    private LocalDateTime expireAt;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
