package com.mawai.wiibcommon.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class HandDTO {

    /** 当前手牌牌面（例如 AH、TD）。 */
    private List<String> cards;

    /** 当前手牌下注额（加倍后为加倍后的金额）。 */
    private long bet;

    /** 当前手牌最优点数（A 可按 1 或 11 计算）。 */
    private int score;

    /**
     * 是否爆牌。
     * <p>
     * 显式指定 JSON 字段名为 isBust，避免 boolean 命名在不同序列化策略下
     * 被推断成 bust，导致前端字段不一致。
     */
    @JsonProperty("isBust")
    private boolean isBust;

    /**
     * 是否自然 Blackjack（仅两张牌且点数21）。
     */
    @JsonProperty("isBlackjack")
    private boolean isBlackjack;

    /**
     * 是否执行过加倍操作。
     */
    @JsonProperty("isDoubled")
    private boolean isDoubled;
}
