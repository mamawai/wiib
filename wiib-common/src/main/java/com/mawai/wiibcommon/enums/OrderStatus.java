package com.mawai.wiibcommon.enums;

import lombok.Getter;
import lombok.AllArgsConstructor;

/**
 * 订单状态
 */
@Getter
@AllArgsConstructor
public enum OrderStatus {

    PENDING("PENDING", "待成交"),
    TRIGGERED("TRIGGERED", "已触发"),
    SETTLING("SETTLING", "结算中"),
    FILLED("FILLED", "已成交"),
    CANCELLED("CANCELLED", "已取消"),
    EXPIRED("EXPIRED", "已过期");

    private final String code;
    private final String desc;
}
