package com.jiryi.memoryserver.web;

import com.jiryi.memoryserver.common.BizException;
import com.jiryi.memoryserver.common.ResultCode;
import com.jiryi.memoryserver.common.UserContext;
import com.jiryi.memoryserver.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bearer token 鉴权
 *
 * 白名单（/auth/login、/auth/register）由 WebMvcConfig 的 excludePathPatterns 控制，
 * 此处只处理需要登录态的请求。userId 写入 UserContext 供 Controller/Service 取用。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** Bearer 头解析，大小写不敏感 */
    private static final Pattern BEARER = Pattern.compile("^Bearer\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    /** 请求属性键：controller 侧取 token 用（登出需要） */
    public static final String ATTR_TOKEN = "memory.token";

    public static final String ATTR_USER_ID = "memory.userId";

    private final SessionService sessionService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS preflight (OPTIONS) never carries the Authorization header;
        // let it pass so Spring's built-in CORS handler returns 200.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        String token = extractToken(header);
        Optional<Long> userId = sessionService.resolveUserId(token);
        if (userId.isEmpty()) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        request.setAttribute(ATTR_TOKEN, token);
        request.setAttribute(ATTR_USER_ID, userId.get());
        UserContext.set(userId.get());
        return true;
    }

    /**
     * ThreadLocal 必须清理，否则 Tomcat 线程复用会把上一个用户泄漏给下一个请求
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private static String extractToken(String header) {
        if (header == null || header.isBlank()) {
            return "";
        }
        Matcher m = BEARER.matcher(header.trim());
        // 只抛 401 语义的异常，不区分"格式错"和"token 无效"，避免给攻击者提示
        return m.matches() ? m.group(1).trim() : "";
    }

    /** 供 Controller 读取当前请求携带的 token */
    public static String currentToken(HttpServletRequest request) {
        Object value = request.getAttribute(ATTR_TOKEN);
        if (value == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return String.valueOf(value);
    }
}
