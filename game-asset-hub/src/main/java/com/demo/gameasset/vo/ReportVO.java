package com.demo.gameasset.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目周报 / 月报（文档 5.10 的 {@code ReportVO}）。
 *
 * <p>结构分四段，与文档 5.10 的四个步骤一一对应：</p>
 * <ol>
 *   <li>任务进展：{@link #taskCreated} / {@link #taskCompleted} /
 *       {@link #taskOverdue} / {@link #remainingPoints}；</li>
 *   <li>资产进展：{@link #assetAdded} / {@link #assetApproved} /
 *       {@link #assetPendingReview}；</li>
 *   <li>成员产出：{@link #workloads}；</li>
 *   <li>风险提示与结论文本：{@link #risks} 与 {@link #summary}。</li>
 * </ol>
 *
 * <p><b>{@link #summary} 是「自动生成的结论文本」</b>：文档要求的是
 * 「把过程数据转成可决策的结论」，光堆一堆数字不算结论。
 * 它由 {@code ReportRiskAdvisor} 依据四段数据拼出来，与 {@link #risks}
 * 一样是可以被单元测试逐条钉住的行为（触发条件、文案、边界）。</p>
 *
 * <p>{@link #weekStart} / {@link #reportType} / {@link #snapshotSaved}
 * 用于回答「这份报表是哪次生成的、快照存下来了没有」，不算报表内容本身。</p>
 *
 * @author 管理员
 */
@Data
public class ReportVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long projectId;

    private String projectName;

    /** {@code WEEK} / {@code MONTH} */
    private String reportType;

    /** 报表类型中文名，前端标题直接用 */
    private String reportTypeLabel;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate periodStart;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate periodEnd;

    /** 周期展示文本，如 {@code 2026-09-21 ~ 2026-09-27}（文档 5.10 的 period） */
    private String period;

    // ==================== 1. 任务进展 ====================

    /** 周期内新建任务数 */
    private Integer taskCreated;

    /** 周期内完成任务数 */
    private Integer taskCompleted;

    /** 截至生成时刻的逾期任务数（不是周期内的，是当前存量） */
    private Integer taskOverdue;

    /** 项目剩余故事点（= 燃尽图的 currentPoints 口径） */
    private Integer remainingPoints;

    /** 项目任务总数 */
    private Integer taskTotal;

    /** 项目已完成任务数 */
    private Integer taskDone;

    /** 截至周期结束日的燃尽图进度判定：AHEAD / ON_TRACK / BEHIND */
    private String burndownStatus;

    private String burndownStatusLabel;

    // ==================== 2. 资产进展 ====================

    /** 周期内新增资产数 */
    private Integer assetAdded;

    /** 周期内通过审核的资产数 */
    private Integer assetApproved;

    /** 待审核资产存量 */
    private Integer assetPendingReview;

    // ==================== 3. 成员产出 ====================

    private List<ReportMemberVO> workloads = new ArrayList<>();

    // ==================== 4. 结论 ====================

    /** 自动生成的风险提示，按严重程度排列；没有风险时为空列表（不是 null） */
    private List<String> risks = new ArrayList<>();

    /** 自动生成的结论文本（一句话总结本周期） */
    private String summary;

    // ==================== 元信息 ====================

    /** 生成时刻 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime generatedAt;

    /** 快照是否已写入 {@code project_report}（写失败时为 false，但报表仍然返回） */
    private Boolean snapshotSaved;
}
