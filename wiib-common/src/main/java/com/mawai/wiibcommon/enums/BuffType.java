package com.mawai.wiibcommon.enums;

import lombok.Getter;

@Getter
public enum BuffType {
    DISCOUNT_95("交易95折", Rarity.COMMON, Category.DISCOUNT, 0.95),
    CASH_5000("红包5千", Rarity.COMMON, Category.CASH, 5000),
    STOCK_100("股票100股", Rarity.COMMON, Category.STOCK, 100),

    DISCOUNT_90("交易9折", Rarity.RARE, Category.DISCOUNT, 0.90),
    CASH_10000("红包1万", Rarity.RARE, Category.CASH, 10000),
    STOCK_300("股票300股", Rarity.RARE, Category.STOCK, 300),

    DISCOUNT_85("交易85折", Rarity.EPIC, Category.DISCOUNT, 0.85),
    CASH_20000("红包2万", Rarity.EPIC, Category.CASH, 20000),
    STOCK_500("股票500股", Rarity.EPIC, Category.STOCK, 500),

    DISCOUNT_80("交易8折", Rarity.LEGENDARY, Category.DISCOUNT, 0.80),
    CASH_50000("红包5万", Rarity.LEGENDARY, Category.CASH, 50000),
    STOCK_1000("股票1000股", Rarity.LEGENDARY, Category.STOCK, 1000);

    public enum Category { DISCOUNT, CASH, STOCK }

    private final String displayName;
    private final Rarity rarity;
    private final Category category;
    private final double value;

    BuffType(String displayName, Rarity rarity, Category category, double value) {
        this.displayName = displayName;
        this.rarity = rarity;
        this.category = category;
        this.value = value;
    }

}
