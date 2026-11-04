package com.jiryi.memoryserver.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 业务参数外置：所有阈值来自 application.yml / Nacos，禁止代码硬编码
 *
 * 长度类阈值上限与 sql/01-schema.sql 的列宽对齐：配置比列宽大的值不会报错，
 * 而是在写入时超出 VARCHAR 长度引发 500，这里改为启动/刷新时就绑定失败，当场发现。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {

    /** 会话有效期，默认 30 天，与原 Node 实现一致 */
    @NotNull
    private Duration sessionTtl = Duration.ofDays(30);

    /** 单用户记忆条数上限 */
    @Positive
    private int memoryMaxCount = 30;

    /** 单条记忆最大长度，不得超过 t_memory.content VARCHAR(200) */
    @Positive
    @Max(200)
    private int memoryMaxLength = 200;

    /** 会话标题最大长度，不得超过 t_conversation.title VARCHAR(80) */
    @Positive
    @Max(80)
    private int titleMaxLength = 80;

    /** 不得超过 t_user.username VARCHAR(64) */
    @Positive
    @Max(64)
    private int usernameMaxLength = 64;

    @Positive
    private int passwordMinLength = 6;

    @Positive
    private int passwordMaxLength = 128;

    /** 不得超过 t_user.nick_name VARCHAR(64) */
    @Positive
    @Max(64)
    private int nicknameMaxLength = 64;

    /** 默认请求体上限（字节），对应 /memory 等轻量接口 */
    @Positive
    private long defaultBodyBytes = 64 * 1024L;

    /** 会话同步含图片 base64，单独放宽 */
    @Positive
    private long conversationBodyBytes = 64 * 1024 * 1024L;

    /** 单条会话 payload 的 UTF-8 字节上限：整包上限之外再按单条兜一层，防止个别超大会话拖垮整批 */
    @Positive
    private long conversationPayloadMaxBytes = 8 * 1024 * 1024L;

    /** 会话分页单页最大条数：客户端传超限值在此收敛，避免一次拉全量 */
    @Positive
    private int listPageMax = 500;

    /** 跨域配置：生产必须收紧为具体前端域名 */
    @NotNull
    @Valid
    private Cors cors = new Cors();

    /** 连续登录失败次数阈值 */
    @Positive
    private int loginFailMax = 5;

    /** 超过阈值后的锁定时长 */
    @NotNull
    private Duration loginFailLock = Duration.ofMinutes(15);

    /** 是否信任反向代理注入的 X-Forwarded-For 类头 */
    private boolean trustProxy = true;

    /** scrypt 参数，必须与 Node crypto.scryptSync 默认值一致，否则存量密码校验失败 */
    @NotNull
    @Valid
    private Scrypt scrypt = new Scrypt();

    /** 限流规则相关 */
    @NotNull
    @Valid
    private Sentinel sentinel = new Sentinel();

    @Data
    public static class Cors {
        /** 允许的来源模式；默认 * 仅为兼容本地开发，部署时应改为具体域名 */
        private java.util.List<String> allowedOriginPatterns = java.util.List.of("*");
    }

    @Data
    public static class Sentinel {
        /** 规则来源：local 读 classpath 内置 JSON；nacos 交由 Spring Cloud Alibaba datasource 托管 */
        @NotNull
        private RuleSource ruleSource = RuleSource.LOCAL;
    }

    /** 限流规则来源枚举，避免字符串拼写错误静默走默认值 */
    public enum RuleSource {
        LOCAL, NACOS
    }

    @Data
    public static class Scrypt {
        /** CPU/内存成本参数，Node 默认 16384 */
        @Positive
        private int n = 16384;
        /** 块大小因子，Node 默认 8 */
        @Positive
        private int r = 8;
        /** 并行因子，Node 默认 1 */
        @Positive
        private int p = 1;
        /** 派生密钥长度，Node 默认 64 字节 */
        @Positive
        private int dkLen = 64;
        /** 盐长度，Node 默认 16 字节 */
        @Positive
        private int saltBytes = 16;
    }
}
