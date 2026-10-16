package com.zhangchi.gameasset.common.util;

import com.zhangchi.gameasset.common.exception.BizException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MD5 摘要工具（资产秒传与分片合并校验用）。
 *
 * <p>用 JDK 自带的 {@link MessageDigest}，不引 Hutool 的 {@code DigestUtil}。
 * MD5 在这里的用途是「内容指纹去重」，不是安全场景——
 * 秒传判断错一次的最坏后果是重复存了一份文件，
 * 不承担任何密码学职责，因此无需 SHA-256 或加盐。</p>
 *
 * <p><b>流式读取</b>：UE5 的资产动辄数百 MB，{@code readAllBytes()} 会直接把堆打满。
 * 所有方法一律按 8KB 缓冲区边读边更新摘要，内存占用恒定。</p>
 *
 * @author 张忠驰
 */
public final class Md5Util {

    /** 摘要缓冲区大小：8KB 是文件流读取的常用档位，再大收益递减 */
    private static final int BUFFER_SIZE = 8 * 1024;

    /** 十六进制字符表，避免每次 {@code String.format} 的开销 */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Md5Util() {
    }

    /** 计算字节数组的 MD5（32 位小写十六进制） */
    public static String md5Hex(byte[] data) {
        if (data == null) {
            return null;
        }
        return toHex(newDigest().digest(data));
    }

    /**
     * 流式计算输入流的 MD5。
     * <b>不关闭传入的流</b>：流的生命周期由调用方（上传入口）负责，
     * 这里关掉会让调用方后续无法再读同一个流。
     */
    public static String md5Hex(InputStream inputStream) {
        if (inputStream == null) {
            return null;
        }
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw BizException.badRequest("读取上传文件内容失败：" + e.getMessage());
        }
        return toHex(digest.digest());
    }

    /** 计算文件的 MD5（分片合并完成后校验整体一致性用） */
    public static String md5Hex(Path path) {
        if (path == null) {
            return null;
        }
        try (InputStream in = Files.newInputStream(path)) {
            return md5Hex(in);
        } catch (IOException e) {
            throw BizException.badRequest("读取文件失败：" + path.getFileName());
        }
    }

    /** 新建摘要实例。MD5 是 JDK 强制支持的算法，取不到属于运行环境损坏，直接抛 500 */
    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 MD5 摘要算法", e);
        }
    }

    /** 字节数组 → 小写十六进制字符串 */
    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            chars[i * 2] = HEX[b >>> 4];
            chars[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(chars);
    }
}
