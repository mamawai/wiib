package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("option_order")
public class OptionOrder {

    @TableId(type = IdType.AUTO)
    // 期权订单ID
    private Long id;

    // 用户ID
    private Long userId;

    // 期权合约ID
    private Long contractId;

    /** BTO(买入开仓)/STC(卖出平仓) */
    private String orderSide;

    /** MARKET/LIMIT */
    private String orderType;

    // 数量（份）
    private Integer quantity;

    // 限价（仅LIMIT单使用）
    private BigDecimal limitPrice;

    // 冻结金额（预留/可选：通常用于挂单冻结）
    private BigDecimal frozenAmount;

    // 成交价（权利金，单位：每份）
    private BigDecimal filledPrice;

    // 成交金额（不含手续费；通常=filledPrice×quantity，可推导但保留用于复盘）
    private BigDecimal filledAmount;

    // 手续费
    private BigDecimal commission;

    /** 成交时标的价格（复盘用） */
    private BigDecimal underlyingPrice;

    /** PENDING/FILLED/CANCELLED/EXPIRED */
    private String status;

    // 订单过期时间（预留；与合约到期时间不同）
    private LocalDateTime expireAt;

    @TableField(fill = FieldFill.INSERT)
    // 创建时间
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    // 更新时间
    private LocalDateTime updatedAt;
}
