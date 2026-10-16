package com.zhangchi.gameasset.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。
 *
 * @author 张忠驰
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "登录名不能为空")
    @Size(min = 3, max = 50, message = "登录名长度需在 3-50 个字符之间")
    @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "登录名只能包含字母、数字与下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6-32 个字符之间")
    private String password;

    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱不能超过 100 个字符")
    private String email;
}
