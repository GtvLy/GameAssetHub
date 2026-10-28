package com.demo.gameasset.controller;

import com.demo.gameasset.common.result.Result;
import com.demo.gameasset.dto.LoginDTO;
import com.demo.gameasset.dto.RegisterDTO;
import com.demo.gameasset.service.AuthService;
import com.demo.gameasset.vo.CurrentUserVO;
import com.demo.gameasset.vo.LoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>{@code /api/auth/login} 与 {@code /api/auth/register} 在
 * {@code WebMvcConfig} 的免登录白名单里；{@code /logout} 与 {@code /me}
 * 需要携带令牌（否则登出时取不到 jti，无法写黑名单）。</p>
 *
 * @author 管理员
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 注册 */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto) {
        authService.register(dto);
        return Result.success("注册成功，请登录", null);
    }

    /** 登录，返回 JWT */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success("登录成功", authService.login(dto));
    }

    /** 登出，把当前令牌的 jti 写入 Redis 黑名单 */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success("已退出登录", null);
    }

    /** 当前用户信息与参与的项目列表 */
    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        return Result.success(authService.currentUser());
    }
}
