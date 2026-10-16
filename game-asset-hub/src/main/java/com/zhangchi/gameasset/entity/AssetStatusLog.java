package com.zhangchi.gameasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 资产状态流转记录（{@code asset_status_log}，文档 3.2 DDL）。
 *
 * <p>每一次状态变更都<b>必须</b>落一条，不允许只在 {@code asset} 表上改状态：
 * 审核过程是协作中的争议点（「这个打回是谁批的、理由是什么」），
 * 只留最终状态等于把过程丢掉了。模块4 的资产流转统计也会读这张表。</p>
 *
 * <p>本表只追加，不修改、不删除。</p>
 *
 * @author 张忠驰
 */
@Data
@TableName("asset_status_log")
public class AssetStatusLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属资产 */
    private Long assetId;

    /** 变更前状态（新建资产时无前态，可为 null） */
    private String fromStatus;

    /** 变更后状态 */
    private String toStatus;

    /** 操作人 */
    private Long operatorId;

    /** 变更说明 / 审核意见（打回时必填） */
    private String remark;

    private LocalDateTime createTime;
}
