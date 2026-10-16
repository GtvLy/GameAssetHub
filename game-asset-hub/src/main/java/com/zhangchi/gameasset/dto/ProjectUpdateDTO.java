package com.zhangchi.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 修改项目信息请求（OWNER）。
 *
 * <p>字段留空表示「不修改该字段」而不是「清空」，因此服务层逐字段判空赋值，
 * 不用 {@code BeanUtils.copyProperties}——那会把 null 一并覆盖进去，
 * 导致只想改个名字却把简介清空。</p>
 *
 * @author 张忠驰
 */
@Data
public class ProjectUpdateDTO {

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 100, message = "项目名称不能超过 100 个字符")
    private String name;

    @Size(max = 50, message = "项目代号不能超过 50 个字符")
    private String code;

    private String description;

    @Size(max = 30, message = "引擎标识不能超过 30 个字符")
    private String engine;

    @Size(max = 500, message = "封面地址不能超过 500 个字符")
    private String coverUrl;

    private LocalDate startDate;

    private LocalDate planEndDate;
}
