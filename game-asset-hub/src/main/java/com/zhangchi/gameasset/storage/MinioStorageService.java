package com.zhangchi.gameasset.storage;

import com.zhangchi.gameasset.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * MinIO 对象存储实现（<b>占位实现</b>，文档 3.3 第 5 点的可扩展性设计）。
 *
 * <p><b>为什么是一个「不可用」的实现</b>：课程环境要求依赖克制，
 * 引入 {@code minio} SDK 会带进 okhttp、kotlin-stdlib 等一整串传递依赖，
 * 只为演示一个可选能力并不划算。因此这里保留完整的
 * <b>实现位 + 配置位</b>：</p>
 * <ul>
 *   <li>类照常实现 {@link StorageService}，签名与本地实现完全一致，
 *       业务层感知不到差异；</li>
 *   <li>{@code application.yml} 里 {@code gameasset.storage.minio.*}
 *       四项配置齐全；</li>
 *   <li>切换只需把 {@code gameasset.storage.type} 改成 {@code minio}
 *       并引入 SDK，然后把这几个方法体填上——<b>不改任何业务代码</b>。</li>
 * </ul>
 *
 * <p>被选中时构造阶段只告警不中断启动，调用具体方法才抛 500：
 * 启动即崩会让人误以为工程坏了，而实际只是存储后端没接；
 * 真正上传时再明确报错，指向清晰。</p>
 *
 * @author 张忠驰
 */
@Slf4j
public class MinioStorageService implements StorageService {

    /** 未接入时的统一提示，四处方法共用 */
    private static final String NOT_IMPLEMENTED =
            "MinIO 存储实现尚未接入：本项目未引入 minio SDK 依赖。"
                    + "请将 gameasset.storage.type 改回 local，或引入依赖后补全 MinioStorageService";

    private final String endpoint;
    private final String bucket;

    public MinioStorageService(String endpoint, String accessKey, String secretKey, String bucket) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        log.warn("[存储] gameasset.storage.type=minio，但 MinIO 实现为占位版本（endpoint={} bucket={}）。"
                + "上传/下载接口会在调用时报错，仅用于演示存储抽象的可替换性。", endpoint, bucket);
        if (accessKey == null || secretKey == null) {
            log.warn("[存储] MinIO 访问密钥未配置，即使补全实现也无法连接");
        }
    }

    @Override
    public String upload(MultipartFile file, String dir) {
        throw new BizException(500, NOT_IMPLEMENTED);
    }

    @Override
    public String upload(Path source, String originalFilename, String dir) {
        throw new BizException(500, NOT_IMPLEMENTED);
    }

    @Override
    public Resource download(String fileKey) {
        throw new BizException(500, NOT_IMPLEMENTED);
    }

    @Override
    public boolean delete(String fileKey) {
        throw new BizException(500, NOT_IMPLEMENTED);
    }

    /**
     * 返回 MinIO 预签名 URL 是本实现的目标形态（文档 10.5：让缩略图与下载
     * 直连对象存储，避免文件流量穿透应用服务器）。占位阶段无法签名，返回 null。
     */
    @Override
    public String getUrl(String fileKey) {
        return null;
    }

    /** 供日志与排查使用：当前配置指向的 endpoint */
    public String getEndpoint() {
        return endpoint;
    }

    /** 供日志与排查使用：当前配置的 bucket */
    public String getBucket() {
        return bucket;
    }
}
