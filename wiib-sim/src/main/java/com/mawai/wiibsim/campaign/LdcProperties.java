package com.mawai.wiibsim.campaign;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * LDC 分发接口配置。
 * <p>
 * 【为什么默认关】同 LinuxDoConfig.isEnabled() 的套路：不配凭证也能启动，
 * 本地开发和他人 clone 不会因缺配置起不来，只是领取按钮不可用。
 * <p>
 * 做成 @ConfigurationProperties 而非 @Value + @Getter，是为了能在单测里
 * new 出来直接 set —— LdcClient 的重试与判定逻辑必须能脱离 Spring 测。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ldc")
public class LdcProperties {

    /** 网关根域名。/epay 是另一套 Ed25519 签名的支付网关，与分发无关，别混 */
    private String baseUrl = "https://credit.linux.do";

    private String clientId = "";

    private String clientSecret = "";

    private boolean enabled = false;

    /**
     * 领取期限（天），从 campaign_reward.created_at 起算，过期后 claim() 直接拒。
     * <p>
     * 【过期的行是什么状态：PENDING 或 FAILED，绝不会是 CLAIMED】期限这道闸在
     * {@link com.mawai.wiibsim.campaign.service.CampaignClaimService#claim} 里排在任何状态变更之前，
     * 抛出去时那一行一个字都还没动。所以排查"卡在 CLAIMED"时别往过期上想，那是两回事
     * （CLAIMED 的成因与解法见 {@link com.mawai.wiibsim.campaign.entity.CampaignReward} 里 status 那一列的注释）。
     * <p>
     * 过期后要补发，得先把这个值调大（或改 created_at），光重置状态不够 —— 这道闸还在。
     */
    private int claimDays = 7;

    /** 开关开着且凭证齐全才算真启用 */
    public boolean ready() {
        return enabled && StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }
}
