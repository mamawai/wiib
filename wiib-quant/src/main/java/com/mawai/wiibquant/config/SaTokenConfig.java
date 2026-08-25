package com.mawai.wiibquant.config;

import com.mawai.wiibcommon.config.BaseSaTokenConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * Sa-Token 配置：白名单制，放行清单之外的接口一律需要登录。
 * quant 自己不签发登录（token 由 sim 签发，两进程共享同一份 Redis），
 * 所以放行的只有 swagger/error 这些静态门面，actuator 不放。
 */
@Configuration
public class SaTokenConfig extends BaseSaTokenConfig {

    public SaTokenConfig(StringRedisTemplate stringRedisTemplate) {
        super(stringRedisTemplate);
    }

    @Override
    protected List<String> getExcludePaths() {
        return getDefaultExcludePaths();
    }
}
