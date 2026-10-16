package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内通知（文档 4.2 通知列表 / 标记已读）。
 *
 * <p>{@link #bizType} + {@link #bizId} 是前端跳转的依据：
 * {@code TASK} 跳任务详情、{@code ASSET} 跳资产详情。少了它们，
 * 铃铛里的条目点下去只能跳首页——通知的价值就只剩「提醒你有事」。</p>
 *
 * @author 张忠驰
 */
@Data
public class NotificationVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long projectId;

    /** TASK_ASSIGNED / TASK_STATUS / COMMENT / MENTION / ASSET_REVIEW / TASK_DUE_SOON */
    private String type;

    private String typeLabel;

    private String title;

    private String content;

    /** TASK / ASSET / PROJECT */
    private String bizType;

    private Long bizId;

    /** 触发人，为空表示系统触发（定时任务） */
    private Long senderId;

    private String senderName;

    /** 0 未读 / 1 已读 */
    private Integer isRead;

    private LocalDateTime createTime;
}
