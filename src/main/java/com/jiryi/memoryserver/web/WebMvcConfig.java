package com.jiryi.memoryserver.web;

import com.jiryi.memoryserver.config.MemoryProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配：CORS 与拦截器顺序
 *
 * 拦截器执行顺序即注册顺序，是安全语义的一部分：
 * 1. BodySizeInterceptor —— 最前，未鉴权也要先挡超大包，否则解析就消耗带宽；
 * 2. ResourceCountInterceptor —— 资源级 QPS；
 * 3. RateLimitInterceptor（公开路径）—— 按 IP；
 * 4. AuthInterceptor —— 写入登录态；
 * 5. RateLimitInterceptor（受保护路径）—— 按 userId。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** 无需登录态的路径 */
    private static final String[] PUBLIC_PATHS = {"/auth/login", "/auth/register"};

    /** 运维与容器内部路径：不参与限流埋点，否则探针轮询会把 ip:auth 配额打满 */
    private static final String[] OPS_PATHS = {"/actuator/**", "/error"};

    private final BodySizeInterceptor bodySizeInterceptor;

    private final ResourceCountInterceptor resourceCountInterceptor;

    private final RateLimitInterceptor rateLimitInterceptor;

    private final AuthInterceptor authInterceptor;

    private final MemoryProperties props;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(bodySizeInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(OPS_PATHS);

        registry.addInterceptor(resourceCountInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(OPS_PATHS);

        // 公开接口先按 IP 限流
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(PUBLIC_PATHS)
                .excludePathPatterns(OPS_PATHS);

        // 鉴权后再按用户维度限流
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(PUBLIC_PATHS)
                .excludePathPatterns(OPS_PATHS);

        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(PUBLIC_PATHS)
                .excludePathPatterns(OPS_PATHS);
    }

    /**
     * CORS：默认 * 仅为兼容本地开发；生产必须通过 memory.cors.allowed-origin-patterns
     * 收紧为具体前端域名，避免任意站点携带用户 token 发起跨域请求。
     * allowCredential(false) 与"Bearer 头而非 Cookie"的鉴权方式保持一致，避免 CSRF 面
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] patterns = props.getCors().getAllowedOriginPatterns()
                .toArray(String[]::new);
        registry.addMapping("/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
