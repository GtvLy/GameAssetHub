package com.demo.gameasset.config;

import com.demo.gameasset.storage.LocalStorageService;
import com.demo.gameasset.storage.MinioStorageService;
import com.demo.gameasset.storage.StorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存储策略装配（文档 3.3 第 5 点「存储抽象」）。
 *
 * <p>两个 {@link StorageService} 实现按 {@code gameasset.storage.type} 二选一，
 * 业务层只注入接口。{@code matchIfMissing = true} 让 {@code local}
 * 成为默认值——不配任何存储相关配置也能正常上传。</p>
 *
 * @author 管理员
 */
@Configuration
@EnableConfigurationProperties({StorageProperties.class, ChunkUploadProperties.class})
public class StorageConfig {

    /** 本地磁盘存储（默认） */
    @Bean
    @ConditionalOnProperty(prefix = "gameasset.storage", name = "type",
            havingValue = StorageProperties.TYPE_LOCAL, matchIfMissing = true)
    public StorageService localStorageService(StorageProperties properties) {
        return new LocalStorageService(properties.getLocalPath());
    }

    /**
     * MinIO 对象存储（占位实现，不引 SDK 依赖）。
     *
     * <p>只有显式配置 {@code type: minio} 时这个 Bean 才存在，
     * 因此默认环境里 {@code MinioStorageService} 永远不会被实例化，
     * 也不会打印那句「占位实现」的告警。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "gameasset.storage", name = "type",
            havingValue = StorageProperties.TYPE_MINIO)
    public StorageService minioStorageService(StorageProperties properties) {
        StorageProperties.Minio minio = properties.getMinio();
        return new MinioStorageService(minio.getEndpoint(), minio.getAccessKey(),
                minio.getSecretKey(), minio.getBucket());
    }
}
