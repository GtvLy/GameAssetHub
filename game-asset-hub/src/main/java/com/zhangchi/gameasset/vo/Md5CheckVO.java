package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 秒传检查结果（文档 4.2 {@code POST /api/assets/upload/check-md5}）。
 *
 * <p><b>作用域</b>：只在本项目内查找。命中时返回的是「本项目哪个资产的哪个版本
 * 已经有这份文件」，属于本项目成员本就可见的信息；
 * 若跨项目返回，就等于把别的项目的资产名泄露给了外人。
 * 物理层秒传（复用 file_key 省存储）仍然是全局的，但那发生在服务端内部，
 * 不对外暴露任何元数据——两者的区别见 {@code AssetServiceImpl#uploadVersion}。</p>
 *
 * @author 张忠驰
 */
@Data
public class Md5CheckVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 本项目内是否已有相同 MD5 的文件 */
    private Boolean exists;

    private String md5;

    /** 命中的存储 key，前端不需要，排查时用 */
    private String fileKey;

    /** 命中的资产 */
    private Long assetId;

    private String assetNo;

    private String assetName;

    /** 命中的版本 */
    private Long versionId;

    private Integer versionNo;

    /** 命中版本记录的原始文件名与大小，供前端展示「秒传自 xxx」 */
    private String fileName;

    private Long fileSize;

    /** 命中时给前端的提示语，避免前端拼中文 */
    private String message;
}
