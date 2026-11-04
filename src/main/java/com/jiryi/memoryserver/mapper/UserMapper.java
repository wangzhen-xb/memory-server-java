package com.jiryi.memoryserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiryi.memoryserver.entity.UserPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表：查询统一走 BaseMapper + LambdaQueryWrapper 指定列，禁止 select *
 */
@Mapper
public interface UserMapper extends BaseMapper<UserPO> {
}
