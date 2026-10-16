package com.zhangchi.gameasset.service;

import com.zhangchi.gameasset.dto.ChunkInitDTO;
import com.zhangchi.gameasset.vo.ChunkInitVO;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * 分片上传服务（文档 7.6）。
 *
 * <p><b>服务端不保存分片会话状态</b>：{@code init} 只建目录并返回
 * {@code uploadId}，{@code totalChunks} 由 {@code complete} 再次传入并校验。
 * 这样做的好处是重启后未完成的上传仍然可以继续（断点续传），
 * 不需要引入 Redis 会话或数据库表——目录里的 {@code {index}.part}
 * 本身就是最可靠的状态记录。</p>
 *
 * @author 张忠驰
 */
public interface ChunkUploadService {

    /**
     * 初始化上传会话。
     *
     * <p>若该 uploadId 目录已存在则复用（同一 {@code init} 重放时不会丢已传分片），
     * 并把已上传的分片下标回给前端，支持断点续传。</p>
     */
    ChunkInitVO init(ChunkInitDTO dto);

    /**
     * 保存一个分片。
     *
     * @param uploadId 会话ID
     * @param index    分片下标，从 0 开始
     * @param chunk    分片内容
     */
    void saveChunk(String uploadId, Integer index, MultipartFile chunk);

    /**
     * 合并分片。
     *
     * @param uploadId    会话ID
     * @param totalChunks 分片总数
     * @param expectedMd5 客户端声明的整包 MD5，非空时与合并结果比对，不一致抛 400
     * @return 合并结果（含目标文件路径；调用方负责在入库后清理）
     */
    MergedChunkFile merge(String uploadId, Integer totalChunks, String expectedMd5);

    /**
     * 合并产物。
     *
     * @param path       合并后的临时文件
     * @param chunkDir   分片目录，入库结束后整体删除
     * @param md5        整包 MD5
     * @param size       字节数
     */
    record MergedChunkFile(Path path, Path chunkDir, String md5, long size) {
    }

    /** 清理超过配置时长的残留分片目录（定时任务调用），返回清理的会话数 */
    int cleanupExpired();
}
