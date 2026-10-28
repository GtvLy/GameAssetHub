package com.demo.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 修改成员角色请求。
 *
 * @author 管理员
 */
@Data
public class MemberRoleDTO {

    /** OWNER / ARTIST / PROGRAMMER / DESIGNER / VIEWER */
    @NotBlank(message = "成员角色不能为空")
    private String role;
}
