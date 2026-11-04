package com.jiryi.memoryserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 长期记忆 + 登录 + 会话同步服务入口
 * 技术栈：SpringBoot 3 + MyBatis-Plus + PostgreSQL + Redis + Nacos(配置) + Sentinel(限流)
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.jiryi.memoryserver.config")
@EnableScheduling
public class MemoryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryServerApplication.class, args);
    }
}
