package com.mawai.wiibsim.campaign;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * LDC 分发接口配置。默认关：不配凭证也能启动，只是领取按钮不可用。
 * 做成 @ConfigurationProperties 为了单测里能 new 出来直接 set，脱离 Spring 测。
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
     * 过期后要补发得先调大这个值（或改 created_at），光重置状态不够——这道闸还在。
     */
    private int claimDays = 7;

    /** 开关开着且凭证齐全才算真启用 */
    public boolean ready() {
        return enabled && StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }
}
