package com.zhangchi.gameasset.storage;

import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.common.util.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 本地磁盘存储实现（默认实现，{@code gameasset.storage.type=local}）。
 *
 * <p>默认路径 {@code D:/java/.tools/uploads/game-asset-hub}，由
 * {@code application.yml} 的 {@code gameasset.storage.local-path} 配置。</p>
 *
 * <p><b>两条硬约束</b>：</p>
 * <ol>
 *   <li><b>落盘名一律是 UUID + 扩展名</b>。原始文件名只进数据库。
 *       否则同一个资产上传两次同名文件会互相覆盖——而版本是不可变的，
 *       覆盖即静默丢数据，且没有任何报错。</li>
 *   <li><b>所有读写路径都经过 {@link #resolve}</b>，它会做规范化并校验
 *       结果仍在根目录内。否则 {@code fileKey} 里塞一个 {@code ../../}
 *       就能读写配置文件或系统文件。虽然 fileKey 由本服务生成、
 *       理论上可控，但存储层不该把安全建立在「调用方一定不会传坏值」上。</li>
 * </ol>
 *
 * @author 张忠驰
 */
@Slf4j
public class LocalStorageService implements StorageService {

    /** 存储根目录（绝对路径、已规范化） */
    private final Path root;

    public LocalStorageService(String localPath) {
        if (!StringUtils.hasText(localPath)) {
            throw new IllegalStateException("本地存储路径未配置：gameasset.storage.local-path");
        }
        this.root = Paths.get(localPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地存储目录：" + this.root, e);
        }
        log.info("[存储] 本地存储已启用，根目录={}", this.root);
    }

    @Override
    public String upload(MultipartFile file, String dir) {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件为空");
        }
        String originalName = FileUtil.requireValidName(file.getOriginalFilename());
        String relativePath = buildKey(dir, originalName);
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                // 直接流式拷贝落盘，不把内容读进堆：UE5 资产动辄数百 MB
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[存储] 写入失败 key={}", relativePath, e);
            throw new BizException(500, "文件保存失败：" + originalName);
        }
        log.debug("[存储] 已保存 {} → {}", originalName, relativePath);
        return relativePath;
    }

    @Override
    public String upload(Path source, String originalFilename, String dir) {
        if (source == null || !Files.isRegularFile(source)) {
            throw BizException.badRequest("待保存的源文件不存在");
        }
        String originalName = FileUtil.requireValidName(originalFilename);
        String relativePath = buildKey(dir, originalName);
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            // MOVE 优先：分片合并后的临时文件与本存储目录通常在同一盘，可以零拷贝
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(source);
            }
        } catch (IOException e) {
            log.error("[存储] 保存本地文件失败 key={}", relativePath, e);
            throw new BizException(500, "文件保存失败：" + originalName);
        }
        log.debug("[存储] 本地文件已归档 → {}", relativePath);
        return relativePath;
    }

    @Override
    public Resource download(String fileKey) {
        Path path = resolve(fileKey);
        if (!Files.isRegularFile(path)) {
            log.warn("[存储] 文件不存在 key={}", fileKey);
            throw BizException.notFound("文件不存在或已被清理");
        }
        return new FileSystemResource(path);
    }

    @Override
    public boolean delete(String fileKey) {
        if (!StringUtils.hasText(fileKey)) {
            return false;
        }
        try {
            return Files.deleteIfExists(resolve(fileKey));
        } catch (IOException e) {
            log.warn("[存储] 删除失败 key={} 原因={}", fileKey, e.getMessage());
            return false;
        }
    }

    /**
     * 本地存储没有对外可访问的 URL。
     *
     * <p>返回 null 而不是编一个 {@code file://} 路径：后者会把服务器目录结构
     * 暴露给前端，而且浏览器也打不开。前端拿到 null 就改用下载接口。</p>
     */
    @Override
    public String getUrl(String fileKey) {
        return null;
    }

    /**
     * 拼存储 key：{@code dir}/{UUID}.{ext}。
     * {@code dir} 为空时退化为单层，便于测试与非常规调用。
     */
    private String buildKey(String dir, String originalName) {
        String storeName = FileUtil.storeName(originalName, UUID.randomUUID().toString().replace("-", ""));
        String safeDir = sanitizeDir(dir);
        return safeDir.isEmpty() ? storeName : safeDir + "/" + storeName;
    }

    /**
     * 清洗目录前缀，仅保留安全的层级片段。
     *
     * <p>注意<b>不能</b>用 {@link FileUtil#safeBaseName}：那个方法的作用是
     * 「取最后一段」，会把 {@code 1/12} 压成 {@code 12}，正好丢掉
     * projectId/assetId 两级嵌套。目录清洗的语义是「逐段过滤」，
     * 丢掉 {@code .}、{@code ..}、空段与含分隔符的段。</p>
     */
    private String sanitizeDir(String dir) {
        if (!StringUtils.hasText(dir)) {
            return "";
        }
        StringBuilder safe = new StringBuilder();
        for (String segment : dir.replace('\\', '/').split("/")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty() || ".".equals(trimmed) || "..".equals(trimmed)) {
                continue;
            }
            if (!trimmed.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                continue;
            }
            if (safe.length() > 0) {
                safe.append('/');
            }
            safe.append(trimmed);
        }
        return safe.toString();
    }

    /**
     * 把 fileKey 解析成根目录下的真实路径，并挡住目录穿越。
     *
     * <p>{@code normalize()} 会把 {@code a/../../b} 折叠成 {@code ../b}，
     * 再判断是否仍在 root 内即可发现越界。这一步不能省。</p>
     */
    private Path resolve(String fileKey) {
        if (!StringUtils.hasText(fileKey)) {
            throw BizException.badRequest("文件标识不能为空");
        }
        Path resolved = root.resolve(fileKey.replace('\\', '/')).normalize();
        if (!resolved.startsWith(root)) {
            log.warn("[存储] 拒绝越界的文件标识 key={}", fileKey);
            throw BizException.forbidden("非法的文件路径");
        }
        return resolved;
    }
}
