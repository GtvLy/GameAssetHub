package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 资产版本（版本时间线的一个节点，文档 7.3）。
 *
 * @author 张忠驰
 */
@Data
public class AssetVersionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long assetId;

    private Long projectId;

    private Integer versionNo;

    /** 版本备注名 */
    private String versionName;

    /** 原始文件名 */
    private String fileName;

    /** 存储 key。前端不需要它来下载（走 downloadUrl），但排查问题时要看得到 */
    private String fileKey;

    private Long fileSize;

    /** 人类可读的大小，如「128.4 MB」。避免前端各自实现一遍单位换算 */
    private String fileSizeText;

    private String fileExt;

    private String fileMd5;

    private String thumbnailUrl;

    /** 面数 */
    private Integer polyCount;

    /** 贴图尺寸 */
    private String textureSize;

    /** 变更说明 */
    private String changeLog;

    private Long uploaderId;

    /** 上传人昵称，时间线上直接展示 */
    private String uploaderName;

    /** 是否当前版本：同资产仅一条为 1 */
    private Integer isCurrent;

    /** 便于前端判断是否显示「当前版本」徽标，省一次整数比较 */
    private Boolean current;

    /**
     * 下载地址。指向后端下载接口而不是真实文件路径——
     * 真实路径在本地存储下不可直接访问，在 MinIO 下需要预签名。
     */
    private String downloadUrl;

    private LocalDateTime createTime;
}
