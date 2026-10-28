package com.demo.gameasset.storage;

import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.common.util.Md5Util;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分片合并的顺序与数量校验（文档 7.6）。
 *
 * <p>这是文档 9 节明确点名的测试要点。合并出错的表现很隐蔽：
 * 服务端全程无异常、HTTP 200、数据库记录齐全，但美术打开模型时报格式错误——
 * 因为拼出来的字节流是错的。所以这里把「顺序」与「数量」两件事
 * 各自用能抓住问题的用例钉死。</p>
 *
 * <p>用 JUnit 的 {@link TempDir} 构造真实的临时目录：合并是纯文件操作，
 * 不涉及 Spring / MySQL / Redis，仍然属于纯逻辑测试。</p>
 *
 * @author 管理员
 */
@DisplayName("ChunkMerger 分片按序合并与数量校验")
class ChunkMergerTest {

    @TempDir
    Path tempDir;

    /** 写入一个分片，内容为 {@code A{index}}（长度不同便于暴露顺序错误） */
    private void writeChunk(int index, String content) throws IOException {
        Files.writeString(tempDir.resolve(index + ".part"), content, StandardCharsets.UTF_8);
    }

    /** 按 0..total-1 写入分片，内容为 A{index} */
    private void writeAllChunks(int total) throws IOException {
        for (int i = 0; i < total; i++) {
            writeChunk(i, "A" + i);
        }
    }

    // ==================== 顺序 ====================

