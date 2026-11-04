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
 * 用户表：password 为 scrypt 哈希，绝不存明文
 */
@Data
@TableName("t_user")
public class UserPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，全表唯一（部分唯一索引 WHERE deleted = 0） */
    private String username;

    /** scrypt 哈希，格式 saltHex:hashHex 或 {scrypt}saltHex:hashHex */
    private String password;

    private String nickName;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
