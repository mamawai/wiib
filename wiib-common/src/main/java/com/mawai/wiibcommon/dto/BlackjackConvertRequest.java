package com.mawai.wiibcommon.dto;

import lombok.Data;

/**
 * Blackjack 积分转出请求。
 * <p>
 * 说明：
 * - amount 仅表示用户想转出的积分数量；
 * - 是否可转、是否超日限额等规则由 Service 层统一裁决。
 */
@Data
public class BlackjackConvertRequest {

    /**
     * 需要转出的积分金额。
     */
    private Long amount;
}

