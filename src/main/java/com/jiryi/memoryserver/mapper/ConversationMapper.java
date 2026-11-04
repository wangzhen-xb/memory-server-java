package com.jiryi.memoryserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiryi.memoryserver.entity.ConversationPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话明细表
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationPO> {

    /**
     * 批量 upsert 且"较新者胜"：多值 INSERT + ON CONFLICT 一次往返完成全量冲突判定，
     * 避免"先查后写"的竞态，以及循环内逐条打库的 N+1
     *
     * PG 对被 WHERE 过滤掉的冲突行不计入影响行数，因此返回值即实际生效条数；
     * 调用方必须保证 list 非空且 id 不重复（同一语句命中同一行两次会直接报错）
     *
     * @param userId 由服务端从登录态注入，客户端无法伪造归属
     */
    @Insert("<script>"
            + "INSERT INTO t_conversation (id, user_id, title, payload, deleted, created_at, updated_at) VALUES "
            + "<foreach collection='list' item='it' separator=','>"
            + "(#{it.id}, #{userId}, #{it.title}, #{it.payload}, 0, #{it.createdAt}, #{it.updatedAt})"
            + "</foreach>"
            + " ON CONFLICT (id) DO UPDATE SET "
            + "  title = EXCLUDED.title, "
            + "  payload = EXCLUDED.payload, "
            + "  deleted = 0, "
            + "  updated_at = EXCLUDED.updated_at "
            // 不再限制 deleted = 0：逻辑删除过的会话被重新同步时应复活，
            // 否则客户端"删除后又恢复"的会话永远写不回来
            + "WHERE t_conversation.user_id = #{userId} "
            + "  AND EXCLUDED.updated_at > t_conversation.updated_at"
            + "</script>")
    int upsertBatchIfNewer(@Param("list") List<ConversationPO> list, @Param("userId") Long userId);
}