    @Test
    @DisplayName("按下标顺序合并，而不是按文件名字典序（10 片以上必须仍然是 0,1,...,10）")
    void mergesByIndexOrderNotLexicographicOrder() throws IOException {
        // 11 片时字典序会排成 0,1,10,2,3...，顺序错了内容立刻不对
        writeAllChunks(11);
        Path target = tempDir.resolve("out.bin");

        ChunkMerger.MergeResult result = ChunkMerger.merge(tempDir, 11, target);

        String merged = Files.readString(target, StandardCharsets.UTF_8);
        assertThat(merged).isEqualTo("A0A1A2A3A4A5A6A7A8A9A10");
        assertThat(result.chunkCount()).isEqualTo(11);
        assertThat(result.size()).isEqualTo(merged.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("合并结果的 MD5 等于全部内容拼接后的 MD5（内容与顺序都对才能对上）")
    void computesMd5OfConcatenatedContent() throws IOException {
        writeAllChunks(4);
        Path target = tempDir.resolve("out.bin");

        ChunkMerger.MergeResult result = ChunkMerger.merge(tempDir, 4, target);

        assertThat(result.md5()).isEqualTo(Md5Util.md5Hex("A0A1A2A3".getBytes(StandardCharsets.UTF_8)));
        assertThat(result.md5()).as("MD5 应为 32 位小写十六进制").matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("分片落盘顺序与写入顺序无关，只看下标")
    void mergeOrderDoesNotDependOnWriteOrder() throws IOException {
        // 故意乱序写入
        writeChunk(2, "C");
        writeChunk(0, "A");
        writeChunk(1, "B");
        Path target = tempDir.resolve("out.bin");

        ChunkMerger.merge(tempDir, 3, target);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("ABC");
    }

    @Test
    @DisplayName("单片文件也必须能合并")
    void handlesSingleChunk() throws IOException {
        writeChunk(0, "只有一个分片");
        Path target = tempDir.resolve("out.bin");

        ChunkMerger.MergeResult result = ChunkMerger.merge(tempDir, 1, target);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("只有一个分片");
        assertThat(result.chunkCount()).isEqualTo(1);
    }

    // ==================== 数量校验 ====================

    @Test
    @DisplayName("缺片时拒绝合并，并在提示里列出缺了哪几片")
    void rejectsMissingChunks() throws IOException {
        writeChunk(0, "A");
        writeChunk(2, "C");
        // 缺少第 1 片
        Path target = tempDir.resolve("out.bin");

        assertThatThrownBy(() -> ChunkMerger.merge(tempDir, 3, target))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分片不完整")
                .hasMessageContaining("第 1 片");

        assertThat(Files.exists(target)).as("校验失败时不应留下半截文件").isFalse();
    }

    @Test
    @DisplayName("缺片提示里列出多个下标，便于前端定位")
    void missingChunkMessageListsAllMissingIndexes() throws IOException {
        writeChunk(0, "A");
        writeChunk(3, "D");
        Path target = tempDir.resolve("out.bin");

        assertThatThrownBy(() -> ChunkMerger.merge(tempDir, 4, target))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("1、2");
    }

    @Test
    @DisplayName("多出分片说明客户端切片与声明不一致，同样拒绝")
    void rejectsExtraChunks() throws IOException {
        writeAllChunks(4);
        Path target = tempDir.resolve("out.bin");

        assertThatThrownBy(() -> ChunkMerger.merge(tempDir, 2, target))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分片数量与声明不一致")
                .hasMessageContaining("2、3");
    }

    @Test
    @DisplayName("一个分片都没有时，缺失列表包含全部下标")
    void rejectsCompletelyEmptySession() {
        Path target = tempDir.resolve("out.bin");

        assertThatThrownBy(() -> ChunkMerger.merge(tempDir, 3, target))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("0、1、2");
    }

    @Test
    @DisplayName("分片总数为 0 或负数 → 400")
    void rejectsNonPositiveTotalChunks() {
        Path target = tempDir.resolve("out.bin");

        assertThatThrownBy(() -> ChunkMerger.merge(tempDir, 0, target))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分片总数必须大于 0");
        assertThatThrownBy(() -> ChunkMerger.merge(tempDir, -1, target))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分片总数必须大于 0");
    }

    @Test
    @DisplayName("会话目录不存在 → 提示会话过期，而不是报文件系统错误")
    void rejectsMissingSessionDirectory() {
        Path missing = tempDir.resolve("not-exists");
        Path target = tempDir.resolve("out.bin");

        assertThatThrownBy(() -> ChunkMerger.merge(missing, 2, target))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("上传会话不存在或已过期");
    }

    @Test
    @DisplayName("非 {n}.part 的文件被忽略，不影响数量校验（合并产物与说明文件混在目录里也安全）")
    void ignoresNonChunkFiles() throws IOException {
        writeAllChunks(2);
        Files.writeString(tempDir.resolve("merged.tmp"), "上一次合并的残留");
        Files.writeString(tempDir.resolve("notes.txt"), "说明文件");
        Files.writeString(tempDir.resolve("x.part"), "非法命名");
        Path target = tempDir.resolve("out.bin");

        ChunkMerger.MergeResult result = ChunkMerger.merge(tempDir, 2, target);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("A0A1");
        assertThat(result.chunkCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("listChunkIndexes 返回升序下标，目录不存在时返回空列表而不是抛错")
    void listsChunkIndexesInOrder() throws IOException {
        writeChunk(5, "F");
        writeChunk(1, "B");
        writeChunk(3, "D");

        assertThat(ChunkMerger.listChunkIndexes(tempDir)).containsExactly(1, 3, 5);
        assertThat(ChunkMerger.listChunkIndexes(tempDir.resolve("nope"))).isEmpty();
        assertThat(ChunkMerger.listChunkIndexes(null)).isEmpty();
    }

    @Test
    @DisplayName("重复上传同一片时以最后一次为准（断点续传重传的场景）")
    void lastWriteWinsForDuplicatedChunk() throws IOException {
        writeChunk(0, "旧的");
        writeChunk(0, "新的");
        writeChunk(1, "尾巴");
        Path target = tempDir.resolve("out.bin");

        ChunkMerger.merge(tempDir, 2, target);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("新的尾巴");
    }
}
