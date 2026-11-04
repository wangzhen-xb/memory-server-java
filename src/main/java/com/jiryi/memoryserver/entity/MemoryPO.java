package com.jiryi.memoryserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 跨会话长期记忆：按用户归属，单条上限由 memory.memory-max-length 控制
 */
@Data
@TableName("t_memory")
public class MemoryPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String content;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    /**
     * 记忆走 insertBatch 自定义 SQL，时间戳由 DB default now() 生成，因此不配自动填充
     */
    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
