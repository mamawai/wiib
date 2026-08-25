package com.mawai.wiibsim.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 全站成交的<b>内部</b>行，只到 service 为止，绝不直接出接口。
 * 与对外 DTO 分成两个类型，让"带身份的行泄漏出接口"变成编译期错误。
 *
 * @see PublicTradeDTO
 */
@Data
public class PublicTradeRow {

    /** SPOT / FUTURES —— 两张订单表 UNION 出来的，靠它区分来源 */
    private String kind;

    private Long tradeId;

    /** 只用来算假名，不出接口 */
    private Long userId;

    /** 只用来判是不是策略账户（quant-*），不出接口 */
    private String username;

    private String symbol;

    private String orderSide;

    private BigDecimal quantity;

    private BigDecimal filledPrice;

    private BigDecimal filledAmount;

    private LocalDateTime createdAt;
}
