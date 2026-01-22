package com.mawai.wiibcommon.enums;

import lombok.Getter;

@Getter
public enum Rarity {
    COMMON(60),
    RARE(30),
    EPIC(9),
    LEGENDARY(1);

    private final int weight;

    Rarity(int weight) {
        this.weight = weight;
    }

}
