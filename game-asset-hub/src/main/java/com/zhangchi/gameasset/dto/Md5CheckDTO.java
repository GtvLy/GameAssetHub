package com.zhangchi.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 秒传检查请求（文档 4.2 {@code POST /api/assets/upload/check-md5}）。
 *
 * @author 张忠驰
 */
@Data
public class Md5CheckDTO {

    /** 文件 MD5（32 位小写十六进制） */
    @NotBlank(message = "MD5 不能为空")
    @Pattern(regexp = "^[0-9a-fA-F]{32}$", message = "MD5 必须是 32 位十六进制字符串")
    private String md5;

    /** 原始文件名，仅用于回显给前端展示「已秒传 xxx」 */
    private String fileName;

    /** 文件大小，仅用于前端展示 */
    private Long fileSize;
}
