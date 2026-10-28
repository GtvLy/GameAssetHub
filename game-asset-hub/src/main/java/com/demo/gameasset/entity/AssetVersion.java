package com.demo.gameasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 资产版本（{@code asset_version}，文档 3.2 DDL）。
 *
 * <p><b>版本一经上传永不覆盖</b>。切换当前版本、回滚历史版本都只改
 * {@link #isCurrent} 标记位并更新 {@code asset.current_version_id}，
 * 不删除任何历史文件——这是「历史版本完整追溯」的硬要求。</p>
 *
 * <p>{@code version_no} 在资产维度自增，由 {@code uk_asset_version(asset_id, version_no)}
 * 唯一约束兜底：即使分布式锁失效（例如 Redis 抖动），并发插入也只会失败一个，
 * 不会写出两个 V3。</p>
 *
 * <p>本表<b>没有 {@code deleted} 列</b>，也不允许删除。</p>
 *
 * @author 管理员
 */
@Data
@TableName("asset_version")
public class AssetVersion implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属资产 */
    private Long assetId;

    /**
     * 所属项目。
     * 版本表冗余 project_id 是刻意的：切面校验通过后要把 project_id 拼进
     * 每一条 SQL，冗余一列就不必为了拿 project_id 先回查 asset 表。
     */
    private Long projectId;

    /** 版本号，从 1 递增 */
    private Integer versionNo;

    /** 版本备注名，如「初版高模」；未填时默认 V{versionNo} */
    private String versionName;

    /** 原始文件名（客户端上传时的名字），与磁盘上的 UUID 名不同 */
    private String fileName;

    /** 存储路径 / 对象 Key，由 StorageService 生成 */
    private String fileKey;

    /** 字节数 */
    private Long fileSize;

    /** 扩展名：fbx / blend / png / uasset / wav */
    private String fileExt;

    /** 文件 MD5，用于秒传与去重（{@code idx_md5}） */
    private String fileMd5;

    /** 缩略图URL（图片类资产或渲染截图） */
    private String thumbnailUrl;

    /** 面数（模型类资产） */
    private Integer polyCount;

    /** 贴图尺寸，如 2048x2048 */
    private String textureSize;

    /** 变更说明 */
    private String changeLog;

    /** 上传人 */
    private Long uploaderId;

    /** 是否当前版本：同一资产下仅一条为 1（{@code idx_asset_current}） */
    private Integer isCurrent;

    private LocalDateTime createTime;
}
