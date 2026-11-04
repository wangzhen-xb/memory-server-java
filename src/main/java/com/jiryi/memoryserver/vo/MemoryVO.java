package com.jiryi.memoryserver.vo;

import com.jiryi.memoryserver.entity.MemoryPO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 记忆项响应
 */
@Data
@AllArgsConstructor
public class MemoryVO {

    private Long id;

    private String content;

    /** 序列化为 ISO-8601，与原 Node 接口保持一致 */
    private OffsetDateTime createdAt;

    public static MemoryVO from(MemoryPO po) {
        return new MemoryVO(po.getId(), po.getContent(), po.getCreatedAt());
    }
}
