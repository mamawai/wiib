package com.mawai.wiibcommon.dto;

import lombok.Data;

/**
 * Blackjack 开局下注请求。
 * <p>
 * 说明：
 * - 这里只承载原始请求值，不在 DTO 层做复杂业务校验；
 * - 下注合法性（最小/最大/积分余额）统一由 Service 层负责，保证规则口径单一。
 */
@Data
public class BlackjackBetRequest {

    /**
     * 下注金额（积分）。
     */
    private Long amount;
}

