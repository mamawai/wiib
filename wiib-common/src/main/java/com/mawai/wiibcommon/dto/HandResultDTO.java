package com.mawai.wiibcommon.dto;

import lombok.Data;

@Data
public class HandResultDTO {

    /** 结果对应的手牌下标（与 playerHands 一一对应）。 */
    private int handIndex;

    /** 该手结算结果：WIN / LOSE / PUSH / BLACKJACK。 */
    private String result;

    /** 该手总返还积分（含本金与收益）。 */
    private long payout;

    /** 该手净收益积分（可正可负）。 */
    private long net;
}
