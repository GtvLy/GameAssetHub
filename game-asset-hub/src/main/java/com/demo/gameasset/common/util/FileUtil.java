package com.demo.gameasset.common.util;

import com.demo.gameasset.common.exception.BizException;

import java.util.Locale;

/**
 * 文件名处理工具。
 *
 * <p>刻意不引 Hutool / commons-io：本项目对文件名的需求只有
 * 「取扩展名」与「防目录穿越」两件事，自己写十行比引一个工具库更好维护，
 * 也更符合课程对依赖克制的要求。</p>
 *
 * <p><b>安全要点</b>：{@code MultipartFile#getOriginalFilename} 是<b>客户端可控</b>的，
 * 浏览器正常只会给文件名，但构造请求可以塞进 {@code ../../web.xml} 或
 * {@code C:\windows\x.txt}。存储层虽然把文件名重命名成 UUID，
 * 但只要有任何一处把它当路径拼接，就是一次任意文件写入。
 * 因此所有落盘路径一律经过 {@link #safeBaseName(String)} 清洗。</p>
 *
 * @author 管理员
 */
public final class FileUtil {

    /** 扩展名长度上限，与 {@code asset_version.file_ext VARCHAR(20)} 对齐 */
    private static final int MAX_EXT_LENGTH = 20;

    /** 无扩展名时的占位值 */
    public static final String NO_EXT = "";

    private FileUtil() {
    }

    /**
     * 取扩展名（小写，不含点）。{@code SM_Crate_A.FBX → fbx}；
     * 无扩展名或扩展名过长时返回空串（不抛异常，扩展名只是展示用的元数据）。
     */
    public static String ext(String fileName) {
        if (fileName == null) {
            return NO_EXT;
        }
        String name = safeBaseName(fileName);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return NO_EXT;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 扩展名里不该出现分隔符或空白，出现了说明这个文件名不正常，直接丢弃
        if (ext.length() > MAX_EXT_LENGTH || !ext.chars().allMatch(FileUtil::isExtChar)) {
            return NO_EXT;
        }
        return ext;
    }

    /**
     * 取安全的「基础文件名」：剥掉任何目录部分，只保留最后一段。
     *
     * <p>{@code ../../etc/passwd → passwd}；{@code C:\a\b.fbx → b.fbx}。</p>
     *
     * @return 清洗后的文件名；入参为空或清洗后为空串时抛 400
     */
    public static String safeBaseName(String fileName) {
        if (fileName == null) {
            return "";
        }
        // 统一分隔符后取最后一段：Windows 的 \ 与 Unix 的 / 都要处理
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String base = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return base.trim();
    }

    /**
     * 清洗并校验原始文件名，为空时抛 400。
     * 上传入口必须调用它，不能直接用 {@code getOriginalFilename()}。
     */
    public static String requireValidName(String originalFilename) {
        String base = safeBaseName(originalFilename);
        if (base.isEmpty()) {
            throw BizException.badRequest("文件名不能为空");
        }
        if (base.length() > 255) {
            throw BizException.badRequest("文件名过长（上限 255 字符）");
        }
        // 控制字符会让日志与响应头变得不可读，甚至造成响应头注入
        if (base.chars().anyMatch(Character::isISOControl)) {
            throw BizException.badRequest("文件名包含非法字符");
        }
        return base;
    }

    /**
     * 存储用的文件名：{@code UUID + 原扩展名}。
     *
     * <p>用 UUID 而不是原文件名落盘，好处有三：</p>
     * <ol>
     *   <li>同名文件（{@code SM_Crate_A.fbx} 的第 2 版）不会互相覆盖——
     *       资产版本是<b>不可变</b>的，覆盖即数据丢失；</li>
     *   <li>原始文件名里的中文 / emoji / 空格不会在不同文件系统上踩编码坑；</li>
     *   <li>原始文件名只存在数据库里，磁盘上看不出任何业务信息。</li>
     * </ol>
     */
    public static String storeName(String originalFilename, String uuid) {
        String ext = ext(originalFilename);
        return ext.isEmpty() ? uuid : uuid + "." + ext;
    }

    /** 扩展名允许的字符：字母数字与 {@code +-_}（{@code .tar.gz} 之类只取最后一段） */
    private static boolean isExtChar(int c) {
        return Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '_';
    }

    /**
     * 字节数 → 人类可读大小，如 {@code 134500000 → 128.3 MB}。
     *
     * <p>放在后端而不是前端：版本时间线、资产卡片、对比页三处都要显示体积，
     * 前端各自实现一遍单位换算，早晚会出现「同一份文件在列表里是 128.3 MB、
     * 在详情里是 128.29 MB」这种解释不清的差异。</p>
     *
     * <p>用 {@link Locale#ROOT} 格式化：默认 Locale 在部分环境下小数点会变成逗号，
     * 直接抛给 JSON 后前端会当成两个字段。</p>
     */
    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    /** 带正负号的大小增量，如 {@code +128.3 MB} / {@code -2.0 MB}，用于版本对比 */
    public static String humanSizeDelta(long delta) {
        if (delta == 0) {
            return "无变化";
        }
        return (delta > 0 ? "+" : "-") + humanSize(Math.abs(delta));
    }
}
