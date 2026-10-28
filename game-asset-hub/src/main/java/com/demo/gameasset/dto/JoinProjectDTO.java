package com.demo.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 凭邀请码加入项目请求。
 *
 * @author 管理员
 */
@Data
public class JoinProjectDTO {

    @NotBlank(message = "邀请码不能为空")
    @Size(max = 64, message = "邀请码长度不能超过 64 个字符")
    private String inviteCode;
}
