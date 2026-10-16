package com.zhangchi.gameasset.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 燃尽图数据（文档 5.6 + 4.4 的响应示例）。
 *
 * <h3>两条线的口径（写在这里，避免各处理解不同）</h3>
 * <ul>
 *   <li><b>理想线 {@link #idealLine}</b>：从 {@code startDate} 到 {@code planEndDate}
 *       <b>整段区间</b>按天线性递减，覆盖计划全周期。不因「今天到没到」而截断——
 *       理想线是计划，计划不会因为今天还没到就少画一段。</li>
 *   <li><b>实际线 {@link #actualLine}</b>：只画到 {@code min(planEndDate, today)}。
 *       未来还没发生的日子不该有一条「实际」曲线。今天的点位按当天的状态日志回放。</li>
 * </ul>
 *
 * <p>两者共用同一个「按天」的类目轴，前端可以把它们画在同一张图上，
 * 不会出现一条是周粒度、另一条是日粒度导致横轴对不齐。</p>
 *
 * @author 张忠驰
 */
@Data
public class BurndownVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long projectId;

    /** 计划开始日期（取自 {@code project.start_date}） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /** 计划结束日期（取自 {@code project.plan_end_date}） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    /** 理想线实际绘制的终点 = {@code min(endDate, today)}；早于开始日期时等于 startDate */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate actualEndDate;

    /** 今天（服务端日期，前端不用自己算，避免客户端时钟不准把图画错） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate today;

    /** 总故事点。无 story_point 的任务按 0 计，见 {@code StatMapper#sumStoryPoint} 的注释 */
    private Integer totalPoints;

    /** 计划总天数 */
    private Long totalDays;

    /** 已消耗天数（已按 [0, totalDays] 夹紧，今天在区间前为 0、区间后为 totalDays） */
    private Long elapsedDays;

    /** 理想线（覆盖 [startDate, endDate] 全区间，逐日） */
    private List<BurndownPointVO> idealLine = new ArrayList<>();

    /** 实际线（覆盖 [startDate, min(endDate, today)]，逐日，用 task_status_log 回放） */
    private List<BurndownPointVO> actualLine = new ArrayList<>();

    /** 当前剩余故事点 = 实际线最后一个点；实际线为空时退化为 {@link #totalPoints} */
    private Integer currentPoints;

    /** 进度判定：AHEAD / ON_TRACK / BEHIND */
    private String scheduleStatus;

    /** 进度判定中文，前端直接显示，不必再维护一份映射 */
    private String scheduleStatusLabel;

    /** 理想线在今天应有的剩余故事点（已按区间夹紧，前端可用于画对比参考） */
    private Integer idealPointsToday;

    /** 是否已经过了计划结束日期（过了之后「落后」不再随时间恶化，判据要换一种说法） */
    private Boolean overduePlan;
}
