package com.demo.gameasset.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.demo.gameasset.common.enums.AssetStatus;
import com.demo.gameasset.common.enums.ProjectRole;
import com.demo.gameasset.common.enums.TaskColumn;
import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.entity.Project;
import com.demo.gameasset.mapper.ProjectMapper;
import com.demo.gameasset.mapper.ProjectReportMapper;
import com.demo.gameasset.mapper.StatMapper;
import com.demo.gameasset.service.ReportService;
import com.demo.gameasset.service.support.BurndownCalculator;
import com.demo.gameasset.service.support.ProjectAccessGuard;
import com.demo.gameasset.service.support.ReportRiskAdvisor;
import com.demo.gameasset.service.support.TaskOverviewSupport;
import com.demo.gameasset.vo.BurndownPointVO;
import com.demo.gameasset.vo.BurndownVO;
import com.demo.gameasset.vo.ReportMemberVO;
import com.demo.gameasset.vo.ReportVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目周报 / 月报实现（模块4，文档 5.10）。
 *
 * <p>四段结构照文档：任务进展 → 资产进展 → 成员产出 → 自动生成的结论与风险提示。
 * 最后一段由 {@link ReportRiskAdvisor} 生成（可被单测逐条钉住）。</p>
 *
 * <h3>三个刻意的取法</h3>
 * <ol>
 *   <li><b>进度判定按「周期结束日」而不是「今天」</b>：报表是给一段时间做结论的。
 *       补生成上个月的月报时用今天的进度判定，结论会张冠李戴。
 *       周期还没结束（比如本周的周报）时取今天——未来那段还没有实际数据。</li>
 *   <li><b>快照的 JSON 用手工组装的 ObjectMapper 写</b>：见
 *       {@link #serializeSnapshot} 的注释（注入的 ObjectMapper 已经注册了
 *       JavaTimeModule 与 {@code yyyy-MM-dd} 格式，直接换 fastjson 的
 *       {@code JSON.toJSONString} 会把 LocalDate 写成数组）。</li>
 *   <li><b>快照写失败不影响返回</b>：快照是留痕，报表本身才是用户要的东西。
 *       写快照抛异常时记 error 日志、把 {@code snapshotSaved} 置为 false 照常返回，
 *       而不是让用户看到 500。</li>
 * </ol>
 *
 * @author 管理员
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    /** 周报周期天数（文档 5.10：weekStart ~ weekStart + 6） */
    public static final int WEEK_DAYS = 7;

    public static final String TYPE_WEEK = "WEEK";

    public static final String TYPE_MONTH = "MONTH";

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final StatMapper statMapper;
    private final ProjectMapper projectMapper;
    private final ProjectReportMapper projectReportMapper;
    private final ProjectAccessGuard accessGuard;
    private final TaskOverviewSupport taskOverviewSupport;
    private final BurndownCalculator burndownCalculator;
    private final ReportRiskAdvisor riskAdvisor;
    private final ObjectMapper objectMapper;

    // ==================== 周报 / 月报 ====================

    @Override
    public ReportVO weekly(Long projectId, LocalDate weekStart) {
        requireMember(projectId);
        // 不传起始日就取「本周一」：用户点「生成周报」时想的是本周，
        // 而不是「今天起的七天」（那样周一生成会横跨两个自然周）
        LocalDate start = weekStart != null
                ? weekStart
                : LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return generate(projectId, TYPE_WEEK, start, start.plusDays(WEEK_DAYS - 1L));
    }

    @Override
    public ReportVO monthly(Long projectId, String month) {
        requireMember(projectId);
        YearMonth target;
        if (month == null || month.isBlank()) {
            target = YearMonth.now();
        } else {
            try {
                target = YearMonth.parse(month.trim(), MONTH_FORMAT);
            } catch (DateTimeParseException e) {
                throw BizException.badRequest("month 格式应为 yyyy-MM，当前取值：" + month);
            }
        }
        return generate(projectId, TYPE_MONTH, target.atDay(1), target.atEndOfMonth());
    }

    // ==================== 组装 ====================

    private ReportVO generate(Long projectId, String reportType,
                              LocalDate periodStart, LocalDate periodEnd) {
        Project project = getProject(projectId);

        ReportVO report = new ReportVO();
        report.setProjectId(projectId);
        report.setProjectName(project.getName());
        report.setReportType(reportType);
        report.setReportTypeLabel(TYPE_MONTH.equals(reportType) ? "月报" : "周报");
        report.setPeriodStart(periodStart);
        report.setPeriodEnd(periodEnd);
        report.setPeriod(periodStart + " ~ " + periodEnd);
        report.setGeneratedAt(LocalDateTime.now());

        // 1. 任务进展
        report.setTaskCreated(nz(statMapper.countTaskCreatedBetween(projectId, periodStart, periodEnd)));
        report.setTaskCompleted(nz(statMapper.countTaskCompletedBetween(projectId, periodStart, periodEnd)));
        report.setTaskOverdue(taskOverviewSupport.countOverdue(projectId));
        report.setTaskTotal(taskOverviewSupport.countTasks(projectId));
        report.setTaskDone(taskOverviewSupport.countByColumn(projectId, TaskColumn.FINISHED));

        // 燃尽图（截至周期结束日）——剩余故事点与进度判定都取自它，
        // 保证报表里的「剩余故事点」与燃尽图上的当前点永远一致
        LocalDate asOf = periodEnd.isAfter(LocalDate.now()) ? LocalDate.now() : periodEnd;
        BurndownVO burndown = burndownAsOf(project, asOf);
        report.setRemainingPoints(burndown.getCurrentPoints());
        report.setBurndownStatus(burndown.getScheduleStatus());
        report.setBurndownStatusLabel(burndown.getScheduleStatusLabel());

        // 2. 资产进展
        report.setAssetAdded(nz(statMapper.countAssetCreatedBetween(projectId, periodStart, periodEnd)));
        report.setAssetApproved(nz(statMapper.countAssetApprovedBetween(projectId, periodStart, periodEnd)));
        report.setAssetPendingReview(nz(
                statMapper.countAssetByStatus(projectId, AssetStatus.PENDING.name())));

        // 3. 成员产出
        List<ReportMemberVO> members = statMapper.selectPeriodWorkload(projectId, periodStart, periodEnd);
        for (ReportMemberVO member : members) {
            member.setRoleLabel(ProjectRole.label(member.getRole()));
        }
        report.setWorkloads(members == null ? new ArrayList<>() : members);

        // 4. 结论与风险提示
        riskAdvisor.advise(report);

        // 5. 落快照（幂等 upsert）
        report.setSnapshotSaved(saveSnapshot(projectId, reportType, periodStart, periodEnd, report));

        log.info("[报表] 生成成功 projectId={} type={} period={} 风险数={}",
                projectId, reportType, report.getPeriod(), report.getRisks().size());
        return report;
    }

    /**
     * 取「截至 asOf 这天」的燃尽图。
     *
     * <p>与 {@code StatServiceImpl#burndown} 共用同一个计算器与同一条回放 SQL，
     * 因此报表里的进度判定与统计页上的燃尽图<b>必然一致</b>——
     * 两处各写一份算法，早晚会出现「报表说落后、燃尽图说正常」。
     * 这里只是把「今天」换成「周期结束日」。</p>
     */
    private BurndownVO burndownAsOf(Project project, LocalDate asOf) {
        int totalPoints = nz(statMapper.sumStoryPoint(project.getId()));
        BurndownCalculator.Window window =
                burndownCalculator.resolveWindow(project.getStartDate(), project.getPlanEndDate(), asOf);

        List<BurndownPointVO> actual = new ArrayList<>();
        if (window.plannable() && window.getActualEnd() != null) {
            actual = statMapper.selectDailyRemainingPoints(
                    project.getId(), window.getStart(), window.getActualEnd());
        }
        return burndownCalculator.assemble(project.getId(), project.getStartDate(),
                project.getPlanEndDate(), asOf, totalPoints, actual);
    }

    // ==================== 快照 ====================

    /**
     * 写快照。失败只记日志并返回 false，不向上抛——报表本身照常返回。
     *
     * @return 快照是否写入成功
     */
    private boolean saveSnapshot(Long projectId, String reportType,
                                 LocalDate periodStart, LocalDate periodEnd, ReportVO report) {
        String json = serializeSnapshot(report);
        if (json == null) {
            return false;
        }
        try {
            // 注意：这里刻意不看受影响行数。MySQL 的 INSERT ... ON DUPLICATE KEY UPDATE
            // 在「命中了已有行、但新值与旧值完全相同」时返回 0 行，
            // 而快照内容里带 generatedAt，实际上几乎不会相同。
            // 但把「返回 0」当成失败是错的——它同样表示快照已存在且内容一致，
            // 属于成功。判断成功的依据是「语句没有抛异常」。
            projectReportMapper.upsertSnapshot(projectId, reportType, periodStart, periodEnd, json);
            return true;
        } catch (Exception e) {
            log.error("[报表] 快照写入失败（不影响报表返回）projectId={} type={} periodStart={}",
                    projectId, reportType, periodStart, e);
            return false;
        }
    }

    /**
     * 把报表序列化成快照 JSON。
     *
     * <p><b>用注入的 {@code ObjectMapper}，不要用 fastjson 的
     * {@code JSON.toJSONString}</b>：项目里没有 fastjson 依赖，而且
     * Jackson 的默认行为是把 {@code LocalDate} 写成 {@code [2026,9,21]}
     * 这样的数组（{@code WRITE_DATES_AS_TIMESTAMPS}），
     * 快照里的日期就变成了没人认得的东西。本项目的
     * {@code JacksonConfig#objectMapper()} 已经注册了 JavaTimeModule 并把
     * 日期固定成 {@code yyyy-MM-dd} / {@code yyyy-MM-dd HH:mm:ss}，
     * 直接复用它，格式与接口响应完全一致。</p>
     *
     * @return JSON 字符串；序列化失败返回 null（调用方据此把 snapshotSaved 置 false）
     */
    private String serializeSnapshot(ReportVO report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            log.error("[报表] 快照序列化失败 projectId={}", report.getProjectId(), e);
            return null;
        }
    }

    // ==================== 内部工具 ====================

    private void requireMember(Long projectId) {
        accessGuard.requireActiveMember(projectId, UserContext.requireUserId());
    }

    private Project getProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw BizException.notFound("项目不存在或已归档");
        }
        return project;
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
