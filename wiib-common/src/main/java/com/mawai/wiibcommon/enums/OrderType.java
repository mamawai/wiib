package com.mawai.wiibcommon.enums;

import lombok.Getter;
import lombok.AllArgsConstructor;

@Getter
@AllArgsConstructor
public enum OrderType {

    MARKET("MARKET", "市价"),
    LIMIT("LIMIT", "限价");

    private final String code;
    private final String desc;
}
