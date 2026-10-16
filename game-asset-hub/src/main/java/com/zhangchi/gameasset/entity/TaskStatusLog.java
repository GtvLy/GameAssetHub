package com.zhangchi.gameasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务状态变更日志（{@code task_status_log}，文档 3.2 DDL）。
 *
 * <p><b>只追加，不修改不删除</b>（表上没有 deleted 列，物理上也不允许删除）。
 * 原因在文档 5.6 说得最清楚：模块4 的燃尽图要用<b>状态变更日志回放</b>
 * 每日快照，而不是看任务的当前状态——否则历史曲线会随着今天的操作被改写，
 * 「上周的曲线和这周看到的不一样」这种问题根本无从排查。</p>
 *
 * <p>因此每次 {@code column_status} 变化都必须落一条，无例外、无省略。</p>
 *
 * @author 张忠驰
 */
@Data
@TableName("task_status_log")
public class TaskStatusLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** 冗余项目ID：模块4 按项目维度聚合日志时不必 join task 表 */
    private Long projectId;

    /** 变更前的列；新建任务首次落位时为 null */
    private String fromStatus;

    /** 变更后的列 */
    private String toStatus;

    /** 操作人 */
    private Long operatorId;

    private LocalDateTime createTime;
}
