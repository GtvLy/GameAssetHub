package com.demo.gameasset.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 上传新版本请求（{@code POST /api/assets/{id}/versions} 的 {@code meta} 部分）。
 *
 * <p>与 {@link AssetCreateDTO} 相比少了资产本身的信息（名称、分类等），
 * 因为那些属于资产主表，不会因为上传新版本而改变。</p>
 *
 * @author 管理员
 */
@Data
public class UploadVersionDTO {

    /** 版本备注名，不传时服务端按 V{版本号} 生成 */
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

    /**
     * 客户端预先算好的 MD5。请求里没有 file 部分时用它做秒传命中；
     * 有 file 时作为服务端计算结果的校验依据。
     */
    @Size(max = 64, message = "MD5 不能超过 64 个字符")
    private String fileMd5;

    /** 秒传命中时的原始文件名（无文件上传时必填） */
    @Size(max = 255, message = "文件名不能超过 255 个字符")
    private String fileName;
}
