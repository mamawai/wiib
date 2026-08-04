package com.mawai.wiibcommon.deprecate;

/**
 * 【封存】限流器类型。BUY/SELL 是老股市残留值，复活时按现役场景重定义
 * （如 AI_CHAT / BEHAVIOR_ANALYSIS），见 {@link RateLimiterAspect} 类注释。
 */
public enum RateLimiterType {
    BUY,
    SELL
}
