package com.jiryi.memoryserver.common;

/**
 * 业务码常量：与 HTTP 状态码保持同语义段，便于前端统一处理
 */
public final class ResultCode {

    /** 参数校验不通过 */
    public static final int BAD_REQUEST = 400;
    /** 未登录或登录已过期 */
    public static final int UNAUTHORIZED = 401;
    /** 账号或密码错误 */
    public static final int BAD_CREDENTIALS = 401;
    /** 请求体超过分级上限 */
    public static final int PAYLOAD_TOO_LARGE = 413;
    /** 资源不存在 */
    public static final int NOT_FOUND = 404;
    /** 用户名已存在 */
    public static final int CONFLICT = 409;
    /** 账号被临时锁定 */
    public static final int LOCKED = 423;
    /** 触发限流 */
    public static final int TOO_MANY_REQUESTS = 429;
    /** 服务器内部错误 */
    public static final int SERVER_ERROR = 500;

    private ResultCode() {
    }
}
