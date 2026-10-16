package com.zhangchi.gameasset.storage;

import com.zhangchi.gameasset.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 分片合并器（文档 7.6：{@code complete} 时按序合并、校验 MD5）。
 *
 * <p><b>为什么从 Service 里独立出来</b>：合并这段逻辑的正确性完全由
 * 「顺序」与「数量」两件事决定，而这两件事最容易写错又最难在生产环境发现——
 * 少合一片，用户拿到的是一个损坏的 fbx，UE5 打不开，但服务端全程无报错。
 * 抽成不依赖 Spring / 数据库 / Redis 的纯静态工具后，
 * 可以用临时目录直接构造各种残缺分片组合来验证。</p>
 *
 * <p><b>顺序由下标决定，不由文件名排序决定</b>：
 * 若按文件名字典序合并，{@code 10.part} 会排到 {@code 2.part} 前面，
 * 10 片以上的文件必定损坏。这里显式按 {@code 0..total-1} 逐个取文件。</p>
 *
 * @author 张忠驰
 */
@Slf4j
public final class ChunkMerger {

    /** 分片文件名：{@code {index}.part}，与前端约定一致 */
    private static final Pattern CHUNK_PATTERN = Pattern.compile("^(\\d+)\\.part$");

    /** 合并缓冲区：64KB，顺序写大文件时的常用档位 */
    private static final int BUFFER_SIZE = 64 * 1024;

    private ChunkMerger() {
    }

    /**
     * 合并结果。
     *
     * @param md5        合并后整体内容的 MD5（合并时顺带算出，不额外读一遍文件）
     * @param size       总字节数
     * @param chunkCount 实际参与合并的分片数
     */
    public record MergeResult(String md5, long size, int chunkCount) {
    }

    /**
     * 把 {@code chunkDir} 下的分片按序合并到 {@code target}。
     *
     * @param chunkDir    分片所在目录（每个 uploadId 一个）
     * @param totalChunks 客户端声明并上传的分片总数，必须 &gt;= 1
     * @param target      合并目标文件，父目录不存在会自动创建
     * @return 合并结果（含整包 MD5 与字节数）
     * @throws BizException 分片缺失、多余、或读写失败时抛 400
     */
    public static MergeResult merge(Path chunkDir, int totalChunks, Path target) {
        validateArgs(chunkDir, totalChunks, target);

        // 1. 数量校验：缺片与多片都要拦住。
        //    只校验「缺片」是不够的——多出来的片说明客户端的切片逻辑与它声明的
        //    totalChunks 不一致，合并结果同样不可信，宁可失败也不能入库一个坏文件。
        List<Integer> present = listChunkIndexes(chunkDir);
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (!present.contains(i)) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            throw BizException.badRequest("分片不完整，缺少第 " + formatIndexes(missing)
                    + " 片（共需 " + totalChunks + " 片）");
        }
        List<Integer> extra = present.stream().filter(i -> i >= totalChunks).toList();
        if (!extra.isEmpty()) {
            throw BizException.badRequest("分片数量与声明不一致：声明 " + totalChunks
                    + " 片，却多出第 " + formatIndexes(extra) + " 片");
        }

        // 2. 按序合并 + 边写边算 MD5
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long totalSize = 0;
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                for (int i = 0; i < totalChunks; i++) {
                    Path part = chunkDir.resolve(i + ".part");
                    try (var in = Files.newInputStream(part)) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                            totalSize += read;
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("[分片合并] 失败 chunkDir={} totalChunks={}", chunkDir, totalChunks, e);
            // 合并中途失败会留下一个半截文件，必须清掉，否则下次调用可能复用它
            deleteQuietly(target);
            throw BizException.badRequest("分片合并失败：" + e.getMessage());
        }

        String md5 = toHex(digest.digest());
        log.info("[分片合并] 完成 chunkDir={} 片数={} 大小={}B md5={}",
                chunkDir.getFileName(), totalChunks, totalSize, md5);
        return new MergeResult(md5, totalSize, totalChunks);
    }

    /**
     * 列出目录里已上传的分片下标（非 {@code {n}.part} 的文件一律忽略）。
     */
    public static List<Integer> listChunkIndexes(Path chunkDir) {
        if (chunkDir == null || !Files.isDirectory(chunkDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(chunkDir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .map(CHUNK_PATTERN::matcher)
                    .filter(Matcher::matches)
                    .map(m -> Integer.parseInt(m.group(1)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[分片合并] 读取分片目录失败 chunkDir={} 原因={}", chunkDir, e.getMessage());
            return List.of();
        }
    }

    private static void validateArgs(Path chunkDir, int totalChunks, Path target) {
        if (totalChunks < 1) {
            throw BizException.badRequest("分片总数必须大于 0");
        }
        if (chunkDir == null || !Files.isDirectory(chunkDir)) {
            throw BizException.badRequest("上传会话不存在或已过期，请重新上传");
        }
        if (target == null) {
            throw BizException.badRequest("合并目标文件未指定");
        }
    }

    /** 把缺失/多余的下标拼成「0、2、5」这样的人话，便于前端定位是哪一片失败了 */
    private static String formatIndexes(List<Integer> indexes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indexes.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(indexes.get(i));
        }
        return sb.toString();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程的错误语义，交由定时任务兜底
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 MD5 摘要算法", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            chars[i * 2] = hex[b >>> 4];
            chars[i * 2 + 1] = hex[b & 0x0F];
        }
        return new String(chars);
    }
}
