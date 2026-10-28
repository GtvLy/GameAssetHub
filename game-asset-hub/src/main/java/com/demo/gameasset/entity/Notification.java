package com.demo.gameasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内通知（{@code notification}，文档 3.2 DDL + 5.8 触发事件表）。
 *
 * <p>没有 {@code deleted} 列：通知是「已经发生过的事」，
 * 只标记已读，不提供删除。要清空未读就调用「全部已读」。</p>
 *
 * <p>{@link #bizType} + {@link #bizId} 是前端跳转的依据：
 * {@code TASK + 305} 跳任务详情，{@code ASSET + 128} 跳资产详情。
 * 少了这两个字段，铃铛里的条目点下去只能跳首页，通知的价值大打折扣。</p>
 *
 * @author 管理员
 */
@Data
@TableName("notification")
public class Notification implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属项目（文档 DDL 允许为空）。
     * 本项目产生的通知都会带上项目：前端按当前项目上下文过滤铃铛列表，
     * 可空是为了兼容「与项目无关的系统通知」这种将来可能有的场景。
     */
    private Long projectId;

    /** 接收人 */
    private Long userId;

    /** 类型，见 NotificationType */
    private String type;

    /** 通知标题（列表主行） */
    private String title;

    /** 通知正文（列表次行，可为空） */
    private String content;

    /** 业务类型：TASK / ASSET / PROJECT */
    private String bizType;

    /** 业务ID，前端跳转用 */
    private Long bizId;

    /** 触发人（可为空 = 系统触发，如定时任务） */
    private Long senderId;

    /** 0 未读 / 1 已读 */
    private Integer isRead;

    private LocalDateTime createTime;
}
