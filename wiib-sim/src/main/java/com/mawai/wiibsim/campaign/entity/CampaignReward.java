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
     * <b>【运维：卡在 CLAIMED 的行怎么救】</b>CLAIMED 是"已经抢到领取权、发放请求在飞"的中间态。
     * 发放最坏要 ~2 分钟（8 次重试 × 15s 超时），这段时间里进程被重启 / 发版 / 杀掉，
     * 或者收尾那条 markSuccess / markFailed 自己失败了，这一行就会永远停在 CLAIMED ——
     * 而 casClaim 只收 PENDING 与 FAILED，claim() 又直接拒 CLAIMED，
     * 用户从此只看得到"上一次领取正在处理中，请稍后再看"，没有超时、没有自愈、点多少次都一样。
     * 手工重置成 FAILED 即可（FAILED 是可重领的）：
     * <pre>
     * UPDATE campaign_reward SET status='FAILED', error_msg='人工重置：上次领取中断'
     * WHERE id = ? AND status='CLAIMED';
     * </pre>
     * <b>【重置完还得过两道闸，否则用户点下去仍是死路】</b>claim() 在看状态之前还判两件事：
     * <ul>
     *   <li>活动必须还是 SETTLING —— 翻成 DONE 之后领取入口整个关闭，报"活动尚未结算，暂不可领取"；</li>
     *   <li>{@code created_at + ldc.claim-days}（默认 7 天）不能过 —— 过了报"领取期限已过，请联系管理员"。
     *       第 8 天才重置的话用户撞的就是这句，得先把 ldc.claim-days 调大（或改 created_at）。</li>
     * </ul>
     * 重置前先 SELECT 一眼这两个值，别让用户在"以为修好了"之后再撞第二堵墙。
     * <p>
     * <b>【为什么这么做不会重复付款】</b>out_trade_no 一个字都没动。那次中断的请求如果其实已经
     * 发成功了，用户重领时同一单号会撞上服务端的唯一索引、被判成"此前已发放成功"（SUCCESS），
     * 钱不会出去第二遍。所以重置只会让人重新走一遍流程，不会多花钱。
     * <p>
     * <b>【唯一不许做的事】</b>别为人工补发另起一个新的 out_trade_no —— 那是全套流程里
     * 唯一真会双倍付款的操作，理由见下面 {@link #outTradeNo} 那段。
     */
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
     * <p>
     * <b>【同理：只要出现过 SUCCESS，就绝不许 DELETE 掉 reward 行重新结算】</b>删表重结看着是"从头再来"，
     * 实际不是：单号只由 {@code WIIB_{campaignCode}_{userId}} 决定，重结算出来的是<b>同一批单号</b>，
     * 而服务端的唯一索引记着上一轮的那笔。于是每个已到账的人重领时都会撞 duplicate key、
     * 被判成"此前已发放成功"，页面告诉他"已到账"—— 可服务端付的是<b>上一轮那个金额</b>，
     * 与新算出来的 ldc_amount 毫无关系；新加入的人则一分也拿不到差额。
     * 真要重算，先把 campaign.code 换一个（新单号 = 新账），并且认清那等于重新发一整轮钱。
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
