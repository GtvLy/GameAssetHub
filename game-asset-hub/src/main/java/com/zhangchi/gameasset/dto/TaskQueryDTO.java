package com.zhangchi.gameasset.dto;

import lombok.Data;

/**
 * 任务列表筛选与分页参数（文档 4.2：分页 + 筛选）。
 *
 * <p>看板页面本身用 {@code /board}（一次拿四列）；这个接口服务于
 * 「任务列表」这类需要跨列筛选与分页的场景，例如
 * 「我的全部逾期任务」「这个资产相关的任务」。</p>
 *
 * @author 张忠驰
 */
@Data
public class TaskQueryDTO {

    /** 页码，从 1 开始 */
    private Integer page = 1;

    /** 每页条数，上限由 MyBatis-Plus 分页插件统一限制（200） */
    private Integer size = 20;

    /** 只看某一列：TODO / DOING / REVIEW / DONE */
    private String columnStatus;

    /** 按负责人筛选 */
    private Long assigneeId;

    /** 按创建人筛选 */
    private Long creatorId;

    /** 按关联资产筛选（资产详情页的「相关任务」也走它） */
    private Long assetId;

    /** P0 / P1 / P2 */
    private String priority;

    /** ART / PROGRAM / DESIGN / BUG / OTHER */
    private String taskType;

    /** 只看未完成的（{@code column_status != 'DONE'}），概览的「我的待办」用它 */
    private Boolean unfinishedOnly;

    /**
     * 只看逾期：有截止日期、早于今天、且未完成。
     * 传 false 或 null 时不参与筛选。
     */
    private Boolean overdueOnly;

    /** 关键字：模糊匹配任务标题 */
    private String keyword;

    /** 排序字段：sortOrder（默认，按看板列内顺序）/ dueDate / updateTime / priority */
    private String orderBy;

    /** 归一化页码：非法值一律回落到第 1 页 */
    public int normalizedPage() {
        return page == null || page < 1 ? 1 : page;
    }

    /** 归一化页大小：非法值回落到 20，过大值由分页插件再兜一道 */
    public int normalizedSize() {
        if (size == null || size < 1) {
            return 20;
        }
        return Math.min(size, 200);
    }

    public boolean onlyUnfinished() {
        return Boolean.TRUE.equals(unfinishedOnly);
    }

    public boolean onlyOverdue() {
        return Boolean.TRUE.equals(overdueOnly);
    }
}
