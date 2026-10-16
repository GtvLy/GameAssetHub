package com.zhangchi.gameasset.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分片上传初始化请求（文档 7.6 的 {@code initChunkUpload}）。
 *
 * @author 张忠驰
 */
@Data
public class ChunkInitDTO {

    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @NotNull(message = "文件大小不能为空")
    @Min(value = 1, message = "文件大小必须大于 0")
    private Long fileSize;

    /** 分片总数，前端按 chunkSize 切片后算出 */
    @NotNull(message = "分片总数不能为空")
    @Min(value = 1, message = "分片总数必须大于 0")
    private Integer totalChunks;

    /** 整包 MD5（可选）。complete 阶段会用它校验合并结果 */
    private String md5;
}
