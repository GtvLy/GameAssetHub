package com.zhangchi.gameasset.service.impl;

import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.common.util.FileUtil;
import com.zhangchi.gameasset.config.ChunkUploadProperties;
import com.zhangchi.gameasset.dto.ChunkInitDTO;
import com.zhangchi.gameasset.service.ChunkUploadService;
import com.zhangchi.gameasset.storage.ChunkMerger;
import com.zhangchi.gameasset.vo.ChunkInitVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 分片上传服务实现（文档 7.6）。
 *
 * <p>临时目录结构：{@code {tempPath}/{uploadId}/{index}.part}，
 * 合并产物为 {@code {tempPath}/{uploadId}/merged.tmp}。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@Service
public class ChunkUploadServiceImpl implements ChunkUploadService {

    /**
     * uploadId 的合法形态：32 位小写十六进制（UUID 去掉横线）。
     *
     * <p>这个校验是<b>安全边界</b>，不是格式洁癖：uploadId 会被拼进文件路径，
     * 若允许 {@code ../..}，调用方就能让服务端把分片写到任意目录。
     * 用白名单正则而不是 {@code replace("..", "")} 这类黑名单清洗——
     * 黑名单永远漏。</p>
     */
    private static final Pattern UPLOAD_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    /** 分片下标上限（按 5MB 估算约 5TB 的文件），挡住 index=Integer.MAX_VALUE 之类的怪请求 */
    private static final int MAX_CHUNK_INDEX = 1_000_000;

    /** 合并产物文件名，与 {@code {index}.part} 的命名不冲突，不会干扰分片计数 */
    private static final String MERGED_FILE_NAME = "merged.tmp";

    private final Path tempRoot;
    private final ChunkUploadProperties properties;

    public ChunkUploadServiceImpl(ChunkUploadProperties properties) {
        this.properties = properties;
        this.tempRoot = Paths.get(properties.getTempPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.tempRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建分片临时目录：" + this.tempRoot, e);
        }
        log.info("[分片上传] 临时目录={} 分片大小={}KB 残留保留={}小时",
                this.tempRoot, properties.getChunkSize() / 1024, properties.getExpireHours());
    }

    @Override
    public ChunkInitVO init(ChunkInitDTO dto) {
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        Path sessionDir = sessionDir(uploadId);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            log.error("[分片上传] 创建会话目录失败 uploadId={}", uploadId, e);
            throw new BizException(500, "创建上传会话失败，请重试");
        }

        ChunkInitVO vo = new ChunkInitVO();
        vo.setUploadId(uploadId);
        vo.setChunkSize(properties.getChunkSize());
        vo.setTotalChunks(dto.getTotalChunks());
        vo.setUploadedIndexes(ChunkMerger.listChunkIndexes(sessionDir));
        log.info("[分片上传] 会话已创建 uploadId={} 文件={} 大小={}B 分片数={}",
                uploadId, FileUtil.safeBaseName(dto.getFileName()),
                dto.getFileSize(), dto.getTotalChunks());
        return vo;
    }

    @Override
    public void saveChunk(String uploadId, Integer index, MultipartFile chunk) {
        if (chunk == null || chunk.isEmpty()) {
            throw BizException.badRequest("分片内容为空");
        }
        if (index == null || index < 0 || index > MAX_CHUNK_INDEX) {
            throw BizException.badRequest("非法的分片下标：" + index);
        }
        Path sessionDir = requireSessionDir(uploadId);
        Path target = sessionDir.resolve(index + ".part");
        try (InputStream in = chunk.getInputStream()) {
            // REPLACE_EXISTING：网络抖动导致某一片重传时直接覆盖，
            // 而不是报错让用户从头再来
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("[分片上传] 分片写入失败 uploadId={} index={}", uploadId, index, e);
            throw new BizException(500, "分片保存失败，请重试");
        }
        log.debug("[分片上传] 已接收 uploadId={} index={} 大小={}B", uploadId, index, chunk.getSize());
    }

    @Override
    public MergedChunkFile merge(String uploadId, Integer totalChunks, String expectedMd5) {
        Path sessionDir = requireSessionDir(uploadId);
        if (totalChunks == null || totalChunks < 1) {
            throw BizException.badRequest("分片总数必须大于 0");
        }
        Path target = sessionDir.resolve(MERGED_FILE_NAME);

        ChunkMerger.MergeResult result = ChunkMerger.merge(sessionDir, totalChunks, target);

        // 校验客户端声明的整包 MD5：它是「服务端拿到的字节是否等于用户本地文件」
        // 的最终判据。少了这一步，分片乱序/截断这类问题要到用户打开模型时才暴露。
        if (StringUtils.hasText(expectedMd5) && !expectedMd5.trim().equalsIgnoreCase(result.md5())) {
            deleteQuietly(target);
            log.warn("[分片上传] MD5 校验失败 uploadId={} 声明={} 实际={}",
                    uploadId, expectedMd5, result.md5());
            throw BizException.badRequest("整包 MD5 校验失败：客户端声明 " + expectedMd5
                    + "，服务端合并结果 " + result.md5() + "，请重新上传");
        }
        return new MergedChunkFile(target, sessionDir, result.md5(), result.size());
    }

    @Override
    public int cleanupExpired() {
        if (!Files.isDirectory(tempRoot)) {
            return 0;
        }
        Instant deadline = Instant.now().minus(properties.getExpireHours(), ChronoUnit.HOURS);
        int cleaned = 0;
        try (Stream<Path> sessions = Files.list(tempRoot)) {
            List<Path> candidates = sessions.toList();
            for (Path session : candidates) {
                if (!Files.isDirectory(session)) {
                    continue;
                }
                // 以目录的最后修改时间判断：分片每写一片都会刷新它，
                // 因此「24 小时没动过」等价于「这次上传已经没人管了」
                Instant lastTouched = Files.getLastModifiedTime(session).toInstant();
                if (lastTouched.isBefore(deadline)) {
                    deleteRecursively(session);
                    cleaned++;
                }
            }
        } catch (IOException e) {
            log.warn("[分片上传] 清理残留分片失败：{}", e.getMessage());
        }
        if (cleaned > 0) {
            log.info("[分片上传] 已清理 {} 个超过 {} 小时的残留会话", cleaned, properties.getExpireHours());
        }
        return cleaned;
    }

    // ==================== 内部工具 ====================

    /** 按 uploadId 拼会话目录（不校验存在性，用于 init） */
    private Path sessionDir(String uploadId) {
        return tempRoot.resolve(uploadId).normalize();
    }

    /** 取已存在的会话目录，不存在或 ID 非法一律 400 */
    private Path requireSessionDir(String uploadId) {
        if (!StringUtils.hasText(uploadId) || !UPLOAD_ID_PATTERN.matcher(uploadId).matches()) {
            throw BizException.badRequest("非法的上传会话标识");
        }
        Path dir = sessionDir(uploadId);
        // normalize 之后再确认仍在根目录内，双重保险
        if (!dir.startsWith(tempRoot) || !Files.isDirectory(dir)) {
            throw BizException.badRequest("上传会话不存在或已过期，请重新发起上传");
        }
        return dir;
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            // 先删文件后删目录：walk 是深度优先，反过来遍历就得到「子先父后」
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[分片上传] 临时文件清理失败 path={} 原因={}", path, e.getMessage());
        }
    }
}
