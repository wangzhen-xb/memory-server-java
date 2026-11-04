package com.jiryi.memoryserver.common;

import lombok.Getter;

/**
 * 业务异常：由 GlobalExceptionHandler 统一转 Result，禁止把堆栈透给前端
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public static BizException badRequest(String msg) {
        return new BizException(ResultCode.BAD_REQUEST, msg);
    }

    public static BizException unauthorized(String msg) {
        return new BizException(ResultCode.UNAUTHORIZED, msg);
    }

    public static BizException notFound(String msg) {
        return new BizException(ResultCode.NOT_FOUND, msg);
    }

    public static BizException conflict(String msg) {
        return new BizException(ResultCode.CONFLICT, msg);
    }

    public static BizException locked(String msg) {
        return new BizException(ResultCode.LOCKED, msg);
    }
}
