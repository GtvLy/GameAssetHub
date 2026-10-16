package com.zhangchi.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建资产请求（{@code multipart/form-data} 里的 {@code meta} 部分，文档 4.2）。
 *
 * <p>新建资产<b>必须同时给出首个版本</b>——一个没有任何版本的资产在业务上
 * 没有意义（详情页、时间线、下载全都是空的），因此这个 DTO 同时承担
 * 首个版本的元数据。</p>
 *
 * @author 张忠驰
 */
@Data
public class AssetCreateDTO {

    @NotNull(message = "请选择所属分类")
    private Long categoryId;

    @NotBlank(message = "资产名称不能为空")
    @Size(max = 200, message = "资产名称不能超过 200 个字符")
    private String name;

    @Size(max = 2000, message = "资产说明不能超过 2000 个字符")
    private String description;

    /** P0 / P1 / P2，不传按 DDL 默认 P2 */
    private String priority;

    /** 标签，逗号分隔，如「场景,道具」 */
    @Size(max = 300, message = "标签不能超过 300 个字符")
    private String tags;

    // ==================== 首个版本的元数据 ====================

    @Size(max = 100, message = "版本备注名不能超过 100 个字符")
    private String versionName;

    @Size(max = 500, message = "变更说明不能超过 500 个字符")
    private String changeLog;

    /** 面数（模型类资产） */
    private Integer polyCount;

    /** 贴图尺寸，如 2048x2048 */
    @Size(max = 50, message = "贴图尺寸不能超过 50 个字符")
    private String textureSize;

    @Size(max = 500, message = "缩略图地址不能超过 500 个字符")
    private String thumbnailUrl;

    // ==================== 秒传（不传文件时使用） ====================

    /**
     * 客户端预先算好的文件 MD5。
     *
     * <p>两种用途：一是秒传时用它去命中已有文件（此时请求里<b>没有</b> file 部分）；
     * 二是带上文件一起传时，作为服务端计算结果的校验依据。</p>
     */
    @Size(max = 64, message = "MD5 不能超过 64 个字符")
    private String fileMd5;

    /** 秒传命中时用于回填的原始文件名（无文件上传时必填） */
    @Size(max = 255, message = "文件名不能超过 255 个字符")
    private String fileName;
}
