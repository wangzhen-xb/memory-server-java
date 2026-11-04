package com.jiryi.memoryserver.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录/注册响应：token 为 Bearer 凭证，user 为脱敏后的用户信息
 */
@Data
@AllArgsConstructor
public class LoginVO {

    private String token;

    private UserVO user;
}
