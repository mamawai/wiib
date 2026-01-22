package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结算DTO
 */
@Data
public class SettlementDTO {

    /** 结算ID */
    private Long id;

    /** 关联订单ID */
    private Long orderId;

    /** 结算金额 */
    private BigDecimal amount;

    /** 到账时间 */
    private LocalDateTime settleTime;

    /** 状态 PENDING/SETTLED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
