package com.jiryi.memoryserver.controller;

import com.jiryi.memoryserver.common.Result;
import com.jiryi.memoryserver.common.UserContext;
import com.jiryi.memoryserver.dto.LoginDTO;
import com.jiryi.memoryserver.dto.RegisterDTO;
import com.jiryi.memoryserver.service.AuthService;
import com.jiryi.memoryserver.vo.LoginVO;
import com.jiryi.memoryserver.vo.UserVO;
import com.jiryi.memoryserver.web.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口
 * 入参统一 @Valid 校验，出参统一 Result 封装
 */
@RestController
@RequestMapping
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/auth/register")
    public Result<LoginVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.ok(authService.register(dto));
    }

    @PostMapping("/auth/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(authService.login(dto));
    }

    /** 当前登录用户，userId 由 AuthInterceptor 从会话解析后写入上下文，不接受客户端传入 */
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.currentUser(UserContext.requireUserId()));
    }

    @PostMapping("/auth/logout")
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout(AuthInterceptor.currentToken(request));
        return Result.ok();
    }
}
