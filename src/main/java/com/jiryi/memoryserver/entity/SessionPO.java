package com.jiryi.memoryserver.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 会话流水表：会话真源在 Redis（TTL 自动回收），本表用于留痕与 Redis 数据丢失时回落
 */
@Data
@TableName("t_session")
public class SessionPO {

    /** 不透明 token（64 位 hex），主键即鉴权入口 */
    @TableId(type = IdType.INPUT)
    private String token;

    private Long userId;

    /** 过期时间，Redis 不可用时以此字段判定有效性 */
    private OffsetDateTime expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
