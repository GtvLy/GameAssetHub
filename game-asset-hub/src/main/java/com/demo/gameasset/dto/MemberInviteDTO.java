package com.demo.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 邀请成员请求。
 *
 * @author 管理员
 */
@Data
public class MemberInviteDTO {

    /** 被邀请人的用户名或邮箱，二者任一匹配即可 */
    @NotBlank(message = "请输入被邀请人的用户名或邮箱")
    @Size(max = 100, message = "账号长度不能超过 100 个字符")
    private String account;

    /** 项目内角色，为空时默认 ARTIST */
    private String role;
}
