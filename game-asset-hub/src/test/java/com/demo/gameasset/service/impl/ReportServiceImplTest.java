package com.demo.gameasset.service.impl;

import com.demo.gameasset.common.enums.TaskColumn;
import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.config.JacksonConfig;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.entity.Project;
import com.demo.gameasset.mapper.ProjectMapper;
import com.demo.gameasset.mapper.ProjectReportMapper;
import com.demo.gameasset.mapper.StatMapper;
import com.demo.gameasset.service.support.BurndownCalculator;
import com.demo.gameasset.service.support.ProjectAccessGuard;
import com.demo.gameasset.service.support.ReportRiskAdvisor;
import com.demo.gameasset.service.support.TaskOverviewSupport;
import com.demo.gameasset.support.MybatisPlusTestSupport;
import com.demo.gameasset.vo.BurndownPointVO;
import com.demo.gameasset.vo.ReportMemberVO;
import com.demo.gameasset.vo.ReportVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 周报 / 月报测试（模块4，文档 5.10）。
 *
 * <p>三件「不测就一定错」的事：</p>
 * <ol>
 *   <li><b>快照 upsert 幂等</b>：同一周期重复生成必须不报错，且快照被刷新。
 *       这条在真机上要用「生成两次都 200 + 库里只有一行」来验，
 *       在单测里则要确认第二次仍然是走 upsert 语句、不会去先查后插。</li>
 *   <li><b>快照 JSON 里的日期格式</b>：{@code LocalDate} 在 Jackson 的默认行为下
 *       会被写成 {@code [2026,9,21]} 这样的数组，快照里的日期就没人认得了。
 *       这里断言序列化后的文本里出现 {@code "2026-09-21"}，把格式钉死。</li>
 *   <li><b>周期边界</b>：周报是 7 天、月报是自然月首末日；
 *       {@code month} 格式非法要 400 而不是 500。</li>
 * </ol>
 *
 * <p>Mockito 测试：不加载 Spring、不连 MySQL / Redis。</p>
 *
 * @author 管理员
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReportServiceImpl 周报与月报")
class ReportServiceImplTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long ME = 1L;

    @Mock
    private StatMapper statMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectReportMapper projectReportMapper;
    @Mock
    private ProjectAccessGuard accessGuard;
    @Mock
    private TaskOverviewSupport taskOverviewSupport;

    private ReportServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleFourEntities();
        // ObjectMapper 用生产配置的那一份：它是否把 LocalDate 写成 yyyy-MM-dd
        // 正是本测试要盯住的东西，换成裸 ObjectMapper 就测不到真实行为了
        service = new ReportServiceImpl(statMapper, projectMapper, projectReportMapper, accessGuard,
                taskOverviewSupport, new BurndownCalculator(), new ReportRiskAdvisor(),
                new JacksonConfig().objectMapper());
        UserContext.setUserId(ME);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** 把「一切正常的一周」准备好 */
    private void givenHealthyWeek(LocalDate start, LocalDate end) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("星港计划");
        project.setStartDate(start.minusMonths(3));
        project.setPlanEndDate(end.plusMonths(3));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);

        when(taskOverviewSupport.countTasks(PROJECT_ID)).thenReturn(12);
        when(taskOverviewSupport.countByColumn(PROJECT_ID, TaskColumn.FINISHED)).thenReturn(4);
        when(taskOverviewSupport.countOverdue(PROJECT_ID)).thenReturn(0);
        when(statMapper.countTaskCreatedBetween(eq(PROJECT_ID), any(), any())).thenReturn(3);
        when(statMapper.countTaskCompletedBetween(eq(PROJECT_ID), any(), any())).thenReturn(3);
        when(statMapper.countAssetCreatedBetween(eq(PROJECT_ID), any(), any())).thenReturn(2);
        when(statMapper.countAssetApprovedBetween(eq(PROJECT_ID), any(), any())).thenReturn(1);
        when(statMapper.countAssetByStatus(eq(PROJECT_ID), anyString())).thenReturn(1);
        when(statMapper.sumStoryPoint(PROJECT_ID)).thenReturn(60);
        when(statMapper.selectDailyRemainingPoints(eq(PROJECT_ID), any(), any()))
                .thenReturn(new ArrayList<>(List.of(
                        new BurndownPointVO(start, 60),
                        new BurndownPointVO(end, 30))));

        ReportMemberVO member = new ReportMemberVO();
        member.setUserId(2L);
        member.setNickname("李阳");
        member.setRole("ARTIST");
        member.setTaskCreated(1);
        member.setTaskCompleted(2);
        when(statMapper.selectPeriodWorkload(eq(PROJECT_ID), any(), any()))
                .thenReturn(new ArrayList<>(List.of(member)));
    }

    // ==================== 周期解析 ====================

    @Test
    @DisplayName("不传 weekStart 时取「本周一」，周期是 7 天而不是「今天起 7 天」")
    void weeklyDefaultsToMondayOfThisWeek() {
        givenHealthyWeek(LocalDate.now().minusDays(3), LocalDate.now().plusDays(3));

        ReportVO report = service.weekly(PROJECT_ID, null);

        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(report.getPeriodStart()).isEqualTo(monday);
        assertThat(report.getPeriodEnd()).isEqualTo(monday.plusDays(6));
        assertThat(report.getPeriod()).isEqualTo(monday + " ~ " + monday.plusDays(6));
        assertThat(report.getReportType()).isEqualTo("WEEK");
        assertThat(report.getReportTypeLabel()).isEqualTo("周报");
    }

    @Test
    @DisplayName("显式传入 weekStart 时原样使用（不强行对齐到周一，便于补生成任意 7 天）")
    void weeklyHonoursExplicitStart() {
        LocalDate start = LocalDate.of(2026, 9, 21);
        givenHealthyWeek(start, start.plusDays(6));

        ReportVO report = service.weekly(PROJECT_ID, start);

        assertThat(report.getPeriodStart()).isEqualTo(start);
        assertThat(report.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 27));
    }

    @Test
    @DisplayName("月报周期 = 自然月首日到末日（含 2 月与 31 天的月份）")
    void monthlyUsesCalendarMonth() {
        givenHealthyWeek(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertThat(service.monthly(PROJECT_ID, "2026-09").getPeriodStart())
                .isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(service.monthly(PROJECT_ID, "2026-09").getPeriodEnd())
                .isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(service.monthly(PROJECT_ID, "2026-02").getPeriodEnd())
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(service.monthly(PROJECT_ID, "2026-02").getReportType()).isEqualTo("MONTH");
        assertThat(service.monthly(PROJECT_ID, "2026-02").getReportTypeLabel()).isEqualTo("月报");
    }

    @Test
    @DisplayName("不传 month 时取当前月")
    void monthlyDefaultsToCurrentMonth() {
        YearMonth now = YearMonth.now();
        givenHealthyWeek(now.atDay(1), now.atEndOfMonth());

        ReportVO report = service.monthly(PROJECT_ID, null);

        assertThat(report.getPeriodStart()).isEqualTo(now.atDay(1));
        assertThat(report.getPeriodEnd()).isEqualTo(now.atEndOfMonth());
    }

    @Test
    @DisplayName("month 格式非法（2026-13 / 九月 / 空串语义之外的乱写）→ 400，不是 500、也不是默认当前月")
    void monthlyRejectsBadFormat() {
        assertThatThrownBy(() -> service.monthly(PROJECT_ID, "2026-13"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("yyyy-MM");
        assertThatThrownBy(() -> service.monthly(PROJECT_ID, "2026/09"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("yyyy-MM");
        assertThatThrownBy(() -> service.monthly(PROJECT_ID, "九月"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("yyyy-MM");
    }

    // ==================== 四段内容 ====================

    @Test
    @DisplayName("四段内容齐全：任务进展 / 资产进展 / 成员产出 / 结论与风险")
    void reportContainsAllFourSections() {
        LocalDate start = LocalDate.of(2026, 9, 21);
        givenHealthyWeek(start, start.plusDays(6));

        ReportVO report = service.weekly(PROJECT_ID, start);

        assertThat(report.getTaskCreated()).isEqualTo(3);
        assertThat(report.getTaskCompleted()).isEqualTo(3);
        assertThat(report.getTaskTotal()).isEqualTo(12);
        assertThat(report.getTaskDone()).isEqualTo(4);
        assertThat(report.getAssetAdded()).isEqualTo(2);
        assertThat(report.getAssetApproved()).isEqualTo(1);
        assertThat(report.getAssetPendingReview()).isEqualTo(1);
        assertThat(report.getRemainingPoints()).isEqualTo(30);
        assertThat(report.getWorkloads()).hasSize(1);
        assertThat(report.getWorkloads().get(0).getRoleLabel()).isEqualTo("美术");
        assertThat(report.getRisks()).isEmpty();
        assertThat(report.getSummary()).contains("未发现明显风险");
        assertThat(report.getGeneratedAt()).isNotNull();
    }

    @Test
    @DisplayName("有逾期任务时结论里带上风险提示")
    void reportSurfacesRisks() {
        LocalDate start = LocalDate.of(2026, 9, 21);
        givenHealthyWeek(start, start.plusDays(6));
        when(taskOverviewSupport.countOverdue(PROJECT_ID)).thenReturn(2);

        ReportVO report = service.weekly(PROJECT_ID, start);

        assertThat(report.getRisks()).anyMatch(risk -> risk.contains("有 2 个任务已逾期"));
        assertThat(report.getSummary()).contains("共需关注 1 项风险");
    }

    // ==================== 快照 ====================

    @Test
    @DisplayName("生成即落快照，走的是 upsert（不是先查后插），snapshotSaved = true")
    void writesSnapshotThroughUpsert() {
        LocalDate start = LocalDate.of(2026, 9, 21);
        givenHealthyWeek(start, start.plusDays(6));

        ReportVO report = service.weekly(PROJECT_ID, start);

        verify(projectReportMapper).upsertSnapshot(eq(PROJECT_ID), eq("WEEK"),
                eq(start), eq(start.plusDays(6)), anyString());
        assertThat(report.getSnapshotSaved()).isTrue();
    }

    @Test
    @DisplayName("同一周期重复生成：两次都成功，第二次仍然是 upsert（幂等，不靠捕获唯一键异常再重查）")
    void repeatedGenerationIsIdempotent() {
        LocalDate start = LocalDate.of(2026, 9, 21);
        givenHealthyWeek(start, start.plusDays(6));

        ReportVO first = service.weekly(PROJECT_ID, start);
        ReportVO second = service.weekly(PROJECT_ID, start);

        assertThat(first.getSnapshotSaved()).isTrue();
        assertThat(second.getSnapshotSaved()).isTrue();
        verify(projectReportMapper, org.mockito.Mockito.times(2))
                .upsertSnapshot(eq(PROJECT_ID), eq("WEEK"), eq(start), eq(start.plusDays(6)), anyString());
        // 关键：全程没有对 project_report 做「先查一次」——
        // 那种写法在并发下会漏读，本轮在项目02 上真实踩过
        verify(projectReportMapper, never()).selectSnapshot(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("快照 JSON 里的日期是 yyyy-MM-dd 字符串，不是 Jackson 默认的 [2026,9,21] 数组")
    void snapshotJsonUsesReadableDates() {
        LocalDate start = LocalDate.of(2026, 9, 21);
        givenHealthyWeek(start, start.plusDays(6));

        service.weekly(PROJECT_ID, start);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(projectReportMapper).upsertSnapshot(eq(PROJECT_ID), eq("WEEK"), eq(start), any(), json.capture());

        assertThat(json.getValue())
                .contains("\"periodStart\":\"2026-09-21\"")
                .contains("\"periodEnd\":\"2026-09-27\"")
                .contains("星港计划")
                .doesNotContain("[2026,9,21]");
    }

    @Test
    @DisplayName("快照写失败不影响报表返回（snapshotSaved = false，正文照旧）")
    void snapshotFailureDoesNotBreakTheReport() {
        LocalDate start = LocalDate.of(2026, 9, 21);
        givenHealthyWeek(start, start.plusDays(6));
        when(projectReportMapper.upsertSnapshot(anyLong(), anyString(), any(), any(), anyString()))
                .thenThrow(new RuntimeException("表不存在"));

        ReportVO report = service.weekly(PROJECT_ID, start);

        assertThat(report.getSnapshotSaved()).isFalse();
        assertThat(report.getTaskCreated()).isEqualTo(3);
        assertThat(report.getSummary()).isNotBlank();
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("项目不存在 → 404；非成员 → 403，且一次统计都不查")
    void accessAndExistenceGuards() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.weekly(PROJECT_ID, LocalDate.of(2026, 9, 21)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("项目不存在");

        when(accessGuard.requireActiveMember(anyLong(), anyLong()))
                .thenThrow(BizException.forbidden("你不是该项目成员，无权访问"));
        assertThatThrownBy(() -> service.weekly(PROJECT_ID, LocalDate.of(2026, 9, 21)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是该项目成员");
        assertThatThrownBy(() -> service.monthly(PROJECT_ID, "2026-09"))
                .isInstanceOf(BizException.class);
        verify(statMapper, never()).countTaskCreatedBetween(anyLong(), any(), any());
    }

    @Test
    @DisplayName("周期还没结束时，燃尽图判定取「今天」而不是未来的周期结束日")
    void burndownAsOfIsClampedToToday() {
        LocalDate start = LocalDate.now().minusDays(2);
        givenHealthyWeek(start, start.plusDays(6));

        service.weekly(PROJECT_ID, start);

        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(statMapper).selectDailyRemainingPoints(eq(PROJECT_ID), any(), to.capture());
        assertThat(to.getValue()).isEqualTo(LocalDate.now());
    }
}
