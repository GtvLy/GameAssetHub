package com.zhangchi.gameasset.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分片上传配置（{@code gameasset.chunk-upload.*}，文档 10.4）。
 *
 * @author 张忠驰
 */
@Data
@ConfigurationProperties(prefix = "gameasset.chunk-upload")
public class ChunkUploadProperties {

    /** 分片临时目录：每个 uploadId 一个子目录，落 {@code {index}.part} */
    private String tempPath = "D:/java/.tools/uploads/game-asset-hub/tmp/chunks";

    /** 残留分片清理任务的 cron，默认每天凌晨 3 点 */
    private String cleanupCron = "0 0 3 * * ?";

    /**
     * 分片大小（字节），默认 5MB。
     * 前端也按这个值切片，两边必须一致；对外由 init 接口返回给前端，
     * 避免前端写死一个数字后与后端配置脱节。
     */
    private int chunkSize = 5 * 1024 * 1024;

    /** 残留分片保留时长（小时），超过即视为上传中断的垃圾 */
    private int expireHours = 24;
}
