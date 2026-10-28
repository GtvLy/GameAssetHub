package com.demo.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 变更项目状态请求（{@code PATCH /api/projects/{id}/status}）。
 *
 * @author 管理员
 */
@Data
public class ProjectStatusDTO {

    /** ACTIVE 进行中 / PAUSED 暂停 / ARCHIVED 已归档，合法性在服务层用 ProjectStatus 校验 */
    @NotBlank(message = "项目状态不能为空")
    private String status;
}
