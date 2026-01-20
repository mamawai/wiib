package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资金结算实体
 * 卖出资金T+1到账
 */
@Data
@TableName("settlement")
public class Settlement {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 关联订单ID */
    private Long orderId;

    /** 结算金额（卖出净额=成交额-手续费） */
    private BigDecimal amount;

    /** 到账日期（卖出次日） */
    private LocalDate settleDate;

    /** PENDING/SETTLED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
