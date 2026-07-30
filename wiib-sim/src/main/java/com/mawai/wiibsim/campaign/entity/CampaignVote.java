package com.mawai.wiibsim.campaign.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("campaign_vote")
public class CampaignVote {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";

    public static final String WIN = "WIN";
    public static final String LOSE = "LOSE";
    /** 平盘：不计分、不计入当日正确票数，该日奖池整体顺延到次日 */
    public static final String DEFERRED = "DEFERRED";

    public static final String SYMBOL_BTC = "BTCUSDT";
    public static final String SYMBOL_GOLD = "XAUUSDT";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long campaignId;

    private Long userId;

    /** UTC 交易日 */
    private LocalDate voteDate;

    private String symbol;

    /** UP / DOWN */
    private String direction;

    /** WIN / LOSE / DEFERRED；null = 未结算 */
    private String result;

    /** 结算后回填的该票得分 */
    private BigDecimal score;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
