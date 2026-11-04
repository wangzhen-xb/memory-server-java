package com.jiryi.memoryserver.web;

import com.jiryi.memoryserver.common.BizException;
import com.jiryi.memoryserver.common.ResultCode;
import com.jiryi.memoryserver.config.MemoryProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 请求体大小分级校验（Content-Length 快速拒绝）
 *
 * 放在拦截器最前、且在 Jackson 解析之前，只判 Content-Length：
 * Boot 的 server.tomcat.max-http-* 是全局单值，无法满足 /memory(64KB) 与
 * /conversations(64MB) 两档要求；靠读流计字节则会先消耗一遍带宽。
 *
 * 分块传输不声明 Content-Length，本拦截器看不到长度，硬上限由
 * BodyLimitFilter 按实际读到的字节数兜住，两者共用 limitFor 的同一档判定。
 */
@Component
@RequiredArgsConstructor
public class BodySizeInterceptor implements HandlerInterceptor {

    /** 会话同步路径，放宽上限 */
    private static final String CONVERSATION_PATH = "/conversations";

    private final MemoryProperties props;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long declared = request.getContentLengthLong();
        if (declared <= 0) {
            // 无 body 或分块传输，交后续解析环节处理（分块由 BodyLimitFilter 硬拦）
            return true;
        }
        long limit = limitFor(request.getServletPath(), props);
        if (declared > limit) {
            throw new BizException(ResultCode.PAYLOAD_TOO_LARGE,
                    "请求体超过上限 " + (limit / 1024) + "KB");
        }
        return true;
    }

    /** 分档上限：会话同步含图片 base64 单独放宽，其余走默认档；拦截器与过滤器共用 */
    static long limitFor(String servletPath, MemoryProperties props) {
        return CONVERSATION_PATH.equals(servletPath)
                ? props.getConversationBodyBytes()
                : props.getDefaultBodyBytes();
    }
}
