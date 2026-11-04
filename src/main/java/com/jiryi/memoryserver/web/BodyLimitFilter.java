package com.jiryi.memoryserver.web;

import com.jiryi.memoryserver.config.MemoryProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 请求体字节硬上限过滤器
 *
 * 为什么拦截器不够：BodySizeInterceptor 只能读 Content-Length，客户端改用
 * Transfer-Encoding: chunked 就不声明长度，超大包会一路读到 Jackson 解析阶段，
 * 把整段 payload 连同样式全部堆进堆内存。DTO 上的条数上限（500 条）约束不了
 * 单条 payload 的长度，所以"条数限制"并不能兜住总字节数。
 *
 * 这里在过滤器阶段包一层计数字节流：真正读到的字节数越线立刻中断，
 * 声明长度与分块传输两种情况用同一个阈值封死，GET/无请求体时零开销（流是懒创建的）。
 * actuator 与 /error 不参与，避免健康检查被体积逻辑误伤。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class BodyLimitFilter extends OncePerRequestFilter {

    /** 超限标记键：异常处理器据此判定 413，不依赖 Jackson/Spring 的异常包装层数 */
    public static final String ATTR_TOO_LARGE = "memory.bodyTooLarge";

    private final MemoryProperties props;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/actuator") || path.startsWith("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long limit = BodySizeInterceptor.limitFor(request.getServletPath(), props);
        chain.doFilter(new LimitedBodyRequest(request, limit), response);
    }

    /** 请求包装：只在首次取流时套一层计数，其余属性全部原样委托 */
    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long limit;

        private ServletInputStream limitedStream;

        private BufferedReader limitedReader;

        LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limitedStream == null) {
                limitedStream = new CountingInputStream(super.getInputStream(), limit, this);
            }
            return limitedStream;
        }

        /** 用受限流构造 reader，保证 @RequestBody 之外的文本读取路径同样受约束 */
        @Override
        public BufferedReader getReader() throws IOException {
            if (limitedReader == null) {
                limitedReader = new BufferedReader(
                        new InputStreamReader(getInputStream(), resolveCharset()));
            }
            return limitedReader;
        }

        /** 声明的字符集不合法时回落 UTF-8，不能因为一个错字符集名抛 500 */
        private Charset resolveCharset() {
            String encoding = getCharacterEncoding();
            if (encoding == null || encoding.isBlank()) {
                return StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(encoding);
            } catch (Exception e) {
                return StandardCharsets.UTF_8;
            }
        }
    }

    /**
     * 计数字节输入流：就绪语义与 ReadListener 全部委托容器实现，
     * 只做一件事 —— 累计读到的字节数，越线打标并中断
     */
    private static final class CountingInputStream extends ServletInputStream {

        private final ServletInputStream source;

        private final long limit;

        private final HttpServletRequest request;

        /** 已读字节数，单请求单线程消费，无需原子类 */
        private long consumed;

        CountingInputStream(ServletInputStream source, long limit, HttpServletRequest request) {
            this.source = source;
            this.limit = limit;
            this.request = request;
        }

        @Override
        public int read() throws IOException {
            int b = source.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = source.read(b, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        /** 容器实现可能直接跳过缓冲区，必须计入，否则攻击者可用 skip 绕过统计 */
        @Override
        public long skip(long n) throws IOException {
            long skipped = source.skip(n);
            if (skipped > 0) {
                count(skipped);
            }
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return source.available();
        }

        @Override
        public void close() throws IOException {
            source.close();
        }

        @Override
        public boolean isReady() {
            return source.isReady();
        }

        @Override
        public boolean isFinished() {
            return source.isFinished();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            source.setReadListener(readListener);
        }

        private void count(long delta) throws IOException {
            consumed += delta;
            if (consumed > limit) {
                request.setAttribute(ATTR_TOO_LARGE, limit);
                throw new IOException("请求体超过上限 " + limit + " 字节");
            }
        }
    }
}
