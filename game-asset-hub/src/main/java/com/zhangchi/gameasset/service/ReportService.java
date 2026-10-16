package com.zhangchi.gameasset.service;

import com.zhangchi.gameasset.vo.ReportVO;

import java.time.LocalDate;

/**
 * 项目周报 / 月报（模块4，文档 4.4 的 {@code /api/projects/{pid}/report/**}）。
 *
 * <p>生成即落快照（{@code project_report}），同一项目 + 同类型 + 同周期
 * 重复生成是<b>幂等</b>的：不报错，快照被刷新（见
 * {@code ProjectReportMapper#upsertSnapshot}）。</p>
 *
 * @author 张忠驰
 */
public interface ReportService {

    /**
     * 生成周报（周期 = 起始日起连续 7 天）。
     *
     * @param weekStart 周期起始日；为 null 时取「本周一」
     */
    ReportVO weekly(Long projectId, LocalDate weekStart);

    /**
     * 生成月报（周期 = 自然月首日到末日）。
     *
     * @param month {@code yyyy-MM} 格式；为 null 时取当前月
     */
    ReportVO monthly(Long projectId, String month);
}
