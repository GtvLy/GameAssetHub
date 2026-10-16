package com.zhangchi.gameasset.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储配置（{@code gameasset.storage.*}，文档 10.4）。
 *
 * <p>对应文档 3.3 第 5 点：通过 {@code type} 在本地磁盘与 MinIO 之间切换，
 * 业务代码不感知。默认 {@code local}，不需要任何外部服务即可跑通。</p>
 *
 * @author 张忠驰
 */
@Data
@ConfigurationProperties(prefix = "gameasset.storage")
public class StorageProperties {

    /** 存储类型：local / minio */
    private String type = TYPE_LOCAL;

    /**
     * 本地存储根目录。
     *
     * <p>刻意放在用户目录之外的固定路径，而不是相对路径 {@code ./uploads}：
     * 相对路径会随启动目录变化（IDE 里跑与 jar 里跑不一样），
     * 表现为「上传成功但重启后文件不见了」，排查起来很费时间。</p>
     */
    private String localPath = "D:/java/.tools/uploads/game-asset-hub";

    /** MinIO 相关配置（占位，见 MinioStorageService 说明） */
    private Minio minio = new Minio();

    public static final String TYPE_LOCAL = "local";
    public static final String TYPE_MINIO = "minio";

    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey;
        private String secretKey;
        private String bucket = "game-assets";
    }
}
