package com.mawai.wiibcommon.exception;

import com.mawai.wiibcommon.enums.ErrorCode;

/**
 * 限流异常
 */
public class RateLimitException extends BizException {

    public RateLimitException(String message) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED.getCode(), message);
    }

    public RateLimitException() {
        super(ErrorCode.RATE_LIMIT_EXCEEDED);
    }
}
