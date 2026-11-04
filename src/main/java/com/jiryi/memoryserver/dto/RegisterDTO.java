package com.jiryi.memoryserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册入参：校验规则与业务阈值同源（长度上限见 MemoryProperties），
 * 此处只设静态硬上限，动态阈值在 Service 层二次校验
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名长度不能超过 64")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 4, max = 128, message = "密码长度必须为 4-128")
    private String password;

    @Size(max = 64, message = "昵称长度不能超过 64")
    private String nickName;
}
