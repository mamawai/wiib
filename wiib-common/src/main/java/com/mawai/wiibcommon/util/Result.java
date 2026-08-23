package com.mawai.wiibcommon.util;

import com.mawai.wiibcommon.enums.ErrorCode;
import lombok.Data;

@Data
public class Result<T> {

    private int code;
    private String msg;
    private T data;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.setCode(ErrorCode.SUCCESS.getCode());
        // 成功没有话可说：前端见 code=0 就取 data，从不读 msg
        result.setData(data);
        return result;
    }

    /**
     * 失败并附成文的话。
     * <p>
     * <b>没有 fail(ErrorCode) 这个重载</b>：枚举里存的是词表 key 不是文案，直接塞进 msg
     * 就会把 {@code error.balanceNotEnough} 原样发到界面上。带码的失败一律
     * {@code throw new BizException(码)}，由 {@code GlobalExceptionHandler} 按请求语言渲染。
     */
    public static <T> Result<T> fail(int code, String msg) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }

    public static <T> Result<T> fail(String msg) {
        Result<T> result = new Result<>();
        result.setCode(ErrorCode.SYSTEM_ERROR.getCode());
        result.setMsg(msg);
        return result;
    }
}
