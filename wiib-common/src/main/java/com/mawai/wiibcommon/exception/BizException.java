package com.mawai.wiibcommon.exception;

import com.mawai.wiibcommon.enums.ErrorCode;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final int code;
    private final String msg;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
        this.msg = errorCode.getMsg();
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
        this.msg = msg;
    }

    public BizException(String msg) {
        super(msg);
        this.code = ErrorCode.SYSTEM_ERROR.getCode();
        this.msg = msg;
    }
}
