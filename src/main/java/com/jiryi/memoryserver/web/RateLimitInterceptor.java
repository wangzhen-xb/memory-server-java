package com.jiryi.memoryserver.web;

import com.jiryi.memoryserver.config.MemoryProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Sentinel 埋点拦截器
 *
 * 同一个 Bean 在 WebMvcConfig 中注册两次，覆盖两类路径，因为可用维度不同：
 * 1. 登录/注册（无登录态）——只能按客户端 IP 做热点参数限流，防撞库；
 * 2. 其余接口（AuthInterceptor 之后）——按 userId 限流，防单用户刷接口。
 * 全局 QPS 由 flow-rules 中的 memory-api / auth-api 规则承担，此处只做维度埋点。
 *
 * 打开的 Entry 不在本拦截器关闭：统一由 ResourceCountInterceptor.afterCompletion
 * （注册更早，afterCompletion 反而最后执行）按后进先出收尾。
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final MemoryProperties props;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long userId = UserContextHolder.currentUserId(request);
        if (userId == null) {
            // 公开接口：按 IP
            SentinelGuard.track(request, SentinelGuard.RES_IP_AUTH, IpUtils.clientIp(request, props.isTrustProxy()));
            return true;
        }
        // 已鉴权：先过通用用户配额，再过接口专属配额
        SentinelGuard.track(request, SentinelGuard.RES_USER, userId);
        String resource = switch (request.getServletPath()) {
            case "/memory" -> SentinelGuard.RES_USER_MEMORY;
            case "/conversations" -> SentinelGuard.RES_USER_CONVERSATION;
            default -> null;
        };
        if (resource != null) {
            SentinelGuard.track(request, resource, userId);
        }
        return true;
    }
}
