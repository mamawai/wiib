package com.mawai.wiibcommon.deprecate;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * 【封存】令牌桶限流切面。现役代码零 @RateLimiter 标注，已摘 @Component 不入 Spring 容器——
 * 挂着不接线会造成"以为有限流"的虚假安全感，故整栈退到本包封存。
 *
 * <p>预期复活场景：AI 助手对外开放时，给行为分析/对话这类 LLM 端点加按用户限流。复活清单：
 * ① 本类加回 @Component；② {@link RateLimiterType} 按现役场景重定义（旧值 BUY/SELL 是老股市残留）；
 * ③ 目标端点标 {@link RateLimiter}。lua 脚本仍在 resources/lua/token_bucket.lua 原路径，免改。
 * 注意 key 按登录用户推导，只适用于登录后端点；匿名流量限流应做在反向代理层。</p>
 */
@Aspect
@Slf4j
public class RateLimiterAspect {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> rateLimiterScript;

    public RateLimiterAspect(StringRedisTemplate stringRedisTemplate) throws Exception {
        this.stringRedisTemplate = stringRedisTemplate;

        // 加载Lua脚本
        this.rateLimiterScript = new DefaultRedisScript<>();
        this.rateLimiterScript.setResultType(Long.class);
        String scriptText = StreamUtils.copyToString(
                new ClassPathResource("lua/token_bucket.lua").getInputStream(),
                StandardCharsets.UTF_8);
        this.rateLimiterScript.setScriptText(scriptText);

        log.info("RateLimiterAspect initialized with token bucket lua script");
    }

    @Around("@annotation(rateLimiter)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimiter rateLimiter) throws Throwable {
        try {
            String key = getLimiterKey(rateLimiter.type());
            double permitsPerSecond = rateLimiter.permitsPerSecond();
            int bucketCapacity = rateLimiter.bucketCapacity();
            long now = System.currentTimeMillis();

            Long result = stringRedisTemplate.execute(
                    rateLimiterScript,
                    Collections.singletonList(key),
                    String.valueOf(permitsPerSecond),
                    String.valueOf(bucketCapacity),
                    String.valueOf(now),
                    "1"
            );

            if (result != 1) {
                log.warn("Rate limit exceeded for key: {}, method: {}",
                        key, joinPoint.getSignature().toShortString());
                throw new RateLimitException(rateLimiter.message());
            }
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.error("Rate limiter execution failed, fail open", e);
        }

        return joinPoint.proceed();
    }

    private static String getLimiterKey(RateLimiterType type) {
        String loginId = null;
        try {
            if (StpUtil.isLogin()) {
                loginId = StpUtil.getLoginIdAsString();
            }
        } catch (Exception e) {
            log.warn("Failed to get login id from StpUtil", e);
        }

        if (loginId == null || loginId.isBlank()) {
            throw new RateLimitException("用户登录状态异常，无法进行限流");
        }

        return "limiter:" + type.name() + ":" + loginId;
    }
}
