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

    /** 领取期限（天），过期保持 CLAIMED，后台可手动补发或作废 */
    private int claimDays = 7;

    /** 开关开着且凭证齐全才算真启用 */
    public boolean ready() {
        return enabled && StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }
}
