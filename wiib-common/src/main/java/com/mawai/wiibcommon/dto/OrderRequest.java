package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 交易请求
 */
@Data
public class OrderRequest {

    /** 股票ID */
    private Long stockId;

    /** 交易数量 */
    private Integer quantity;

    /** 订单类型 MARKET/LIMIT */
    private String orderType;

    /** 限价（限价单必填） */
    private BigDecimal limitPrice;

    /** 杠杆倍率（2-10），仅市价买入支持；null或<=1表示不加杠杆 */
    private Integer leverageMultiple;

    /** 使用的折扣Buff ID（可选） */
    private Long useBuffId;
}
