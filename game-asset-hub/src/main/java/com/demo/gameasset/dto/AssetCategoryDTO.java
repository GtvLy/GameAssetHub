package com.demo.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 修改资产分类请求。
 *
 * @author 管理员
 */
@Data
public class AssetCategoryDTO {

    /** 父分类ID，0 或不传表示一级分类（支持二级） */
    private Long parentId;

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称不能超过 50 个字符")
    private String name;

    /**
     * 分类编码：资产编号的前缀，如 {@code CHR_SM} 生成 {@code CHR_SM_0001}。
     *
     * <p>限死为大写字母、数字与下划线：编码会直接拼进资产编号，
     * 团队沟通时会被口述与手写（「你看下 CHR_SM_0003」），
     * 出现空格、中文或小写都会让口述产生歧义。</p>
     */
    @NotBlank(message = "分类编码不能为空")
    @Size(max = 30, message = "分类编码不能超过 30 个字符")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
            message = "分类编码只能由大写字母、数字与下划线组成，且以字母开头，如 CHR_SM")
    private String code;

    @Size(max = 50, message = "图标名不能超过 50 个字符")
    private String icon;

    /** 主题色，不传时数据库默认 #409EFF */
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "颜色需为 #RRGGBB 格式")
    private String color;

    private Integer sortOrder;
}
