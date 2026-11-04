package com.jiryi.memoryserver.common;

/**
 * 登录态上下文：基于 ThreadLocal，由 AuthInterceptor 写入、afterCompletion 清除
 * 注意：仅在单次请求线程内有效，异步线程池场景需改用 TransmittableThreadLocal
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        CURRENT.set(userId);
    }

    /**
     * 取当前登录用户，未登录直接抛 401，杜绝空指针下游
     */
    public static Long requireUserId() {
        Long id = CURRENT.get();
        if (id == null) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        return id;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
