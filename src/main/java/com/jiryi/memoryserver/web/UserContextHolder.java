package com.jiryi.memoryserver.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求属性读取辅助：避免 Controller 里到处写字符串键与强转
 */
public final class UserContextHolder {

    private UserContextHolder() {
    }

    /** 取 AuthInterceptor 放入的 userId，未登录返回 null（由调用方决定处理方式） */
    public static Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return value instanceof Long id ? id : null;
    }
}
