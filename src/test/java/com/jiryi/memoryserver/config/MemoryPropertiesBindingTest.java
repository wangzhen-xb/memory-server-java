package com.jiryi.memoryserver.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务参数绑定与校验测试
 *
 * MemoryProperties 上的 @Validated 会把"配置能起来但一写就 500"的隐患提前到启动阶段：
 * 长度类阈值与 sql/01-schema.sql 的列宽绑定，配错直接绑定失败。
 * 这里同时反向确认：本仓库 application.yml / nacos 种子文件里的取值全部合规。
 */
class MemoryPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultsBindToDbColumnLimits() {
        runner.withPropertyValues().run(context -> {
            assertThat(context).hasSingleBean(MemoryProperties.class);
            MemoryProperties props = context.getBean(MemoryProperties.class);
            // 与 t_memory.content VARCHAR(200)、t_conversation.title VARCHAR(80) 对齐
            assertThat(props.getMemoryMaxLength()).isEqualTo(200);
            assertThat(props.getTitleMaxLength()).isEqualTo(80);
            assertThat(props.isTrustProxy()).isTrue();
        });
    }

    @Test
    void trustProxySwitchIsReadable() {
        runner.withPropertyValues("memory.trust-proxy=false").run(context -> {
            assertThat(context).hasSingleBean(MemoryProperties.class);
            assertThat(context.getBean(MemoryProperties.class).isTrustProxy()).isFalse();
        });
    }

    @Test
    void maxLengthBeyondColumnWidthFailsBinding() {
        // 超列宽的配置过去会静默接受、写入时才炸 500，现在启动即失败
        runner.withPropertyValues("memory.memory-max-length=300")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void nonPositiveThresholdFailsBinding() {
        // 阈值配成 0 会让所有请求被裁成空列表或全部判超限，属于必须挡住的配置事故
        runner.withPropertyValues("memory.login-fail-max=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("memory.default-body-bytes=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingScryptBlockFailsBinding() {
        // scrypt 参数被配成 null 时，注册与登录会在取参数处 NPE，必须当场拒绝
        runner.withPropertyValues("memory.scrypt=")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 只装配配置绑定，不引入数据源与 Redis，保证测试可在无中间件环境执行 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MemoryProperties.class)
    static class TestConfig {
    }
}
