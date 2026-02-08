package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("blackjack_account")
public class BlackjackAccount {

    /** 主键 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID（全局唯一，一人一个账户）。 */
    private Long userId;

    /** 当前可用积分。 */
    private Long chips;

    /** 当日已转出积分。 */
    private Long todayConverted;

    /** 上次转出日期（用于判断 todayConverted 是否跨天清零）。 */
    private LocalDate lastConvertDate;

    /** 上次积分保底重置日期。 */
    private LocalDate lastResetDate;

    /** 历史累计完成局数。 */
    private Long totalHands;

    /** 历史累计净赢积分。 */
    private Long totalWon;

    /** 历史累计净输积分。 */
    private Long totalLost;

    /** 历史单局最大净赢积分。 */
    private Long biggestWin;

    /** 创建时间（插入时自动填充）。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（插入/更新时自动填充）。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
