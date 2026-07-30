package com.mawai.wiibsim.campaign.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("campaign_reward")
public class CampaignReward {

    public static final String PENDING = "PENDING";
    public static final String CLAIMED = "CLAIMED";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long campaignId;

    private Long userId;

    /** 领取时二次 OAuth 拿到的最新值，不取 user 表存量值 */
    private String linuxDoId;

    private String username;

    private Integer tradeScore;

    private Integer dailyScore;

    private BigDecimal voteScore;

    /** 强平扣分，负数 */
    private Integer penalty;

    /** max(0, 四项之和) */
    private BigDecimal finalScore;

    private BigDecimal ldcAmount;

    private String status;

    /** WIIB_{campaignCode}_{userId}，固定可重算 */
    private String outTradeNo;

    /** LDC 返回的 trade_no */
    private String externalRef;

    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
