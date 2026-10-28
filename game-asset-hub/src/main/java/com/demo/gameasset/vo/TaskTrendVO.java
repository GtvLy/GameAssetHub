package com.demo.gameasset.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务新增 / 完成趋势（文档 4.4 的 {@code /stat/task-trend}，折线图数据源）。
 *
 * <p>窗口 {@link #days} 有上下限：太小看不出趋势（1 天没有「趋势」可言），
 * 太大则曲线被压在几个像素里。取 [7, 180]，默认 30 天。
 * 超出范围的值<b>返回 400 而不是静默夹紧</b>——静默夹紧会让
 * 「我传了 1 天，为什么给我 7 个点」变成一个没人能解释的现象。</p>
 *
 * <p><b>窗口是「今天往前 N 天」，含今天</b>：{@code endDate = today}、
 * {@code startDate = today - (days - 1)}。这样 30 天正好是 30 个点，
 * 而不是 31 个——把天数当成「点的个数」比当成「两个端点之差」更符合
 * 用户说「看最近 30 天」时的预期。</p>
 *
 * @author 管理员
 */
@Data
public class TaskTrendVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long projectId;

    /** 实际使用的窗口天数（已按 [7, 180] 夹紧） */
    private Integer days;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    /** 逐日点位，长度恒等于 {@link #days}，空日补 0 */
    private List<TrendPointVO> points = new ArrayList<>();

    /** 窗口内新增任务数合计 */
    private Integer totalCreated;

    /** 窗口内完成任务数合计 */
    private Integer totalCompleted;

    /** 净增（新增 − 完成）。为正表示任务在堆积，是排期是否失控的直接信号 */
    private Integer netGrowth;
}
