package com.mawai.wiibcommon.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单状态变化事件
 * 触发场景：订单创建、成交、取消、过期、触发
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long userId;

    /** 订单ID */
    private Long orderId;

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 订单类型：MARKET、LIMIT */
    private String orderType;

    /** 订单方向：BUY、SELL */
    private String orderSide;

    /** 数量 */
    private Integer quantity;

    /** 价格 */
    private BigDecimal price;

    /** 旧状态 */
    private String oldStatus;

    /** 新状态：PENDING、TRIGGERED、FILLED、CANCELLED、EXPIRED */
    private String newStatus;

    /** 成交价格（仅FILLED状态） */
    private BigDecimal executePrice;

    /** 事件时间戳 */
    private Long timestamp;

    public OrderStatusEvent(Long userId, Long orderId, String stockCode, String stockName,
                            String orderType, String orderSide, Integer quantity, BigDecimal price,
                            String oldStatus, String newStatus) {
        this.userId = userId;
        this.orderId = orderId;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.orderType = orderType;
        this.orderSide = orderSide;
        this.quantity = quantity;
        this.price = price;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.timestamp = System.currentTimeMillis();
    }
}
