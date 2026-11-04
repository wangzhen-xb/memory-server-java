package com.jiryi.memoryserver.vo;

import com.jiryi.memoryserver.entity.ConversationPO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 会话响应：ISO 字符串供展示，epoch 毫秒供客户端做同步游标，二者并存避免前端二次解析
 */
@Data
@AllArgsConstructor
public class ConversationVO {

    private String id;

    private String title;

    private String payload;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    /** 供客户端比较用的 epoch 毫秒，由 updatedAt 派生，不落库 */
    private Long updatedAtMs;

    public static ConversationVO from(ConversationPO po) {
        OffsetDateTime updated = po.getUpdatedAt();
        long ms = updated == null ? 0L : updated.toInstant().toEpochMilli();
        return new ConversationVO(po.getId(), po.getTitle(), po.getPayload(),
                po.getCreatedAt(), updated, ms);
    }
}
