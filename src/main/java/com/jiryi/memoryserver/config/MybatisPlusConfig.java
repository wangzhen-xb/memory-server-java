package com.jiryi.memoryserver.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * MyBatis-Plus 配置：分页方言、全表更新拦截、审计字段自动填充
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 明确方言为 PG，不依赖运行时自动探测
        PaginationInnerInterceptor page = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        // 超出总页数直接返回空页，杜绝深翻页打穿数据库
        page.setOverflow(false);
        page.setMaxLimit(500L);
        interceptor.addInnerInterceptor(page);
        // 拦截不带 where 条件的 update/delete，防误伤全表
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }

    /**
     * created_at / updated_at 自动填充，业务代码不再手写时间戳
     */
    @Component
    public static class AuditFieldHandler implements MetaObjectHandler {

        @Override
        public void insertFill(MetaObject metaObject) {
            OffsetDateTime now = OffsetDateTime.now();
            strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
            strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
        }
    }
}
