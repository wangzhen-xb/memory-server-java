package com.jiryi.memoryserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis 键前缀外置：多环境共用一套实例时靠它隔离，避免键冲突
 */
@Data
@ConfigurationProperties(prefix = "redis.key")
public class RedisKeyProperties {

    /** 会话键前缀，完整键为 mem:sess:{token} */
    private String sessionPrefix = "mem:sess:";

    /** 登录失败计数键前缀，完整键为 mem:fail:{username} */
    private String loginFailPrefix = "mem:fail:";

    /** 锁定标记键前缀，完整键为 mem:lock:{username}，与计数键分开，避免误删 */
    private String loginLockPrefix = "mem:lock:";

    public String session(String token) {
        return sessionPrefix + token;
    }

    public String loginFail(String username) {
        return loginFailPrefix + username;
    }

    public String loginFailLock(String username) {
        return loginLockPrefix + username;
    }
}
