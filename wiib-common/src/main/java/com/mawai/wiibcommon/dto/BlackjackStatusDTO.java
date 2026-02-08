package com.mawai.wiibcommon.dto;

import lombok.Data;

@Data
public class BlackjackStatusDTO {

    /** 当前可用积分余额。 */
    private long chips;

    /** 今日已转出的积分（仅统计当天）。 */
    private long todayConverted;

    /** 当前可转出的积分（通常为 chips - 初始保底积分，下限 0）。 */
    private long convertable;

    /** 今日积分转出上限。 */
    private long todayConvertLimit;

    /** 历史总局数。 */
    private long totalHands;

    /** 历史累计净赢积分。 */
    private long totalWon;

    /** 历史累计净输积分。 */
    private long totalLost;

    /** 历史单局最大净赢积分。 */
    private long biggestWin;

    /** 今日积分池剩余额度。 */
    private long dailyPool;

    /** 当前进行中的牌局快照；若无进行中牌局则为 null。 */
    private GameStateDTO activeGame;
}
