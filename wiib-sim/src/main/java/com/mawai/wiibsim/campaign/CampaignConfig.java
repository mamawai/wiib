package com.mawai.wiibsim.campaign;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 活动模块的包内自注册。
 * <p>
 * 不动主类的 @MapperScan，这么写为了活动整包可删；多个 @MapperScan 叠加，这份只扫活动自己的。
 */
@Configuration
@MapperScan("com.mawai.wiibsim.campaign.mapper")
public class CampaignConfig {
}
