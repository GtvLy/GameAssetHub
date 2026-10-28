package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 资产状态流转记录（详情页的审核轨迹）。
 *
 * @author 管理员
 */
@Data
public class AssetStatusLogVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long assetId;

    private String fromStatus;

    /** 变更前状态中文名 */
    private String fromStatusLabel;

    private String toStatus;

    /** 变更后状态中文名 */
    private String toStatusLabel;

    private Long operatorId;

    /** 操作人昵称：审核轨迹上必须看到「谁批的」 */
    private String operatorName;

    /** 变更说明 / 审核意见 */
    private String remark;

    private LocalDateTime createTime;
}
