package com.demo.gameasset.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * 文件存储抽象（文档 3.3 第 5 点、2.2 节 {@code storage/} 包）。
 *
 * <p>接口只暴露 5 个动作，业务层拿到的永远是 {@code fileKey} 字符串，
 * 不知道文件究竟落在本地磁盘还是 MinIO。切换存储（{@code gameasset.storage.type}）
 * 不需要改任何一行业务代码。</p>
 *
 * <p><b>{@code fileKey} 的约定</b>：形如 {@code 1/12/3f2a....fbx} 的相对路径，
 * 三段分别是 projectId / assetId / 存储名。带上项目与资产两层前缀有两个好处：
 * 一是运维排查时能直接从路径定位到业务对象，二是给「按项目清理存储」留了余地。
 * 注意 {@code fileKey} 里<b>不含原始文件名</b>——原始名存数据库，
 * 磁盘上只有 UUID，避免同名覆盖与编码问题。</p>
 *
 * @author 管理员
 */
public interface StorageService {

    /**
     * 保存上传的文件。
     *
     * @param file 上传文件（原始文件名会被读取，但只用于取扩展名）
     * @param dir  业务目录前缀，如 {@code 1/12}（projectId/assetId）
     * @return 存储 key，写入 {@code asset_version.file_key}
     */
    String upload(MultipartFile file, String dir);

    /**
     * 保存服务器本地的一个文件（分片合并后的成品走这条路径）。
     *
     * <p>与 {@link #upload(MultipartFile, String)} 分开而不是让调用方
     * 自己包一个 MultipartFile：合并结果本来就是磁盘上的临时文件，
     * 再包一层只会多一次无意义的拷贝。</p>
     *
     * @param source           源文件
     * @param originalFilename 原始文件名（只用于取扩展名）
     * @param dir              业务目录前缀
     * @return 存储 key
     */
    String upload(Path source, String originalFilename, String dir);

    /**
     * 读取文件内容。
     *
     * @param fileKey 存储 key
     * @return 可读的资源；文件不存在抛 404
     */
    Resource download(String fileKey);

    /**
     * 删除文件。<b>业务上几乎不会调用</b>——资产版本不可变，
     * 删除版本会破坏「历史可追溯」。保留它是为了支持运维清理与失败回滚。
     *
     * @return 确实删掉了返回 true；文件本就不存在返回 false
     */
    boolean delete(String fileKey);

    /**
     * 取可直接访问的 URL。
     *
     * <p>两套实现的语义不同：MinIO 返回预签名 URL，前端可以直连、
     * 不占用应用服务器带宽；本地存储没有公开 URL，返回 {@code null}，
     * 前端改用下载接口。</p>
     *
     * @return 可直接访问的 URL；本地存储返回 null
     */
    String getUrl(String fileKey);
}
