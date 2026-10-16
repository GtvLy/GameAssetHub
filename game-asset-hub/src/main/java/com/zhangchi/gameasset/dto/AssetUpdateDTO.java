package com.zhangchi.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改资产信息请求（名称、描述、分类、优先级、标签）。
 *
 * <p>逐字段判空语义与模块1 的 {@code ProjectUpdateDTO} 一致：
 * <b>null 表示「不改这一项」，而不是「清空」</b>。
 * 若用 {@code BeanUtils.copyProperties} 整体覆盖，只想改个优先级
 * 就会把描述和标签一起抹掉——这类「保存后丢数据」的问题用户很难描述清楚。</p>
 *
 * @author 张忠驰
 */
@Data
public class AssetUpdateDTO {

    @NotBlank(message = "资产名称不能为空")
    @Size(max = 200, message = "资产名称不能超过 200 个字符")
    private String name;

    @Size(max = 2000, message = "资产说明不能超过 2000 个字符")
    private String description;

    /** 变更所属分类（null 表示不改） */
    private Long categoryId;

    /** P0 / P1 / P2（null 表示不改） */
    private String priority;

    @Size(max = 300, message = "标签不能超过 300 个字符")
    private String tags;
}
