package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分片上传初始化结果（文档 7.6）。
 *
 * <p>{@link #chunkSize} 由服务端下发而不是前端写死 5MB：
 * 切片大小一旦两边不一致，合并出来的文件必然是坏的，
 * 而这种错误表现为「上传成功但模型打不开」，排查方向很容易跑偏。
 * 让服务端作为唯一来源，前端拿到什么就切什么。</p>
 *
 * @author 张忠驰
 */
@Data
public class ChunkInitVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 上传会话ID，后续 chunk / complete 都带它 */
    private String uploadId;

    /** 服务端要求的分片大小（字节） */
    private Integer chunkSize;

    /** 分片总数（回显客户端的声明值，便于前端核对） */
    private Integer totalChunks;

    /** 已存在的分片下标（断点续传时前端可跳过这些片） */
    private java.util.List<Integer> uploadedIndexes = new java.util.ArrayList<>();
}
