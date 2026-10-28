package com.demo.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 资产状态流转请求（文档 4.2 {@code PATCH /api/assets/{id}/status}）。
 *
 * <p>请求体形如：</p>
 * <pre>
 * { "toStatus": "APPROVED", "remark": "模型结构合理，UV 需在下一版本优化" }
 * </pre>
 *
 * <p><b>打回必填审核意见</b>的校验放在 Service 层而不是用 {@code @NotBlank(groups=...)}：
 * 分组校验要在 Controller 上按目标状态切换 {@code groups}，而目标状态是请求体里的
 * 一个字段，注解层面拿不到。写在 Service 里反而只有一处、一眼可见，
 * 也不会因为「某个入口忘了指定分组」而漏校验。</p>
 *
 * @author 管理员
 */
@Data
public class AssetStatusDTO {

    /** 目标状态：PENDING / APPROVED / REJECTED / DEPRECATED / IN_PROGRESS */
    @NotBlank(message = "目标状态不能为空")
    private String toStatus;

    /** 变更说明 / 审核意见（打回到 REJECTED 时必填） */
    @Size(max = 500, message = "审核意见不能超过 500 个字符")
    private String remark;
}
