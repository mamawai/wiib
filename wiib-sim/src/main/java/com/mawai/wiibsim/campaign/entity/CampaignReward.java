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

    /**
     * WIIB_{campaignCode}_{userId}，固定可重算（生成器见
     * {@link com.mawai.wiibsim.campaign.service.CampaignSettleService#outTradeNo}）。
     * <p>
     * <b>【人工补发必须原样复用这一列的值，绝不能另起新单号】</b>防重复发放整个架在
     * 服务端对这个单号的唯一索引上：同一单号重发会撞唯一索引、被判成"此前已发放成功"，
     * 钱不会出去第二遍。换个新单号就绕过了这道锁 —— 而 FAILED 里恰恰混着
     * "其实已经发成功、只是响应没读到"的那些（LdcClient.judge 的判据是往窄了收的，
     * 宁可把真幂等漏判成 FAILED），对它们用新单号补发就是双倍付款。
     */
    private String outTradeNo;

    /** LDC 返回的 trade_no */
    private String externalRef;

    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
