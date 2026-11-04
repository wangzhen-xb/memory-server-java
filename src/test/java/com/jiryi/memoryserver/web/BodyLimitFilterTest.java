package com.jiryi.memoryserver.web;

import com.jiryi.memoryserver.config.MemoryProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.InputStream;

/**
 * 请求体硬上限过滤器测试
 *
 * 重点是分块传输场景：客户端不声明 Content-Length 时 BodySizeInterceptor 放行，
 * 必须由过滤器按"实际读到的字节数"截断，并把超限标记打到请求属性上，
 * 让异常处理器能稳定回 413 而不是误判成"JSON 格式错误"的 400。
 */
class BodyLimitFilterTest {

    /** 把上限压到字节级，便于在单测里精确越线 */
    private static final long DEFAULT_LIMIT = 8L;

    private static final long CONVERSATION_LIMIT = 64L;

    @Test
    void overLimitBodyIsCutOffAndMarked() {
        MockHttpServletRequest request = request("PUT", "/memory", 9);

        IOException ex = Assertions.assertThrows(IOException.class,
                () -> new BodyLimitFilter(properties()).doFilter(request, new MockHttpServletResponse(), drainAllChain()));
        Assertions.assertTrue(ex.getMessage().contains("8"), "异常信息应带上阈值，便于排障");
        Assertions.assertEquals(Long.valueOf(DEFAULT_LIMIT), request.getAttribute(BodyLimitFilter.ATTR_TOO_LARGE),
                "超限标记打在请求属性上，不受 Jackson/Spring 包装层数影响");
    }

    @Test
    void bodyWithinLimitPassesThrough() throws Exception {
        MockHttpServletRequest request = request("PUT", "/memory", 8);

        new BodyLimitFilter(properties()).doFilter(request, new MockHttpServletResponse(), drainAllChain());

        Assertions.assertNull(request.getAttribute(BodyLimitFilter.ATTR_TOO_LARGE), "刚好等于上限不应判超限");
    }

    @Test
    void conversationPathUsesRelaxedLimit() throws Exception {
        // 同一段字节数走默认档会超限，走会话档必须放行，验证两档判定确实生效
        MockHttpServletRequest request = request("PUT", "/conversations", 16);

        new BodyLimitFilter(properties()).doFilter(request, new MockHttpServletResponse(), drainAllChain());

        Assertions.assertNull(request.getAttribute(BodyLimitFilter.ATTR_TOO_LARGE));
    }

    @Test
    void actuatorPathIsNotLimited() throws Exception {
        MockHttpServletRequest request = request("GET", "/actuator/health", 16);

        new BodyLimitFilter(properties()).doFilter(request, new MockHttpServletResponse(), drainAllChain());

        Assertions.assertNull(request.getAttribute(BodyLimitFilter.ATTR_TOO_LARGE), "运维端点不参与体积限制");
    }

    @Test
    void singleByteReadIsCountedToo() {
        // 逐字节读取（部分解析器的路径）同样不能绕过计数
        MockHttpServletRequest request = request("PUT", "/memory", 9);

        Assertions.assertThrows(IOException.class, () -> {
            BodyLimitFilter filter = new BodyLimitFilter(properties());
            filter.doFilter(request, new MockHttpServletResponse(), (rq, rs) -> {
                ServletInputStream in = ((jakarta.servlet.http.HttpServletRequest) rq).getInputStream();
                while (in.read() >= 0) {
                    // 模拟逐字节消费
                }
            });
        });
    }

    private static MockHttpServletRequest request(String method, String path, int bodyBytes) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setContentType("application/json");
        request.setContent(new byte[bodyBytes]);
        return request;
    }

    private static MemoryProperties properties() {
        MemoryProperties props = new MemoryProperties();
        props.setDefaultBodyBytes(DEFAULT_LIMIT);
        props.setConversationBodyBytes(CONVERSATION_LIMIT);
        return props;
    }

    /** 下游链把请求体读干净，等价于 Jackson 解析时的消费方式 */
    private static FilterChain drainAllChain() {
        return (rq, rs) -> {
            InputStream in = ((jakarta.servlet.http.HttpServletRequest) rq).getInputStream();
            while (in.read(new byte[4096]) >= 0) {
                // 持续读取直到 EOF 或超限抛出
            }
        };
    }
}
