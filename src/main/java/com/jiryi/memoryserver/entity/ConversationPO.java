package com.jiryi.memoryserver.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 聊天明细：整条会话以 JSON 字符串存 payload（含图片 base64，可达数 MB）
 * id 为客户端生成的会话 ID，因此主键策略是 INPUT 而非自增
 */
@Data
@TableName("t_conversation")
public class ConversationPO {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long userId;

    private String title;

    /** 会话完整 JSON，服务端不解析内容，只做透传存储 */
    private String payload;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    /**
     * 创建时间由业务层从客户端 epoch 换算后显式赋值，此处不加 FieldFill.INSERT，
     * 避免自动填充器覆盖同步过来的历史时间戳
     */
    private OffsetDateTime createdAt;

    /** 同步冲突判定依据：较新者胜 */
    private OffsetDateTime updatedAt;
}
