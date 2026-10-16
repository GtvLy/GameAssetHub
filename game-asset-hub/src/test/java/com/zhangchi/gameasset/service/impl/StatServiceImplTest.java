package com.zhangchi.gameasset.service.impl;

import com.zhangchi.gameasset.common.constant.RedisKeys;
import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.context.UserContext;
import com.zhangchi.gameasset.entity.Project;
import com.zhangchi.gameasset.mapper.ProjectMapper;
import com.zhangchi.gameasset.mapper.StatMapper;
import com.zhangchi.gameasset.service.support.BurndownCalculator;
import com.zhangchi.gameasset.service.support.ProjectAccessGuard;
import com.zhangchi.gameasset.service.support.ProjectStatCache;
import com.zhangchi.gameasset.service.support.StatExcelWriter;
import com.zhangchi.gameasset.service.support.StatExportFile;
import com.zhangchi.gameasset.service.support.WorkloadBalanceCalculator;
import com.zhangchi.gameasset.support.MybatisPlusTestSupport;
import com.zhangchi.gameasset.vo.AssetCategoryStatVO;
import com.zhangchi.gameasset.vo.AssetCompletionVO;
import com.zhangchi.gameasset.vo.BurndownPointVO;
import com.zhangchi.gameasset.vo.BurndownVO;
import com.zhangchi.gameasset.vo.StatCountVO;
import com.zhangchi.gameasset.vo.TaskTrendVO;
import com.zhangchi.gameasset.vo.TrendPointVO;
import com.zhangchi.gameasset.vo.WorkloadItemVO;
import com.zhangchi.gameasset.vo.WorkloadVO;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统计服务测试（模块4）。
 *
 * <h3>为什么把「实际线的日期范围」单独拎出来断言</h3>
 * <p>燃尽图的实际线有两处极易写错、而图上又看不出来的地方：</p>
 * <ol>
 *   <li><b>把 {@code planEnd} 当成实际线的结束日传下去</b>：未来还没发生的日子
 *       会得到一条「实际」曲线。图上完全看不出来——那时的剩余故事点恰好等于
 *       当时的总量，曲线平平地延伸到未来，看着很合理。</li>
 *   <li><b>今天早于开始日期时仍然去查</b>：SQL 的 {@code BETWEEN start AND today}
 *       在 today 小于 start 时返回空，看似无害；但如果传的是 {@code start}，
 *       就会凭空多出一个「起始日」的点。</li>
 * </ol>
 * <p>这两条都只能用 {@code ArgumentCaptor} 去捕获真正传进 Mapper 的日期来钉住。</p>
 *
 * <p>Mockito 测试：不加载 Spring、不连 MySQL / Redis。</p>
 *
 * @author 张忠驰
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StatServiceImpl 统计与进度可视化")
class StatServiceImplTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long ME = 1L;

    @Mock
    private StatMapper statMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectAccessGuard accessGuard;
    @Mock
    private ProjectStatCache statCache;

    private StatServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleFourEntities();
        // 计算器与导出器用真实实现：它们的正确性由各自的测试覆盖，
        // 这里 mock 掉等于把「服务是否正确使用它们」也一起测没了
        service = new StatServiceImpl(statMapper, projectMapper, accessGuard, statCache,
                new BurndownCalculator(), new WorkloadBalanceCalculator(), new StatExcelWriter());
        UserContext.setUserId(ME);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Project project(LocalDate start, LocalDate end) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("星港计划");
        project.setStartDate(start);
        project.setPlanEndDate(end);
        return project;
    }

    private void givenProject(LocalDate start, LocalDate end) {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(start, end));
    }

    // ==================== 燃尽图 ====================

    @Test
    @DisplayName("实际线只查 [开始日, 今天]，绝不把计划结束日当成结束（未来的日子没有「实际」数据）")
    void burndownQueriesOnlyUpToToday() {
        LocalDate today = LocalDate.now();
        givenProject(today.minusDays(60), today.plusDays(60));
        when(statMapper.sumStoryPoint(PROJECT_ID)).thenReturn(100);
        when(statMapper.selectDailyRemainingPoints(eq(PROJECT_ID), any(), any()))
                .thenReturn(new ArrayList<>(List.of(
                        new BurndownPointVO(today.minusDays(10), 80),
                        new BurndownPointVO(today, 60))));

        BurndownVO vo = service.burndown(PROJECT_ID);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(statMapper).selectDailyRemainingPoints(eq(PROJECT_ID), from.capture(), to.capture());

        assertThat(from.getValue()).isEqualTo(today.minusDays(60));
        assertThat(to.getValue()).isEqualTo(today);
        assertThat(vo.getActualEndDate()).isEqualTo(today);
        assertThat(vo.getCurrentPoints()).isEqualTo(60);
        // 理想线仍然覆盖到计划结束日，两条线的横轴范围刻意不同
        List<BurndownPointVO> ideal = vo.getIdealLine();
        assertThat(ideal.get(ideal.size() - 1).getDate()).isEqualTo(today.plusDays(60));
    }

    @Test
    @DisplayName("实际线逐字采用回放结果：服务不按「当前状态」重算任何历史点位")
    void burndownNeverRecomputesHistoryFromCurrentState() {
        LocalDate today = LocalDate.now();
        givenProject(today.minusDays(30), today.plusDays(30));
        when(statMapper.sumStoryPoint(PROJECT_ID)).thenReturn(63);

        // 回放出来的曲线：第 -20 天完成一个（点位下台阶），但这一天的任务
        // 现在其实被拖回了 TODO。服务必须原样输出这条历史曲线——
        // 它没有任何「当前状态」的输入可用，也不允许去查。
        List<BurndownPointVO> replayed = new ArrayList<>(List.of(
                new BurndownPointVO(today.minusDays(25), 63),
                new BurndownPointVO(today.minusDays(20), 58),
                new BurndownPointVO(today.minusDays(15), 58),
                new BurndownPointVO(today, 58)));
        when(statMapper.selectDailyRemainingPoints(eq(PROJECT_ID), any(), any())).thenReturn(replayed);

        BurndownVO vo = service.burndown(PROJECT_ID);

        assertThat(vo.getActualLine()).containsExactlyElementsOf(replayed);
        assertThat(vo.getCurrentPoints()).isEqualTo(58);
        // 回退不会被「补回去」：越靠后的点位不低于历史低点
        assertThat(vo.getActualLine().get(3).getPoints())
                .isEqualTo(vo.getActualLine().get(1).getPoints());
    }

    @Test
    @DisplayName("今天早于计划开始日：不去查实际线，返回空的实际线与总故事点")
    void burndownBeforeStartSkipsQuery() {
        LocalDate today = LocalDate.now();
        givenProject(today.plusDays(30), today.plusDays(120));
        when(statMapper.sumStoryPoint(PROJECT_ID)).thenReturn(40);

        BurndownVO vo = service.burndown(PROJECT_ID);

        verify(statMapper, never()).selectDailyRemainingPoints(anyLong(), any(), any());
        assertThat(vo.getActualLine()).isEmpty();
        assertThat(vo.getCurrentPoints()).isEqualTo(40);
        assertThat(vo.getScheduleStatus()).isEqualTo(BurndownCalculator.ON_TRACK);
    }

    @Test
    @DisplayName("今天晚于计划结束日：实际线截到计划结束日，剩余未清零则判落后（不随拖延放宽）")
    void burndownAfterPlanEndClipsAndStaysBehind() {
        LocalDate today = LocalDate.now();
        LocalDate planEnd = today.minusDays(10);
        givenProject(today.minusDays(100), planEnd);
        when(statMapper.sumStoryPoint(PROJECT_ID)).thenReturn(50);
        when(statMapper.selectDailyRemainingPoints(eq(PROJECT_ID), any(), any()))
                .thenReturn(new ArrayList<>(List.of(
                        new BurndownPointVO(today.minusDays(100), 50),
                        new BurndownPointVO(planEnd, 20))));

        BurndownVO vo = service.burndown(PROJECT_ID);

        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(statMapper).selectDailyRemainingPoints(eq(PROJECT_ID), any(), to.capture());
        assertThat(to.getValue()).isEqualTo(planEnd);
        assertThat(vo.getActualEndDate()).isEqualTo(planEnd);
        assertThat(vo.getScheduleStatus()).isEqualTo(BurndownCalculator.BEHIND);
        assertThat(vo.getElapsedDays()).isEqualTo(vo.getTotalDays());
    }

    @Test
    @DisplayName("项目不存在（或已归档）→ 404，而不是返回一条空曲线")
    void burndownProjectMissing() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.burndown(PROJECT_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("项目不存在");
    }

    // ==================== 资产完成率 ====================

    @Test
    @DisplayName("完成率 = 已通过 / 总数；五种状态全部铺满（含 0 个的），占比之和为 100")
    void assetCompletionComputesRateAndFillsAllStatuses() {
        when(statMapper.selectAssetStatusCount(PROJECT_ID)).thenReturn(new ArrayList<>(List.of(
                count("APPROVED", 2), count("PENDING", 2),
                count("IN_PROGRESS", 2), count("REJECTED", 1), count("DEPRECATED", 2))));

        AssetCompletionVO vo = service.assetCompletion(PROJECT_ID);

        assertThat(vo.getAssetTotal()).isEqualTo(9);
        assertThat(vo.getAssetApproved()).isEqualTo(2);
        assertThat(vo.getAssetPending()).isEqualTo(2);
        assertThat(vo.getAssetRejected()).isEqualTo(1);
        assertThat(vo.getCompletionRate()).isEqualTo(22.2);
        assertThat(vo.getStatusDistribution()).hasSize(5);
        assertThat(vo.getStatusDistribution()).allSatisfy(item -> {
            assertThat(item.getLabel()).isNotBlank();
            assertThat(item.getColor()).startsWith("#");
        });
        double ratioSum = vo.getStatusDistribution().stream()
                .mapToDouble(StatCountVO::getRatio).sum();
        assertThat(ratioSum).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.5));
        verify(statCache).put(eq(PROJECT_ID), eq(RedisKeys.STAT_METRIC_ASSET_COMPLETION), any());
    }

    @Test
    @DisplayName("项目还没有资产：完成率 0.0、五种状态都是 0，不出现除零")
    void assetCompletionEmptyProject() {
        when(statMapper.selectAssetStatusCount(PROJECT_ID)).thenReturn(new ArrayList<>());

        AssetCompletionVO vo = service.assetCompletion(PROJECT_ID);

        assertThat(vo.getAssetTotal()).isZero();
        assertThat(vo.getCompletionRate()).isZero();
        assertThat(vo.getStatusDistribution()).hasSize(5);
        assertThat(vo.getStatusDistribution()).allSatisfy(item -> {
            assertThat(item.getCount()).isZero();
            assertThat(item.getRatio()).isZero();
        });
    }

    @Test
    @DisplayName("命中缓存时不再查库（缓存的意义就在这里）")
    void assetCompletionServedFromCache() {
        AssetCompletionVO cached = new AssetCompletionVO();
        cached.setAssetTotal(99);
        when(statCache.get(PROJECT_ID, RedisKeys.STAT_METRIC_ASSET_COMPLETION, AssetCompletionVO.class))
                .thenReturn(cached);

        AssetCompletionVO vo = service.assetCompletion(PROJECT_ID);

        assertThat(vo.getAssetTotal()).isEqualTo(99);
        verify(statMapper, never()).selectAssetStatusCount(anyLong());
    }

    // ==================== 分类分布 ====================

    @Test
    @DisplayName("分类完成率按分类各自计算；没有分类时返回空数组而不是 null")
    void assetByCategoryComputesPerCategoryRate() {
        AssetCategoryStatVO prop = new AssetCategoryStatVO();
        prop.setCategoryId(3L);
        prop.setCategoryName("场景道具");
        prop.setTotal(3);
        prop.setApproved(1);
        AssetCategoryStatVO empty = new AssetCategoryStatVO();
        empty.setCategoryId(6L);
        empty.setCategoryName("音效");
        empty.setTotal(0);
        empty.setApproved(0);
        when(statMapper.selectCategoryStat(PROJECT_ID)).thenReturn(new ArrayList<>(List.of(prop, empty)));

        List<AssetCategoryStatVO> rows = service.assetByCategory(PROJECT_ID);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getCompletionRate()).isEqualTo(33.3);
        assertThat(rows.get(1).getCompletionRate()).isZero();
        assertThat(rows.get(1).getColor()).as("没配颜色的分类要有兜底色").isNotBlank();
    }

    // ==================== 工作量 ====================

    @Test
    @DisplayName("工作量：均衡度由计算器算好并写入缓存，成员角色补中文名")
    void workloadSummarizesAndCaches() {
        WorkloadItemVO a = new WorkloadItemVO();
        a.setUserId(1L);
        a.setTaskTotal(10);
        a.setTaskDone(5);
        a.setRole("OWNER");
        WorkloadItemVO b = new WorkloadItemVO();
        b.setUserId(2L);
        b.setTaskTotal(0);
        b.setTaskDone(0);
        b.setRole("ARTIST");
        when(statMapper.selectWorkload(PROJECT_ID)).thenReturn(new ArrayList<>(List.of(a, b)));

        WorkloadVO vo = service.workload(PROJECT_ID);

        assertThat(vo.getBalanceIndex()).isZero();
        assertThat(vo.getBalanceWarning()).isTrue();
        assertThat(vo.getHint()).contains("工作量分配不均");
        assertThat(vo.getMembers()).extracting(WorkloadItemVO::getRoleLabel)
                .containsExactlyInAnyOrder("负责人", "美术");
        verify(statCache).put(eq(PROJECT_ID), eq(RedisKeys.STAT_METRIC_WORKLOAD), any());
    }

    // ==================== 趋势 ====================

    @Test
    @DisplayName("趋势图逐日铺满窗口，空日补 0（不是把没有活动的日子整段跳过）")
    void taskTrendFillsEmptyDays() {
        LocalDate today = LocalDate.now();
        // SQL 只返回有数据的几天
        when(statMapper.selectDailyCreated(eq(PROJECT_ID), any(), any()))
                .thenReturn(new ArrayList<>(List.of(
                        new TrendPointVO(today.minusDays(4), 2, 0),
                        new TrendPointVO(today, 1, 0))));
        when(statMapper.selectDailyCompleted(eq(PROJECT_ID), any(), any()))
                .thenReturn(new ArrayList<>(List.of(new TrendPointVO(today.minusDays(2), 0, 3))));

        TaskTrendVO vo = service.taskTrend(PROJECT_ID, 7);

        assertThat(vo.getDays()).isEqualTo(7);
        assertThat(vo.getStartDate()).isEqualTo(today.minusDays(6));
        assertThat(vo.getEndDate()).isEqualTo(today);
        assertThat(vo.getPoints()).hasSize(7);
        assertThat(vo.getPoints()).extracting(TrendPointVO::getDate)
                .containsExactly(today.minusDays(6), today.minusDays(5), today.minusDays(4),
                        today.minusDays(3), today.minusDays(2), today.minusDays(1), today);
        // 空日的两个计数都是 0（而不是 null）
        assertThat(vo.getPoints()).allSatisfy(point -> {
            assertThat(point.getCreated()).isNotNull();
            assertThat(point.getCompleted()).isNotNull();
        });
        assertThat(vo.getPoints().get(2).getCreated()).isEqualTo(2);
        assertThat(vo.getPoints().get(4).getCompleted()).isEqualTo(3);
        assertThat(vo.getTotalCreated()).isEqualTo(3);
        assertThat(vo.getTotalCompleted()).isEqualTo(3);
        assertThat(vo.getNetGrowth()).isZero();
    }

    @Test
    @DisplayName("不传 days 用默认 30；越界（1 / 999）直接 400，不静默夹紧")
    void taskTrendValidatesWindow() {
        when(statMapper.selectDailyCreated(eq(PROJECT_ID), any(), any())).thenReturn(new ArrayList<>());
        when(statMapper.selectDailyCompleted(eq(PROJECT_ID), any(), any())).thenReturn(new ArrayList<>());

        assertThat(service.taskTrend(PROJECT_ID, null).getDays()).isEqualTo(30);
        assertThat(service.taskTrend(PROJECT_ID, 180).getDays()).isEqualTo(180);
        assertThat(service.taskTrend(PROJECT_ID, 7).getPoints()).hasSize(7);

        assertThatThrownBy(() -> service.taskTrend(PROJECT_ID, 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("days 需在");
        assertThatThrownBy(() -> service.taskTrend(PROJECT_ID, 999))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("days 需在");
    }

    // ==================== 导出 ====================

    @Test
    @DisplayName("导出：文件名带项目名与日期、内容是非空 xlsx（PK 魔数）")
    void exportProducesXlsx() {
        LocalDate today = LocalDate.now();
        givenProject(today.minusDays(30), today.plusDays(60));
        when(statMapper.sumStoryPoint(PROJECT_ID)).thenReturn(10);
        when(statMapper.selectDailyRemainingPoints(eq(PROJECT_ID), any(), any())).thenReturn(new ArrayList<>());
        when(statMapper.selectAssetStatusCount(PROJECT_ID)).thenReturn(new ArrayList<>());
        when(statMapper.selectCategoryStat(PROJECT_ID)).thenReturn(new ArrayList<>());
        when(statMapper.selectWorkload(PROJECT_ID)).thenReturn(new ArrayList<>());
        when(statMapper.selectDailyCreated(eq(PROJECT_ID), any(), any())).thenReturn(new ArrayList<>());
        when(statMapper.selectDailyCompleted(eq(PROJECT_ID), any(), any())).thenReturn(new ArrayList<>());

        StatExportFile file = service.export(PROJECT_ID);

        assertThat(file.fileName()).startsWith("星港计划-统计报表-").endsWith(".xlsx");
        assertThat(file.content()).hasSizeGreaterThan(1000);
        assertThat(file.content()[0]).isEqualTo((byte) 'P');
        assertThat(file.content()[1]).isEqualTo((byte) 'K');
    }

    @Test
    @DisplayName("非法项目名不会让文件名带上路径分隔符")
    void exportSanitizesFileName() {
        LocalDate today = LocalDate.now();
        Project weird = project(today.minusDays(10), today.plusDays(10));
        weird.setName("星港/计划:2026");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(weird);
        when(statMapper.sumStoryPoint(PROJECT_ID)).thenReturn(0);
        when(statMapper.selectDailyRemainingPoints(eq(PROJECT_ID), any(), any())).thenReturn(new ArrayList<>());
        when(statMapper.selectAssetStatusCount(PROJECT_ID)).thenReturn(new ArrayList<>());
        when(statMapper.selectCategoryStat(PROJECT_ID)).thenReturn(new ArrayList<>());
        when(statMapper.selectWorkload(PROJECT_ID)).thenReturn(new ArrayList<>());
        when(statMapper.selectDailyCreated(eq(PROJECT_ID), any(), any())).thenReturn(new ArrayList<>());
        when(statMapper.selectDailyCompleted(eq(PROJECT_ID), any(), any())).thenReturn(new ArrayList<>());

        StatExportFile file = service.export(PROJECT_ID);

        assertThat(file.fileName()).doesNotContain("/").doesNotContain("\\").doesNotContain(":");
        assertThat(file.fileName()).contains("星港_计划_2026");
    }

    // ==================== 越权 ====================

    @Test
    @DisplayName("非项目成员：每个统计接口都被 ProjectAccessGuard 拦下（403），一次库都不查")
    void nonMemberIsForbidden() {
        when(accessGuard.requireActiveMember(anyLong(), anyLong()))
                .thenThrow(BizException.forbidden("你不是该项目成员，无权访问"));

        assertThatThrownBy(() -> service.assetCompletion(PROJECT_ID)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.assetByCategory(PROJECT_ID)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.burndown(PROJECT_ID)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.workload(PROJECT_ID)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.taskTrend(PROJECT_ID, 30)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.export(PROJECT_ID)).isInstanceOf(BizException.class);

        verify(statMapper, never()).selectWorkload(anyLong());
        verify(projectMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("未登录（没有用户上下文）→ 401，而不是把 null 当 userId 一路传到 SQL")
    void anonymousIsUnauthorized() {
        UserContext.clear();

        assertThatThrownBy(() -> service.assetCompletion(PROJECT_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未登录");
    }

    private StatCountVO count(String key, int value) {
        StatCountVO vo = new StatCountVO();
        vo.setKey(key);
        vo.setCount(value);
        return vo;
    }
}
