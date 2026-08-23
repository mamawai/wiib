package com.mawai.wiibcommon.exception;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import lombok.Getter;

/**
 * 业务异常。两种造法，区别只在<b>话由谁来写</b>：
 * <ul>
 *   <li>带 {@link ErrorCode}：只传码，文案由 {@link GlobalExceptionHandler} 按当次请求的
 *       界面语言查词表渲染。绝大多数场景用这种。</li>
 *   <li>带 msg：话已经成文（调用方自己查过 {@code MessageCatalog}，或是拼了上游返回的原文），
 *       处理器原样下发。</li>
 * </ul>
 * 两者互斥：errorCode 非空时 msg 为空，反之亦然。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;
    /** 非空=文案走词表渲染 */
    private final ErrorCode errorCode;
    /** 非空=文案已成文，原样下发 */
    private final String msg;

    public BizException(ErrorCode errorCode) {
        // 异常自带的 message 给日志与堆栈用，这里就是 key——比中文好 grep，真正的话在处理器渲染
        super(errorCode.getMsgKey());
        this.code = errorCode.getCode();
        this.errorCode = errorCode;
        this.msg = null;
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
        this.errorCode = null;
        this.msg = msg;
    }

    public BizException(String msg) {
        this(ErrorCode.SYSTEM_ERROR.getCode(), msg);
    }

    /**
     * 成文给用户看的那句话：带码的按当次请求的界面语言查词表，带话的原样。
     * <p>
     * 凡是要把这个异常变成用户能读的字，都得走这里——{@link #getMessage()} 拿到的是 key
     * （给日志与堆栈用的），直接发到界面上就是一行 {@code error.xxx}。
     */
    public String render(MessageCatalog messages) {
        return errorCode != null ? messages.get(errorCode.getMsgKey()) : msg;
    }
}
