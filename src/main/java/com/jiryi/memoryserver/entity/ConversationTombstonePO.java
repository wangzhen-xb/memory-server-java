package com.jiryi.memoryserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 会话删除墓碑：逻辑删除的行本身无法区分"本端删除"与"从未存在"，
 * 墓碑记录删除事实与时间，供其它客户端按 since 增量同步删除动作。
 */
@Data
@TableName("t_conversation_tombstone")
public class ConversationTombstonePO {

    /** 自增代理键：唯一性由 (user_id, conv_id) 唯一索引保证 */
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 被删除的会话 ID（客户端生成） */
    private String convId;

    /** 删除时间，作为增量同步游标 */
    private OffsetDateTime deletedAt;
}
