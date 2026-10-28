package com.demo.gameasset.task;

import com.demo.gameasset.service.ChunkUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 残留分片清理（文档 7.6：分片临时文件由定时任务清理超过 24 小时的残留）。
 *
 * <p><b>为什么必须有这个任务</b>：分片上传最典型的失败方式是
 * 「传到第 37 片时用户关了浏览器 / 网线松了」。此时服务端已经落了 37 个分片，
 * 但 {@code complete} 永远不会到来。没有清理任务的话这些片会一直堆在磁盘上，
 * 而 UE5 资产动辄 2GB，几次中断就能吃掉几十 GB。</p>
 *
 * <p>cron 与保留时长都走配置（{@code gameasset.chunk-upload.cleanup-cron} /
 * {@code expire-hours}），不用改代码就能调整运维策略。</p>
 *
 * @author 管理员
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkCleanupTask {

    private final ChunkUploadService chunkUploadService;

    /**
     * 按配置的 cron 清理残留分片。
     *
     * <p>异常必须在这里吞掉：定时任务里抛出的异常只会被 Spring 记一条日志，
     * 但下一次调度是否继续取决于调度器实现——显式捕获能让「某次清理失败」
     * 与「清理任务整个停摆」区分开。</p>
     */
    @Scheduled(cron = "${gameasset.chunk-upload.cleanup-cron:0 0 3 * * ?}")
    public void cleanupExpiredChunks() {
        try {
            int cleaned = chunkUploadService.cleanupExpired();
            log.info("[定时任务] 残留分片清理完成，本次清理 {} 个会话", cleaned);
        } catch (Exception e) {
            log.error("[定时任务] 残留分片清理失败", e);
        }
    }
}
