package com.jiryi.memoryserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiryi.memoryserver.entity.SessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;

import java.time.OffsetDateTime;

/**
 * 会话流水表：Redis 为主，此表仅留痕与回落
 */
@Mapper
public interface SessionMapper extends BaseMapper<SessionPO> {

    /**
     * 分批清理过期流水：按 expires_at 索引取一批主键再删，避免单语句一次性锁全表死行
     *
     * 一次删完会产生长事务 + 大量 UNDO，PG 上还会因死元组堆积让回落查询越来越慢；
     * 分批后每批一个短事务，批间释放锁，不与业务鉴权争抢
     *
     * @return 本批删除行数，等于 batchSize 说明还有下一批
     */
    @Delete("DELETE FROM t_session WHERE token IN ("
            + "SELECT token FROM t_session WHERE expires_at < #{before} ORDER BY expires_at LIMIT #{batchSize})")
    int deleteExpiredBatch(@Param("before") OffsetDateTime before, @Param("batchSize") int batchSize);
}
