package com.demo.gameasset.common.util;

import com.demo.gameasset.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名清洗与 MD5 摘要（模块2 存储层的两个工具）。
 *
 * <p>为什么值得单独测：{@code MultipartFile#getOriginalFilename} 是
 * <b>客户端可控</b>的，而这两个工具的输出会被拼进磁盘路径与数据库。
 * 它们出问题不是「显示不好看」，而是任意文件写入与越权读取。</p>
 *
 * <p>纯逻辑测试：不加载 Spring、不连 MySQL / Redis。</p>
 *
 * @author 管理员
 */
@DisplayName("FileUtil 与 Md5Util")
class FileUtilAndMd5Test {

    @Nested
    @DisplayName("扩展名与文件名清洗")
    class FileNameHandling {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "SM_Crate_A.fbx, fbx",
                "SM_Crate_A.FBX, fbx",
                "贴图.PNG, png",
                "动画.v2.uasset, uasset",
                "noext, ''",
                "trailing., ''",
                "archive.tar.gz, gz"
        })
        void extractsLowerCaseExtension(String fileName, String expected) {
            assertThat(FileUtil.ext(fileName)).isEqualTo(expected);
        }

        @Test
        @DisplayName("扩展名过长或含异常字符时丢弃，避免写出超长/非法的 file_ext")
        void rejectsAbsurdExtensions() {
            assertThat(FileUtil.ext("a.thisisaverylongextensionnamethatgoeson")).isEmpty();
            assertThat(FileUtil.ext("a.f b")).isEmpty();
            assertThat(FileUtil.ext(null)).isEmpty();
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "../../etc/passwd, passwd",
                "..\\..\\windows\\win.ini, win.ini",
                "C:\\Users\\me\\model.fbx, model.fbx",
                "/var/tmp/贴图.png, 贴图.png",
                "just-a-name.fbx, just-a-name.fbx"
        })
        @DisplayName("剥掉目录部分，只保留最后一段（防目录穿越）")
        void stripsDirectoryParts(String raw, String expected) {
            assertThat(FileUtil.safeBaseName(raw)).isEqualTo(expected);
        }

        @Test
        @DisplayName("空文件名/控制字符文件名一律 400，不允许落盘")
        void requireValidNameRejectsBadNames() {
            assertThatThrownBy(() -> FileUtil.requireValidName(null))
                    .isInstanceOf(BizException.class).hasMessageContaining("文件名不能为空");
            assertThatThrownBy(() -> FileUtil.requireValidName("   "))
                    .isInstanceOf(BizException.class).hasMessageContaining("文件名不能为空");
            assertThatThrownBy(() -> FileUtil.requireValidName("a\u0000b.fbx"))
                    .isInstanceOf(BizException.class).hasMessageContaining("非法字符");
            assertThatThrownBy(() -> FileUtil.requireValidName("x".repeat(300) + ".fbx"))
                    .isInstanceOf(BizException.class).hasMessageContaining("过长");
        }

        @Test
        @DisplayName("存储名 = UUID + 原扩展名，原始文件名不落盘")
        void storeNameUsesUuidPlusExtension() {
            assertThat(FileUtil.storeName("SM_Crate_A.fbx", "abc123"))
                    .isEqualTo("abc123.fbx")
                    .doesNotContain("SM_Crate_A");
            assertThat(FileUtil.storeName("noext", "abc123")).isEqualTo("abc123");
        }

        @Test
        @DisplayName("同名文件存储名不同（版本不可变，覆盖即丢数据）")
        void storeNameIsUniquePerCall() {
            String first = FileUtil.storeName("SM_Crate_A.fbx", java.util.UUID.randomUUID().toString());
            String second = FileUtil.storeName("SM_Crate_A.fbx", java.util.UUID.randomUUID().toString());
            assertThat(first).isNotEqualTo(second);
        }
    }

    @Nested
    @DisplayName("体积格式化")
    class SizeFormatting {

        @ParameterizedTest(name = "{0} 字节 → {1}")
        @CsvSource({
                "0, 0 B",
                "512, 512 B",
                "1024, 1.0 KB",
                "1048576, 1.0 MB",
                "189743104, 181.0 MB",
                "67108864, 64.0 MB",
                "1073741824, 1.00 GB"
        })
        void formatsHumanReadableSize(long bytes, String expected) {
            assertThat(FileUtil.humanSize(bytes)).isEqualTo(expected);
        }

        @Test
        @DisplayName("负数体积显示为占位符，不显示 -1 B")
        void handlesNegativeSize() {
            assertThat(FileUtil.humanSize(-1)).isEqualTo("—");
        }

        @Test
        @DisplayName("体积增量带正负号，0 显示「无变化」")
        void formatsSizeDelta() {
            assertThat(FileUtil.humanSizeDelta(1048576)).isEqualTo("+1.0 MB");
            assertThat(FileUtil.humanSizeDelta(-1048576)).isEqualTo("-1.0 MB");
            assertThat(FileUtil.humanSizeDelta(0)).isEqualTo("无变化");
        }
    }

    @Nested
    @DisplayName("MD5 摘要")
    class Md5 {

        @Test
        @DisplayName("已知向量的 MD5 正确（小写十六进制 32 位）")
        void matchesKnownVectors() {
            assertThat(Md5Util.md5Hex("abc".getBytes(StandardCharsets.UTF_8)))
                    .isEqualTo("900150983cd24fb0d6963f7d28e17f72");
            assertThat(Md5Util.md5Hex("".getBytes(StandardCharsets.UTF_8)))
                    .isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
        }

        @Test
        @DisplayName("内容相同 MD5 相同，内容差一个字节 MD5 完全不同（秒传判定的基础）")
        void isSensitiveToContent() {
            String a = Md5Util.md5Hex("SM_Crate_A".getBytes(StandardCharsets.UTF_8));
            String b = Md5Util.md5Hex("SM_Crate_B".getBytes(StandardCharsets.UTF_8));
            String aAgain = Md5Util.md5Hex("SM_Crate_A".getBytes(StandardCharsets.UTF_8));
            assertThat(a).isEqualTo(aAgain).isNotEqualTo(b);
        }

        @Test
        @DisplayName("流式读取：不关闭传入的流，调用方还能继续用")
        void doesNotCloseInputStream() throws IOException {
            ByteArrayInputStream in = new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8));

            String md5 = Md5Util.md5Hex(in);

            assertThat(md5).isEqualTo("900150983cd24fb0d6963f7d28e17f72");
            assertThat(in.read()).as("流不应被关闭，只是读到了末尾").isEqualTo(-1);
        }

        @Test
        @DisplayName("大内容按缓冲区流式读取，结果与一次性计算一致")
        void streamsLargeContent() {
            byte[] big = new byte[300 * 1024];
            java.util.Arrays.fill(big, (byte) 7);

            assertThat(Md5Util.md5Hex(new ByteArrayInputStream(big)))
                    .isEqualTo(Md5Util.md5Hex(big));
        }

        @Test
        @DisplayName("对文件路径计算 MD5（分片合并校验用）")
        void computesMd5OfFile(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("chunk.bin");
            Files.writeString(file, "abc", StandardCharsets.UTF_8);

            assertThat(Md5Util.md5Hex(file)).isEqualTo("900150983cd24fb0d6963f7d28e17f72");
        }

        @Test
        @DisplayName("null 输入返回 null 而不是抛异常")
        void handlesNullInput() {
            assertThat(Md5Util.md5Hex((byte[]) null)).isNull();
            assertThat(Md5Util.md5Hex((java.io.InputStream) null)).isNull();
            assertThat(Md5Util.md5Hex((Path) null)).isNull();
        }
    }
}
