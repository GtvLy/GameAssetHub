package com.zhangchi.gameasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 资产主表（{@code asset}，文档 3.2 DDL）。
 *
 * <p>一条 {@code asset} 记录代表一个「被管理的资产」，它下面挂若干
 * {@link AssetVersion}。资产本身可变（改名、换分类、改优先级），
 * <b>版本不可变</b>——这正是「版本可追溯」的实现基础。</p>
 *
 * @author 张忠驰
 */
@Data
@TableName("asset")
public class Asset implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属项目，所有单资源查询都必须带这个条件 */
    private Long projectId;

    /** 所属分类 */
    private Long categoryId;

    /** 资产编号，如 CHR_SM_0001。项目内唯一（{@code uk_project_asset_no}） */
    private String assetNo;

    /** 资产名称 */
    private String name;

    /** 资产说明 */
    private String description;

    /** 当前版本ID（指向 asset_version）。新建资产后即指向 V1 */
    private Long currentVersionId;

    /**
     * 版本总数。
     * 口径为「最大版本号」而非行数：版本只增不删，两者一致，
     * 但用最大版本号可以避免万一有历史清理时数字回退。
     */
    private Integer versionCount;

    /** IN_PROGRESS / PENDING / APPROVED / REJECTED / DEPRECATED，见 AssetStatus */
    private String status;

    /** P0 / P1 / P2，见 AssetPriority */
    private String priority;

    /** 标签，逗号分隔，如「场景,道具」 */
    private String tags;

    /** 主负责人（上传者 / 制作者） */
    private Long ownerId;

    /** 审核人（仅 APPROVED / REJECTED 时有值） */
    private Long reviewerId;

    /** 审核时间 */
    private LocalDateTime reviewTime;

    /** 审核意见（打回时必填） */
    private String reviewRemark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：删除资产只置 1，版本与状态日志全部保留以支撑追溯 */
    @TableLogic
    private Integer deleted;
}
