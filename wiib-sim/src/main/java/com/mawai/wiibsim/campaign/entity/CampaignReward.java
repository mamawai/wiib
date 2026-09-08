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

    /**
     * PENDING 待领取 / CLAIMED 已授权待发 / SUCCESS 已到账 / FAILED 发放失败。
     * <p>
     * CLAIMED 无超时无自愈：发放中途进程重启或收尾写库失败，行会永远停在这里。
     * 救法是手工重置成 FAILED（可重领）：
     * <pre>
     * UPDATE campaign_reward SET status='FAILED', error_msg='人工重置：上次领取中断'
     * WHERE id = ? AND status='CLAIMED';
     * </pre>
     * 重置前确认活动仍是 SETTLING，否则用户点下去仍被拒（另一道闸 ldc.claim-days 默认十年，撞不上）。
     * 单号不变所以重置不会重复付款；<b>唯一不许做的是另起新单号</b>，见 {@link #outTradeNo}。
     */
    private String status;

    /**
     * WIIB_{campaignCode}_{userId}，固定可重算（生成器见
     * {@link com.mawai.wiibsim.campaign.service.CampaignSettleService#outTradeNo}）。
     * <p>
     * <b>人工补发必须原样复用这个单号</b>：防重复发放全架在服务端对它的唯一索引上，
     * FAILED 里混着其实已发成功的（judge 判据往窄收），换新单号补发就是双倍付款。
     * <b>出现过 SUCCESS 后绝不许删行重新结算</b>：重结算出的是同一批单号，
     * 已到账的人撞唯一索引拿的仍是上一轮金额。真要重算先换 campaign.code，那等于重新发一整轮钱。
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
