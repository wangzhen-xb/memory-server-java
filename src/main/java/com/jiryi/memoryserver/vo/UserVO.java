package com.jiryi.memoryserver.vo;

import com.jiryi.memoryserver.entity.UserPO;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 对外用户信息：绝不含 password 字段，从 PO 显式映射而非整体序列化
 */
@Data
@AllArgsConstructor
public class UserVO {

    private Long id;

    private String username;

    private String nickName;

    public static UserVO from(UserPO po) {
        if (po == null) {
            return null;
        }
        return new UserVO(po.getId(), po.getUsername(), po.getNickName());
    }
}
