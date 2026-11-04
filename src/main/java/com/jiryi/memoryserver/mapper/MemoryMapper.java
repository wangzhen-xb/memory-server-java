package com.jiryi.memoryserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiryi.memoryserver.entity.MemoryPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 记忆表
 */
@Mapper
public interface MemoryMapper extends BaseMapper<MemoryPO> {

    /**
     * 批量插入：全量替换时一次往返写完，杜绝循环内逐条 insert
     * created_at 用 DB 默认值 now()，与迁移数据的原始时间戳无关（记忆按序重建即可）
     */
    @Insert("<script>"
            + "INSERT INTO t_memory (user_id, content, deleted, created_at, updated_at) VALUES "
            + "<foreach collection='list' item='it' separator=','>"
            + "(#{it.userId}, #{it.content}, 0, now(), now())"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<MemoryPO> list);

    /**
     * 物理删除该用户全部记忆
     *
     * 为什么不走 BaseMapper 的逻辑删除：记忆是全量替换语义，PUT 一次就重建整份列表，
     * 旧行没有任何留痕价值；UPDATE deleted=1 会每次替换都堆积死行
     * （30 条×高频写→一年后单用户万级死行），idx_memory_user_created 有效行密度持续下降。
     * 带 user_id 条件，不触全表，BlockAttackInnerInterceptor 不会拦截。
     */
    @Delete("DELETE FROM t_memory WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
