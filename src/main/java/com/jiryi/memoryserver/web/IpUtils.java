package com.jiryi.memoryserver.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端真实 IP 解析
 *
 * 风险提示：X-Forwarded-For 可被客户端伪造，只有在服务确实部署于可信代理（nginx）之后
 * 才有意义。是否读代理头由 memory.trust-proxy 控制：
 * - true（默认，保持现有部署行为）：确实位于 nginx 之后，取 XFF 首段；
 * - false：应用直接对外，只认容器给出的 remoteAddr，防止每请求伪造一个 IP 绕开按 IP 限流。
 */
public final class IpUtils {

    private static final String UNKNOWN = "unknown";

    private static final String[] HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP"
    };

    private IpUtils() {
    }

    /**
     * @param trustProxy 为 false 时完全忽略代理头，直接返回容器看到的对端地址
     */
    public static String clientIp(HttpServletRequest request, boolean trustProxy) {
        String forwarded = trustProxy ? firstForwarded(request) : null;
        if (forwarded != null) {
            return forwarded;
        }
        String remote = request.getRemoteAddr();
        return remote == null ? UNKNOWN : remote;
    }

    /** 命中任一代理头则返回首段，全部为空时返回 null 交由调用方回落 */
    private static String firstForwarded(HttpServletRequest request) {
        for (String header : HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !UNKNOWN.equalsIgnoreCase(value)) {
                // XFF 形如 "client, proxy1, proxy2"，首段才是真实客户端
                int comma = value.indexOf(',');
                String ip = comma > 0 ? value.substring(0, comma) : value;
                return ip.trim();
            }
        }
        return null;
    }
}
