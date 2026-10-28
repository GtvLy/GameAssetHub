package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目概览首页聚合数据（走 Redis 缓存，TTL 10 分钟）。
 *
 * <p><b>字段填充说明</b>：{@code asset*} 与 {@code task*} 系列指标
 * 依赖 {@code asset} / {@code task} 两张表，它们属于模块2 / 模块3。
 * 模块2 的 {@code assetTotal / assetApproved / assetPending /
 * assetCompletionRate / recentAssets} 由 {@code AssetOverviewSupport#fill}
 * 在写入缓存前补齐；模块3 的 {@code taskTotal / taskDone / taskOverdue}
 * 由 {@code TaskOverviewSupport#fill} 同处补齐。两者都是<b>项目维度</b>数据，
 * 可以随整份 VO 一起进缓存。</p>
 *
 * <p>缓存注意：{@link #myTasks} 是「当前登录用户的待办」，属于<b>用户维度</b>数据，
 * <b>绝不进缓存</b>。它由 {@code TaskOverviewSupport#withMyTasks} 在缓存之外
 * 单独查、挂到 VO 的副本上——若直接写进这份按项目维度缓存的对象，
 * A 打开概览就会看到 B 的待办清单。</p>
 *
 * @author 管理员
 */
@Data
public class ProjectOverviewVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long projectId;

    private String projectName;

    /** 项目状态 ACTIVE / PAUSED / ARCHIVED */
    private String projectStatus;

    /** 成员数 */
    private Integer memberCount;

    // ==================== 资产管理（模块2 填充） ====================

    /** 资产总数 */
    private Integer assetTotal;

    /** 已通过审核的资产数 */
    private Integer assetApproved;

    /** 待审核资产数 */
    private Integer assetPending;

    /** 资产完成率（%），= assetApproved / assetTotal * 100，保留一位小数 */
    private Double assetCompletionRate;

    // ==================== 任务看板（模块3 填充） ====================

    /** 任务总数 */
    private Integer taskTotal;

    /** 已完成任务数 */
    private Integer taskDone;

    /** 已逾期任务数 */
    private Integer taskOverdue;

    // ==================== 时间 ====================

    /** 距计划结束日期剩余天数，已过期返回负数，未设置计划结束日期返回 null */
    private Long daysToDeadline;

    /** 最近更新的资产（模块2 填充，最多 6 条） */
    private List<Object> recentAssets = new ArrayList<>();

    /** 我的待办任务（模块3 填充，最多 5 条，不进缓存） */
    private List<Object> myTasks = new ArrayList<>();
}
