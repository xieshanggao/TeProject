package com.fjf.teproject.domain;

/**
 * 携带 {@link SeckillErrorCode} 的业务异常。
 *
 * <p>由 Web 层的异常处理器统一转换为 HTTP 响应，使业务代码不需要
 * 直接依赖 Spring Web 类型。</p>
 */
public class SeckillException extends RuntimeException {

    private final SeckillErrorCode errorCode;

    public SeckillException(SeckillErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public SeckillErrorCode getErrorCode() {
        return errorCode;
    }
}
