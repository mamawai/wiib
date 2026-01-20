package com.mawai.wiibservice.config;

import cn.dev33.satoken.exception.NotLoginException;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<?> handleBizException(BizException e) {
        log.warn("业务异常: {}", e.getMsg());
        return Result.fail(e.getCode(), e.getMsg());
    }

    @ExceptionHandler(NotLoginException.class)
    public Result<?> handleNotLoginException(NotLoginException e) {
        String message = switch (e.getType()) {
            case NotLoginException.NOT_TOKEN -> "未提供token";
            case NotLoginException.INVALID_TOKEN -> "token无效";
            case NotLoginException.TOKEN_TIMEOUT -> "token已过期，请重新登录";
            case NotLoginException.BE_REPLACED -> "token已被顶下线";
            case NotLoginException.KICK_OUT -> "token已被踢下线";
            case NotLoginException.TOKEN_FREEZE -> "token已被冻结";
            case NotLoginException.NO_PREFIX_MESSAGE -> "未按照指定前缀提交token";
            default -> "当前会话未登录";
        };
        log.warn("登录异常: type={}, message={}", e.getType(), message);
        return Result.fail(ErrorCode.UNAUTHORIZED.getCode(), message);
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常: ", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }
}
