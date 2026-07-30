package com.mawai.wiibsim.campaign;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 活动模块的包内自注册。
 * <p>
 * 【为什么不去改 WiibSimApplication 的 @MapperScan】活动结束要整包 rm 掉，
 * 改主类会在删包后留一条悬空扫描路径，也让"整包可删"这个承诺不再成立。
 * 多个 @MapperScan 是叠加的（各自注册独立的 MapperScannerConfigurer），
 * 主类那份扫 com.mawai.wiibsim.mapper 与 wiibcommon.mapper，这份只扫活动自己的。
 * 本类能被发现是因为主类 scanBasePackages 覆盖了 com.mawai.wiibsim 整棵树。
 */
@Configuration
@MapperScan("com.mawai.wiibsim.campaign.mapper")
public class CampaignConfig {
}
