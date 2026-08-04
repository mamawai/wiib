package com.mawai.wiibcommon.deprecate;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;

/**
 * 【封存】限流异常，复活方式见 {@link RateLimiterAspect} 类注释。
 */
public class RateLimitException extends BizException {

    public RateLimitException(String message) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED.getCode(), message);
    }

    public RateLimitException() {
        super(ErrorCode.RATE_LIMIT_EXCEEDED);
    }
}
