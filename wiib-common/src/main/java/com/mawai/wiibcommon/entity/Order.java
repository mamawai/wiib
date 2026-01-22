package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体
 * 市价单：立即成交，status直接为FILLED
 * 限价单：进入订单池等待撮合，有效期最长一天
 */
@Data
@TableName("orders")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long stockId;

    /** 订单方向 BUY/SELL */
    private String orderSide;

    /** MARKET/LIMIT */
    private String orderType;

    /** 委托数量 */
    private Integer quantity;

    /** 限价（限价单必填） */
    private BigDecimal limitPrice;

    /** 冻结金额（限价买单冻结的资金） */
    private BigDecimal frozenAmount;

    /** 成交价格 */
    private BigDecimal filledPrice;

    /** 成交金额 */
    private BigDecimal filledAmount;

    /** 手续费 */
    private BigDecimal commission;

    /** 触发价格（限价单触发时的实际价格） */
    private BigDecimal triggerPrice;

    /** 触发时间（限价单触发时间） */
    private LocalDateTime triggeredAt;

    /** PENDING/TRIGGERED/FILLED/CANCELLED/EXPIRED */
    private String status;

    /** 过期时间（限价单有效期，最长一天） */
    private LocalDateTime expireAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
