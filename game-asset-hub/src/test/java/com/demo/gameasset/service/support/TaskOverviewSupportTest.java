package com.demo.gameasset.service.support;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.demo.gameasset.common.enums.TaskColumn;
import com.demo.gameasset.entity.Task;
import com.demo.gameasset.mapper.TaskMapper;
import com.demo.gameasset.support.MybatisPlusTestSupport;
import com.demo.gameasset.vo.ProjectOverviewVO;
import com.demo.gameasset.vo.TaskCardVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 概览里的任务指标与「我的待办」测试（模块3 对模块1 的交付）。
 *
 * <p>最要紧的一条是 {@code withMyTasks} 的<b>副本语义</b>：概览按项目维度缓存，
 * 若把「我的待办」原地写进那份 VO，A 的待办就会被缓存给 B 看。
 * 这里断言「base 不被修改」且「返回的是另一个对象」。</p>
 *
 * <p>纯 Mockito 测试：不加载 Spring、不连 MySQL。</p>
 *
 * @author 管理员
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TaskOverviewSupport 概览任务指标与我的待办")
class TaskOverviewSupportTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long ME = 1L;

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskViewSupport viewSupport;

    private TaskOverviewSupport support;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleThreeEntities();
        support = new TaskOverviewSupport(taskMapper, viewSupport);
    }

    private String capturedSql() {
        ArgumentCaptor<Wrapper<Task>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).selectCount(captor.capture());
        return captor.getValue().getTargetSql();
    }

    // ==================== 项目维度指标 ====================

    @Test
    @DisplayName("fill：任务总数 / 已完成 / 已逾期三个指标都从库里算，且查询带 project_id")
    void fillComputesMetrics() {
        when(taskMapper.selectCount(any())).thenReturn(62L, 29L, 5L);
        ProjectOverviewVO vo = new ProjectOverviewVO();

        support.fill(vo, PROJECT_ID);

        assertThat(vo.getTaskTotal()).isEqualTo(62);
        assertThat(vo.getTaskDone()).isEqualTo(29);
        assertThat(vo.getTaskOverdue()).isEqualTo(5);

        ArgumentCaptor<Wrapper<Task>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper, org.mockito.Mockito.times(3)).selectCount(captor.capture());
        assertThat(captor.getAllValues().stream().map(Wrapper::getTargetSql).toList())
                .allSatisfy(sql -> assertThat(sql).contains("project_id"));
    }

    @Test
    @DisplayName("fill：完成数只看 DONE 列；逾期数同时限定截止日期与「未完成」")
    void fillUsesColumnAndDueDateConditions() {
        when(taskMapper.selectCount(any())).thenReturn(1L, 2L, 3L);

        support.fill(new ProjectOverviewVO(), PROJECT_ID);

        // 三次 selectCount 依次是：总数 / 完成数 / 逾期数
        ArgumentCaptor<Wrapper<Task>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper, org.mockito.Mockito.times(3)).selectCount(captor.capture());
        List<String> sqls = captor.getAllValues().stream().map(Wrapper::getTargetSql).toList();

        assertThat(sqls.get(0)).as("任务总数不加列条件").doesNotContain("column_status");
        assertThat(sqls.get(1)).as("完成数只数 DONE").contains("column_status");
        assertThat(sqls.get(2))
                .as("逾期数必须同时限定 due_date 与「未完成」，否则已交付的旧任务会永远算逾期")
                .contains("due_date").contains("column_status");
    }

    @Test
    @DisplayName("fill：入参为空时不查库也不抛异常")
    void fillToleratesNulls() {
        support.fill(null, PROJECT_ID);
        support.fill(new ProjectOverviewVO(), null);

        verify(taskMapper, never()).selectCount(any());
    }

    // ==================== 我的待办：副本语义 ====================

    @Test
    @DisplayName("withMyTasks：返回副本，原对象（可能就是缓存里那一份）不被写入 myTasks")
    void withMyTasksReturnsACopy() {
        ProjectOverviewVO base = new ProjectOverviewVO();
        base.setProjectId(PROJECT_ID);
        base.setTaskTotal(62);
        TaskCardVO mine = new TaskCardVO();
        mine.setId(501L);
        when(taskMapper.selectList(any())).thenReturn(List.of(new Task()));
        when(viewSupport.toCards(eq(PROJECT_ID), any())).thenReturn(List.of(mine));

        ProjectOverviewVO result = support.withMyTasks(base, PROJECT_ID, ME);

        assertThat(result).isNotSameAs(base);
        assertThat(result.getMyTasks()).containsExactly(mine);
        assertThat(base.getMyTasks()).as("base 会被写进 Redis 缓存，绝不能带上用户维度数据").isEmpty();
        assertThat(result.getTaskTotal()).as("副本要带上原有的项目维度指标").isEqualTo(62);
    }

    @Test
    @DisplayName("withMyTasks：没有登录用户时原样返回，不查库（概览不依赖登录上下文）")
    void withMyTasksWithoutUserReturnsBase() {
        ProjectOverviewVO base = new ProjectOverviewVO();

        assertThat(support.withMyTasks(base, PROJECT_ID, null)).isSameAs(base);
        verify(taskMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("我的待办：只查指派给我且未完成的，查询带 project_id 与 assignee_id，最多 5 条")
    void myOpenTasksScopesToUserAndLimit() {
        when(taskMapper.selectList(any())).thenReturn(List.of(new Task()));
        when(viewSupport.toCards(eq(PROJECT_ID), any())).thenReturn(List.of());

        support.myOpenTasks(PROJECT_ID, ME);

        ArgumentCaptor<Wrapper<Task>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).selectList(captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertThat(sql).contains("project_id").contains("assignee_id").contains("column_status");
        assertThat(sql).as("条数上限写在 SQL 里，而不是查回来再截断").contains("LIMIT " + TaskOverviewSupport.MY_TASK_LIMIT);
        assertThat(sql).as("截止日期近的排前面，没填日期的排最后").contains("due_date");
        assertThat(TaskColumn.FINISHED.name()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("单个指标查询：countByColumn 只统计指定列")
    void countByColumnScopesToColumn() {
        when(taskMapper.selectCount(any())).thenReturn(7L);

        assertThat(support.countByColumn(PROJECT_ID, TaskColumn.REVIEW)).isEqualTo(7);
        assertThat(capturedSql()).contains("project_id").contains("column_status");
    }
}
