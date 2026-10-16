package com.zhangchi.gameasset.storage;

import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.common.util.FileUtil;
import com.zhangchi.gameasset.common.util.Md5Util;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 待入库文件的内容来源。
 *
 * <p>版本创建流程只有「算 MD5 → 判断秒传 → 落盘」三步，
 * 但文件可能来自两个完全不同的地方：</p>
 * <ul>
 *   <li><b>小文件直传</b>：{@link MultipartFile}，内容还在请求里；</li>
 *   <li><b>大文件分片</b>：合并后的服务器临时文件 {@link Path}，
 *       此时请求早就结束了。</li>
 * </ul>
 *
 * <p>如果没有这层抽象，{@code AssetService} 里就会出现两条几乎一样、
 * 却各自维护的分支——而版本号自增、{@code is_current} 切换、
 * 状态回退这些逻辑一旦有两份实现，早晚会分叉，分叉出来的那份就是 bug。
 * 这里把差异收敛成三个方法，业务逻辑只写一遍。</p>
 *
 * @author 张忠驰
 */
public interface UploadSource {

    /** 原始文件名（客户端上传时的名字），落 {@code asset_version.file_name} */
    String originalFilename();

    /** 字节数 */
    long size();

    /** 计算内容 MD5 并返回（同一次上传内可能被多次调用，实现应自行缓存） */
    String md5Hex();

    /**
     * 交给存储服务落盘。
     *
     * @param storage 存储实现（local / minio）
     * @param dir     业务目录前缀，如 {@code 1/12}
     * @return 存储 key
     */
    String store(StorageService storage, String dir);

    /**
     * 直传来源：内容就是本次请求的上传流。
     */
    static UploadSource of(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件为空");
        }
        String originalName = FileUtil.requireValidName(file.getOriginalFilename());
        return new UploadSource() {

            /** MD5 只算一次：内容不变，重复读取大文件纯属浪费 IO */
            private String cachedMd5;

            @Override
            public String originalFilename() {
                return originalName;
            }

            @Override
            public long size() {
                return file.getSize();
            }

            @Override
            public String md5Hex() {
                if (cachedMd5 == null) {
                    try (InputStream in = file.getInputStream()) {
                        cachedMd5 = Md5Util.md5Hex(in);
                    } catch (IOException e) {
                        throw BizException.badRequest("读取上传文件失败：" + originalName);
                    }
                }
                return cachedMd5;
            }

            @Override
            public String store(StorageService storage, String dir) {
                return storage.upload(file, dir);
            }
        };
    }

    /**
     * 分片合并来源：内容已在服务器临时目录里。
     *
     * <p>MD5 由合并阶段边写边算并传进来，这里不再重读一遍文件——
     * 2GB 的资产重算一次要好几秒，而合并时顺手算的成本是 0。</p>
     */
    static UploadSource ofLocalFile(Path path, String originalFilename, long size, String knownMd5) {
        if (path == null || !Files.isRegularFile(path)) {
            throw BizException.badRequest("合并后的临时文件不存在");
        }
        String originalName = FileUtil.requireValidName(originalFilename);
        return new UploadSource() {

            @Override
            public String originalFilename() {
                return originalName;
            }

            @Override
            public long size() {
                return size;
            }

            @Override
            public String md5Hex() {
                return knownMd5;
            }

            @Override
            public String store(StorageService storage, String dir) {
                return storage.upload(path, originalName, dir);
            }
        };
    }
}
