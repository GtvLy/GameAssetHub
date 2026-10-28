package com.demo.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求。
 *
 * @author 管理员
 */
@Data
public class LoginDTO {

    @NotBlank(message = "登录名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
