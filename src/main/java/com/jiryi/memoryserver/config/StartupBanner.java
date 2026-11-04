package com.jiryi.memoryserver.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * 启动完成后打印醒目的访问信息横幅，效果对齐前端 dev server（Vite 的 Local/Network 提示）。
 * 说明：这里刻意用 System.out 而不是 log.info——避免日志时间戳前缀破坏边框对齐，一眼就能看清。
 */
@Component
public class StartupBanner implements ApplicationListener<ApplicationReadyEvent> {

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        // 优先取 Web 容器实际绑定端口（兼容 PORT 环境变量、--server.port 传参等各种情况）
        int port = resolvePort(event, env);
        // 当前激活的配置档（local / cloud）
        String profile = String.join(",", env.getActiveProfiles());
        // 从进程启动到就绪的耗时，单位秒，保留一位小数
        double costSec = (System.currentTimeMillis() - event.getApplicationContext().getStartupDate()) / 1000.0;

        String local = "http://127.0.0.1:" + port;
        String network = resolveLanAddress(port);

        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator())
                .append("============================================================").append(System.lineSeparator())
                .append("  记忆服务启动成功   profile: ").append(profile.isEmpty() ? "default" : profile).append(System.lineSeparator())
                .append("  Local:    ").append(local).append(System.lineSeparator());
        if (network != null) {
            sb.append("  Network:  ").append(network).append(System.lineSeparator());
        }
        sb.append("  健康检查: ").append(local).append("/actuator/health").append(System.lineSeparator())
                .append("  主要接口: POST /auth/register | POST /auth/login | GET /me").append(System.lineSeparator())
                .append("            GET/PUT/DELETE /memory | GET/PUT /conversations").append(System.lineSeparator())
                .append("  启动耗时: ").append(String.format("%.1f", costSec)).append(" 秒").append(System.lineSeparator())
                .append("============================================================");
        System.out.println(sb);
    }

    /** 取实际服务端口：优先 Web 容器真实绑定值，取不到再回退配置文件里的 server.port（默认 8080 兜底） */
    private int resolvePort(ApplicationReadyEvent event, Environment env) {
        try {
            if (event.getApplicationContext() instanceof ServletWebServerApplicationContext ctx
                    && ctx.getWebServer() != null) {
                return ctx.getWebServer().getPort();
            }
        } catch (Exception ignored) {
            // 拿不到就回退配置值，横幅打印不应影响任何业务
        }
        return env.getProperty("server.port", Integer.class, 8080);
    }

    /** 局域网访问地址：本机 IP 解析失败时返回 null，只省略 Network 行，不影响整体输出 */
    private String resolveLanAddress(int port) {
        try {
            return "http://" + InetAddress.getLocalHost().getHostAddress() + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }
}
