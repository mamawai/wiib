package com.mawai.wiibcommon.enums;

import lombok.Getter;
import lombok.AllArgsConstructor;

@Getter
@AllArgsConstructor
public enum OrderSide {

    BUY("BUY", "买入"),
    SELL("SELL", "卖出");

    private final String code;
    private final String desc;
}
