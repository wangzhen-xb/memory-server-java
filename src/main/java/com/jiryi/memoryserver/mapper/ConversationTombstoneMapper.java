package com.jiryi.memoryserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiryi.memoryserver.entity.ConversationTombstonePO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 会话墓碑表：删除动作的跨端同步凭据
 *
 * 批量 UPSERT 保证同一 (user_id, conv_id) 只保留最新删除时间，
 * 重复提交（多端同时删、失败重试）天然幂等，不会命中二次更新报错。
 */
@Mapper
public interface ConversationTombstoneMapper extends BaseMapper<ConversationTombstonePO> {

    /**
     * 批量写入墓碑：冲突时取两者较大时间，保证"后一次删除"不被旧时间覆盖
     *
     * @param ids       会话 ID 列表，调用方需保证非空且无重复
     * @param userId    归属用户，由服务端登录态注入
     * @param deletedAt 删除时间（服务端时间，不采用客户端时间以免被伪造）
     */
    @Insert("<script>"
            + "INSERT INTO t_conversation_tombstone (user_id, conv_id, deleted_at) VALUES "
            + "<foreach collection='ids' item='it' separator=','>"
            + "(#{userId}, #{it}, #{deletedAt})"
            + "</foreach>"
            + " ON CONFLICT (user_id, conv_id) DO UPDATE SET "
            + "  deleted_at = GREATEST(t_conversation_tombstone.deleted_at, EXCLUDED.deleted_at)"
            + "</script>")
    int upsertBatch(@Param("ids") List<String> ids,
                    @Param("userId") Long userId,
                    @Param("deletedAt") OffsetDateTime deletedAt);

    /** 按删除时间增量拉取墓碑（升序，便于客户端顺序应用） */
    @Select("SELECT conv_id, deleted_at FROM t_conversation_tombstone "
            + "WHERE user_id = #{userId} AND deleted_at > #{since} "
            + "ORDER BY deleted_at ASC LIMIT #{limit}")
    List<ConversationTombstonePO> selectSince(@Param("userId") Long userId,
                                              @Param("since") OffsetDateTime since,
                                              @Param("limit") int limit);

    /** 会话被重新写入（复活）时清除对应墓碑，避免客户端继续把它当已删除 */
    @Delete("<script>"
            + "DELETE FROM t_conversation_tombstone WHERE user_id = #{userId} AND conv_id IN "
            + "<foreach collection='ids' item='it' open='(' separator=',' close=')'>#{it}</foreach>"
            + "</script>")
    int deleteByConvIds(@Param("userId") Long userId, @Param("ids") List<String> ids);
}
