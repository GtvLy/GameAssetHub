package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 单个成员的工作量（文档 5.7 的 {@code selectWorkload} 结果行）。
 *
 * <p>「成员」只包含项目里 <b>ACTIVE</b> 的成员：被邀请但没加入的人
 * 不可能有任何产出，把他列在表里只会让「人均任务量」被摊薄，
 * 从而掩盖真正的不均衡。</p>
 *
 * <p>{@link #taskTotal} 等字段在某人完全没有任务时是 0 而不是 null：
 * 聚合 SQL 里对 {@code LEFT JOIN} 出来的行做了 {@code COALESCE}，
 * 否则前端表格会出现「—」与「0」混排，还得多写一层判空。</p>
 *
 * @author 管理员
 */
@Data
public class WorkloadItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;

    private String nickname;

    /** 项目内角色：OWNER / ARTIST / PROGRAMMER / DESIGNER / VIEWER */
    private String role;

    /** 角色中文名，前端表格与环形图图例直接用 */
    private String roleLabel;

    /** 指派给该成员的任务数 */
    private Integer taskTotal;

    /** 其中已完成（column_status = 'DONE'）的任务数 */
    private Integer taskDone;

    /** 完成率（%）= taskDone / taskTotal，保留一位小数；没有任务时为 0.0 */
    private Double taskCompletionRate;

    /** 预估工时合计 */
    private BigDecimal estimateHours;

    /** 实际工时合计（未填报的按 0 计） */
    private BigDecimal actualHours;

    /** 故事点合计（燃尽图的分子来源，这里按人拆开） */
    private Integer points;

    /** 在该项目上传的资产版本数 */
    private Integer versionUploaded;

    /** 在该项目通过的资产数（owner_id = 该成员且 status = APPROVED） */
    private Integer assetApproved;

    /**
     * 任务数是否低于全员平均值（判定「谁被摊薄了」）。
     * 由服务端标注，前端不必自己跟平均值比。
     */
    private Boolean belowAverage;
}
