package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.util.List;

@Data
public class GameStateDTO {

    /** 牌局阶段：PLAYER_TURN / SETTLED。 */
    private String phase;

    /** 玩家所有手牌状态（分牌后可能包含多手）。 */
    private List<HandDTO> playerHands;

    /** 当前轮到操作的手牌下标。 */
    private int activeHandIndex;

    /** 庄家牌面；玩家回合通常会隐藏第一张暗牌。 */
    private List<String> dealerCards;

    /** 庄家当前点数；玩家回合时通常为 null。 */
    private Integer dealerScore;

    /** 当前用户积分余额。 */
    private long chips;

    /** 已购买保险金额；未购买时为 null。 */
    private Long insurance;

    /** 当前可执行动作列表（如 HIT/STAND/DOUBLE/SPLIT/INSURANCE）。 */
    private List<String> actions;

    /** 结算结果列表；未结算阶段为 null。 */
    private List<HandResultDTO> results;
}
