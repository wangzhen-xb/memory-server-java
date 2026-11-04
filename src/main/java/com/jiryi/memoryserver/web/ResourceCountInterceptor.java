package com.jiryi.memoryserver.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 资源级 QPS 埋点：按请求路径映射 Sentinel 资源名并统一 entry
 *
 * 为什么不用 @SentinelResource 注解：注解由 SentinelResourceAspect 再次埋点，
 * 与拦截器形成"同一请求两次 entry"，QPS 被放大一倍、异常比例也被重复统计。
 * 资源名改由路径推导后，埋点只有拦截器一处，且名称与 sentinel/flow-rules.json 保持一致。
 *
 * 本拦截器注册位置最靠前（除体积拦截器外），因而 afterCompletion 最后执行，
 * 由它统一关闭本次请求全部 Entry：先让 GlobalExceptionHandler 完成异常登记，
 * 再收尾统计，异常比例/降级规则才能拿到上下文。
 */
@Component
public class ResourceCountInterceptor implements HandlerInterceptor {

    /** 未命中任何规则路径时的兜底资源（如 /me） */
    private static final String DEFAULT_RESOURCE = "auth-api";

    private static final String RES_AUTH = "auth-api";

    private static final String RES_MEMORY = "memory-api";

    private static final String RES_CONVERSATION = "conversation-sync";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        SentinelGuard.track(request, resourceName(request.getServletPath()), "all");
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 清理必须幂等：同一请求只会走到这里一次，但不能依赖这一点
        SentinelGuard.release(request);
    }

    /** 路径到资源名的一一映射，与 flow-rules.json 中的 resource 字段对齐 */
    private static String resourceName(String servletPath) {
        if (servletPath == null || servletPath.isEmpty()) {
            return DEFAULT_RESOURCE;
        }
        if (servletPath.startsWith("/auth") || "/me".equals(servletPath)) {
            return RES_AUTH;
        }
        if (servletPath.startsWith("/memory")) {
            return RES_MEMORY;
        }
        if (servletPath.startsWith("/conversations")) {
            return RES_CONVERSATION;
        }
        return DEFAULT_RESOURCE;
    }
}
