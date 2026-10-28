package com.demo.gameasset.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.gameasset.common.enums.NotificationType;
import com.demo.gameasset.common.enums.TaskColumn;
import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.dto.TaskAssignDTO;
import com.demo.gameasset.dto.TaskCreateDTO;
import com.demo.gameasset.dto.TaskMoveDTO;
import com.demo.gameasset.dto.TaskQueryDTO;
import com.demo.gameasset.dto.TaskUpdateDTO;
import com.demo.gameasset.entity.Asset;
import com.demo.gameasset.entity.ProjectMember;
import com.demo.gameasset.entity.Task;
import com.demo.gameasset.entity.TaskStatusLog;
import com.demo.gameasset.mapper.AssetMapper;
import com.demo.gameasset.mapper.ProjectMemberMapper;
import com.demo.gameasset.mapper.SysUserMapper;
import com.demo.gameasset.mapper.TaskMapper;
import com.demo.gameasset.mapper.TaskStatusLogMapper;
import com.demo.gameasset.service.support.ProjectStatCache;
import com.demo.gameasset.service.NotificationService;
import com.demo.gameasset.service.TaskCommentService;
import com.demo.gameasset.service.support.ProjectOverviewCache;
import com.demo.gameasset.service.support.TaskViewSupport;
import com.demo.gameasset.support.MybatisPlusTestSupport;
import com.demo.gameasset.vo.BoardVO;
import com.demo.gameasset.vo.TaskCardVO;
import com.demo.gameasset.vo.TaskMoveVO;
import com.demo.gameasset.vo.TaskVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务服务测试（模块3 的核心逻辑）。
 *
 * <p>覆盖文档 9 节点名的要点与本次模块3 的实质要求：看板一次四列、
 * 单资源查询带 {@code project_id}、拖拽落位交给算法、乐观锁冲突 409、
 * 换列写状态日志并通知创建人、拖入/拖出 DONE 时 {@code finished_at} 的写入与清空、
 * 逻辑删除保留评论与日志。</p>
 *
 * <p>全部 Mockito mock 掉 Mapper 与外部依赖，<b>不加载 Spring、
 * 不连 MySQL / Redis</b>。落位算法用<b>真实实现</b>（{@code TaskSortOrderCalculator}
 * 是纯静态类），这样「拖拽后排序值是否重复」测的是真正的算法，
 * 而不是我桩出来的结论。</p>
 *
 * @author 管理员
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TaskServiceImpl 任务看板与协作")
class TaskServiceImplTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long OTHER_PROJECT_ID = 99L;
    private static final Long TASK_ID = 305L;
    private static final Long ME = 1L;
    private static final Long ARTIST = 2L;
    private static final Long ASSET_ID = 128L;

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskStatusLogMapper taskStatusLogMapper;
    @Mock
    private TaskCommentService taskCommentService;
    @Mock
    private TaskViewSupport viewSupport;
    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private AssetMapper assetMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ProjectOverviewCache overviewCache;
    /**
     * 模块4 新增的统计缓存。{@code assign} 改了指派人之后要单独失效它
     * （概览的公共指标不变，所以走不到 overviewCache 那条通道）。
     */
    @Mock
    private ProjectStatCache statCache;

    private TaskServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleThreeEntities();
        service = new TaskServiceImpl(taskMapper, taskStatusLogMapper, taskCommentService,
                viewSupport, projectMemberMapper, sysUserMapper, assetMapper,
                notificationService, overviewCache, statCache);
        UserContext.setUserId(ME);
        UserContext.setUsername("demo");
        UserContext.setProjectId(PROJECT_ID);
        UserContext.setProjectRole("OWNER");

        // 装配层与评论层由各自的测试覆盖，这里只保留「被调用了什么」这一层关注
        when(viewSupport.toCards(any(), any())).thenReturn(new ArrayList<>());
        when(taskCommentService.list(any(), any())).thenReturn(new ArrayList<>());
        when(taskStatusLogMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        // 默认让「关联资产属于本项目」这一关通过；越权用例各自覆盖成 null
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setProjectId(PROJECT_ID);
        when(assetMapper.selectOne(any())).thenReturn(asset);
        // 真实环境下 MyBatis-Plus 会把自增主键回填进实体，mock 要还原这一步，
        // 否则 create() 里紧接着的 detail(task.getId(), ...) 会拿到 null
        when(taskMapper.insert(any(Task.class))).thenAnswer(invocation -> {
            invocation.<Task>getArgument(0).setId(TASK_ID);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Task task(Long id, String column, int sortOrder) {
        Task t = new Task();
        t.setId(id);
        t.setProjectId(PROJECT_ID);
        t.setTitle("气闸舱门接入交互提示");
        t.setColumnStatus(column);
        t.setPriority("P1");
        t.setTaskType("PROGRAM");
        t.setCreatorId(ME);
        t.setAssigneeId(ARTIST);
        t.setSortOrder(sortOrder);
        t.setVersion(3);
        return t;
    }

    private TaskCardVO card(Long id, String column, Integer storyPoint) {
        TaskCardVO c = new TaskCardVO();
        c.setId(id);
        c.setColumnStatus(column);
        c.setTitle("卡片" + id);
        c.setStoryPoint(storyPoint);
        return c;
    }

    private TaskCreateDTO createDto() {
        TaskCreateDTO dto = new TaskCreateDTO();
        dto.setTitle("  主角宇航服面数优化  ");
        dto.setPriority("P0");
        dto.setTaskType("ART");
        dto.setAssigneeId(ARTIST);
        dto.setDueDate(LocalDate.of(2026, 10, 5));
        dto.setAssetId(ASSET_ID);
        dto.setStoryPoint(5);
        return dto;
    }

    private TaskMoveDTO moveDto(String toColumn, Long prev, Long next, Integer version) {
        TaskMoveDTO dto = new TaskMoveDTO();
        dto.setToColumn(toColumn);
        dto.setPrevTaskId(prev);
        dto.setNextTaskId(next);
        dto.setVersion(version);
        return dto;
    }

    /**
     * 取乐观锁更新的 WHERE 片段。
     *
     * <p>注意：MyBatis-Plus 的 {@code LambdaUpdateWrapper#getTargetSql()} 只返回
     * <b>WHERE 部分</b>，SET 子句要用 {@link LambdaUpdateWrapper#getSqlSet()}——
     * 断言「更新了哪些列」时别用错方法，否则会得到一条看起来毫无问题的 WHERE
     * 而误以为断言通过。</p>
     */
    private LambdaUpdateWrapper<Task> captureGuardedUpdate() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Task>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(taskMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    // ==================================================================
    // 一、看板：一次请求返回四列
    // ==================================================================

    @Nested
    @DisplayName("看板：一次请求返回四列")
    class Board {

        @Test
        @DisplayName("四列恒定返回（空列也返回），并按列统计数量与故事点合计")
        void returnsFourColumnsAlways() {
            when(taskMapper.selectList(any())).thenReturn(List.of(
                    task(1L, "TODO", 1000), task(2L, "TODO", 2000), task(3L, "DOING", 1000)));
            when(viewSupport.toCards(any(), any())).thenReturn(List.of(
                    card(1L, "TODO", 5), card(2L, "TODO", null), card(3L, "DOING", 3)));

            BoardVO board = service.board(PROJECT_ID);

            assertThat(board.getColumns()).extracting("status")
                    .containsExactly("TODO", "DOING", "REVIEW", "DONE");
            assertThat(board.getTotalTasks()).isEqualTo(3);
            assertThat(board.getColumns().get(0).getTaskCount()).isEqualTo(2);
            assertThat(board.getColumns().get(0).getStoryPointTotal())
                    .as("没填故事点的任务按 0 计，不能让合计变成 null")
                    .isEqualTo(5);
            assertThat(board.getColumns().get(1).getStoryPointTotal()).isEqualTo(3);
            assertThat(board.getColumns().get(2).getTasks()).isEmpty();
            assertThat(board.getColumns().get(3).getTaskCount()).isZero();
        }

        @Test
        @DisplayName("看板查询带 project_id，并按列内顺序（sort_order, id）取回")
        void boardQueryIsProjectScopedAndOrdered() {
            when(taskMapper.selectList(any())).thenReturn(List.of());

            service.board(PROJECT_ID);

            ArgumentCaptor<Wrapper<Task>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(taskMapper).selectList(captor.capture());
            String sql = captor.getValue().getTargetSql();
            assertThat(sql).contains("project_id").contains("sort_order").contains("ORDER BY");
        }

        @Test
        @DisplayName("库里的列名非法（脏数据）时归入待办列，而不是让卡片在看板上彻底消失")
        void unknownColumnFallsBackToTodo() {
            when(taskMapper.selectList(any())).thenReturn(List.of(task(1L, "TODO", 1000)));
            when(viewSupport.toCards(any(), any())).thenReturn(List.of(card(9L, "ARCHIVED", 1)));

            BoardVO board = service.board(PROJECT_ID);

            assertThat(board.getColumns().get(0).getTasks()).extracting("id").containsExactly(9L);
            assertThat(board.getTotalTasks()).isEqualTo(1);
        }
    }

    // ==================================================================
    // 二、创建
    // ==================================================================

    @Nested
    @DisplayName("创建任务")
    class Create {

        @Test
        @DisplayName("追加到列尾：新排序值 = 当前列最大排序值 + 一个步长")
        void appendsToColumnTail() {
            when(taskMapper.selectOne(any())).thenReturn(task(9L, "TODO", 3000),
                    task(TASK_ID, "TODO", 4000));
            when(taskMapper.selectList(any())).thenReturn(List.of());

            service.create(PROJECT_ID, createDto());

            ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
            verify(taskMapper).insert(captor.capture());
            Task saved = captor.getValue();
            assertThat(saved.getSortOrder()).isEqualTo(4000);
            assertThat(saved.getTitle()).as("标题两侧空白要去掉").isEqualTo("主角宇航服面数优化");
            assertThat(saved.getColumnStatus()).isEqualTo("TODO");
            assertThat(saved.getCreatorId()).isEqualTo(ME);
            assertThat(saved.getVersion()).isNull();
        }

        @Test
        @DisplayName("新建即落一条状态日志（from 为空），模块4 回放历史时才有起点")
        void writesInitialStatusLog() {
            when(taskMapper.selectOne(any())).thenReturn(null, task(TASK_ID, "TODO", 1000));
            when(taskMapper.selectList(any())).thenReturn(List.of());

            service.create(PROJECT_ID, createDto());

            ArgumentCaptor<TaskStatusLog> captor = ArgumentCaptor.forClass(TaskStatusLog.class);
            verify(taskStatusLogMapper).insert(captor.capture());
            assertThat(captor.getValue().getFromStatus()).isNull();
            assertThat(captor.getValue().getToStatus()).isEqualTo("TODO");
            assertThat(captor.getValue().getProjectId()).isEqualTo(PROJECT_ID);
        }

        @Test
        @DisplayName("指派了负责人就推 TASK_ASSIGNED；概览缓存同步失效")
        void notifiesAssigneeAndEvictsOverview() {
            when(taskMapper.selectOne(any())).thenReturn(null, task(TASK_ID, "TODO", 1000));
            when(taskMapper.selectList(any())).thenReturn(List.of());

            service.create(PROJECT_ID, createDto());

            verify(notificationService).push(eq(NotificationType.TASK_ASSIGNED), eq(PROJECT_ID), eq(ARTIST),
                    any(), any(), eq(TASK_ID));
            verify(overviewCache).evict(PROJECT_ID);
        }

        @Test
        @DisplayName("直接建到「已完成」时补上完成时间，否则燃尽图会把它算作未完成")
        void creatingIntoDoneSetsFinishedAt() {
            TaskCreateDTO dto = createDto();
            dto.setColumnStatus("done");
            when(taskMapper.selectOne(any())).thenReturn(null, task(TASK_ID, "DONE", 1000));
            when(taskMapper.selectList(any())).thenReturn(List.of());

            service.create(PROJECT_ID, dto);

            ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
            verify(taskMapper).insert(captor.capture());
            assertThat(captor.getValue().getColumnStatus()).isEqualTo("DONE");
            assertThat(captor.getValue().getFinishedAt()).isNotNull();
        }

        @Test
        @DisplayName("非法的看板列 / 优先级 / 任务类型 → 400，且不落库")
        void rejectsInvalidEnums() {
            TaskCreateDTO badColumn = createDto();
            badColumn.setColumnStatus("ARCHIVED");
            assertBadRequest(() -> service.create(PROJECT_ID, badColumn));

            TaskCreateDTO badPriority = createDto();
            badPriority.setPriority("P9");
            assertBadRequest(() -> service.create(PROJECT_ID, badPriority));

            TaskCreateDTO badType = createDto();
            badType.setTaskType("MUSIC");
            assertBadRequest(() -> service.create(PROJECT_ID, badType));

            verify(taskMapper, never()).insert(any(Task.class));
        }

        @Test
        @DisplayName("指派对象不是项目 ACTIVE 成员 → 400（否则任务会烂在别人看不到的看板上）")
        void rejectsNonMemberAssignee() {
            when(projectMemberMapper.selectCount(any())).thenReturn(0L);
            when(taskMapper.selectOne(any())).thenReturn(null).thenReturn(null);

            assertBadRequest(() -> service.create(PROJECT_ID, createDto()));
            verify(taskMapper, never()).insert(any(Task.class));
        }

        @Test
        @DisplayName("关联资产不属于本项目 → 400")
        void rejectsForeignAsset() {
            when(assetMapper.selectOne(any())).thenReturn(null);

            assertBadRequest(() -> service.create(PROJECT_ID, createDto()));
            verify(taskMapper, never()).insert(any(Task.class));
        }
    }

    // ==================================================================
    // 三、拖拽：乐观锁与落位
    // ==================================================================

    @Nested
    @DisplayName("拖拽移动")
    class Move {

        @Test
        @DisplayName("版本过期（更新影响 0 行）→ 409，且不写状态日志、不发通知")
        void staleVersionConflicts() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "TODO", 1000));
            when(taskMapper.selectList(any())).thenReturn(List.of(task(9L, "DOING", 1000)));
            when(taskMapper.update(isNull(), any())).thenReturn(0);

            assertThatThrownBy(() -> service.move(TASK_ID, PROJECT_ID,
                    moveDto("DOING", null, 9L, 3)))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(409));

            verify(taskStatusLogMapper, never()).insert(any(TaskStatusLog.class));
            verify(notificationService, never()).push(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("乐观锁条件写在 SQL 里：version = 客户端传的值，并把它 +1")
        void versionConditionIsInTheWhereClause() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "TODO", 1000));
            when(taskMapper.selectList(any())).thenReturn(List.of());
            when(taskMapper.update(isNull(), any())).thenReturn(1);

            service.move(TASK_ID, PROJECT_ID, moveDto("DOING", null, null, 3));

            String sql = captureGuardedUpdate().getTargetSql();
            assertThat(sql).contains("project_id").contains("version").contains("id");
        }

        @Test
        @DisplayName("换列：写状态变更日志，并通知任务创建人")
        void crossColumnWritesLogAndNotifiesCreator() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "TODO", 1000));
            when(taskMapper.selectList(any())).thenReturn(List.of());
            when(taskMapper.update(isNull(), any())).thenReturn(1);

            service.move(TASK_ID, PROJECT_ID, moveDto("DOING", null, null, 3));

            ArgumentCaptor<TaskStatusLog> captor = ArgumentCaptor.forClass(TaskStatusLog.class);
            verify(taskStatusLogMapper).insert(captor.capture());
            assertThat(captor.getValue().getFromStatus()).isEqualTo("TODO");
            assertThat(captor.getValue().getToStatus()).isEqualTo("DOING");
            assertThat(captor.getValue().getOperatorId()).isEqualTo(ME);

            verify(notificationService).push(eq(NotificationType.TASK_STATUS), eq(PROJECT_ID), eq(ME),
                    any(), any(), eq(TASK_ID));
            verify(overviewCache).evict(PROJECT_ID);
        }

        @Test
        @DisplayName("列内重排（列没变）：不写状态日志、不发通知，避免拖动一下推一条通知")
        void sameColumnReorderIsSilent() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "DOING", 1000));
            when(taskMapper.selectList(any())).thenReturn(List.of(task(9L, "DOING", 2000)));
            when(taskMapper.update(isNull(), any())).thenReturn(1);

            service.move(TASK_ID, PROJECT_ID, moveDto("DOING", 9L, null, 3));

            verify(taskStatusLogMapper, never()).insert(any(TaskStatusLog.class));
            verify(notificationService, never()).push(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("拖入 DONE 写入完成时间；拖出 DONE 必须把它清空（否则燃尽图会算错）")
        void maintainsFinishedAtBothWays() {
            when(taskMapper.selectList(any())).thenReturn(List.of());
            when(taskMapper.update(isNull(), any())).thenReturn(1);

            // 拖入 DONE：完成时间被写入
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "REVIEW", 1000));
            TaskMoveVO intoDone = service.move(TASK_ID, PROJECT_ID, moveDto("DONE", null, null, 3));
            assertThat(intoDone.getFinishedAt()).isNotNull();

            // 拖出 DONE：set 子句里必须显式带上 finished_at = null
            Task done = task(TASK_ID, "DONE", 1000);
            done.setFinishedAt(java.time.LocalDateTime.of(2026, 9, 22, 10, 30));
            when(taskMapper.selectOne(any())).thenReturn(done);
            TaskMoveVO outOfDone = service.move(TASK_ID, PROJECT_ID, moveDto("DOING", null, null, 3));
            assertThat(outOfDone.getFinishedAt()).isNull();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<LambdaUpdateWrapper<Task>> captor =
                    ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(taskMapper, org.mockito.Mockito.times(2)).update(isNull(), captor.capture());
            List<LambdaUpdateWrapper<Task>> wrappers = captor.getAllValues();
            assertThat(wrappers.get(0).getSqlSet())
                    .as("拖入 DONE 要写入完成时间")
                    .contains("finished_at");
            assertThat(wrappers.get(1).getSqlSet())
                    .as("拖出 DONE 要在 SET 子句里带上 finished_at（置空），不能整列略过")
                    .contains("finished_at");
            assertThat(wrappers.get(1).getParamNameValuePairs().values())
                    .as("finished_at 要显式置空，不能因为「字段为 null 就不更新」而留在原值")
                    .containsNull();
        }

        @Test
        @DisplayName("响应返回服务端算出的 sortOrder 与新的 version（不回版本号，下一次拖拽必然 409）")
        void returnsServerSortOrderAndNextVersion() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "TODO", 1000));
            when(taskMapper.selectList(any())).thenReturn(List.of(task(9L, "DOING", 3000)));
            when(taskMapper.update(isNull(), any())).thenReturn(1);

            TaskMoveVO vo = service.move(TASK_ID, PROJECT_ID, moveDto("DOING", null, 9L, 3));

            assertThat(vo.getSortOrder()).isEqualTo(2000);
            assertThat(vo.getVersion()).isEqualTo(4);
            assertThat(vo.getColumnStatus()).isEqualTo("DOING");
            assertThat(vo.getColumnLabel()).isEqualTo(TaskColumn.DOING.getLabel());
        }

        @Test
        @DisplayName("prev/next 指向已被移走的卡片时不报错，按当前列内相邻关系落位")
        void toleratesStaleAnchors() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "TODO", 1000));
            when(taskMapper.selectList(any())).thenReturn(List.of(task(9L, "DOING", 3000)));
            when(taskMapper.update(isNull(), any())).thenReturn(1);

            TaskMoveVO vo = service.move(TASK_ID, PROJECT_ID, moveDto("DOING", 777L, 888L, 3));

            assertThat(vo.getSortOrder()).isEqualTo(4000);
            assertThat(vo.getRebalanced()).isFalse();
        }

        @Test
        @DisplayName("整列重排时逐条更新其他卡片的排序值，且每条 SQL 都带 project_id")
        void rebalanceUpdatesOtherCards() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "DOING", 500));
            when(taskMapper.selectList(any())).thenReturn(List.of(
                    task(9L, "DOING", 1000), task(10L, "DOING", 1005)));
            when(taskMapper.update(isNull(), any())).thenReturn(1);

            TaskMoveVO vo = service.move(TASK_ID, PROJECT_ID, moveDto("DOING", 9L, 10L, 3));

            assertThat(vo.getRebalanced()).isTrue();
            assertThat(vo.getReorderedCount()).isEqualTo(2);
            verify(taskMapper).updateSortOrder(9L, PROJECT_ID, 1000);
            verify(taskMapper).updateSortOrder(10L, PROJECT_ID, 3000);
        }

        @Test
        @DisplayName("非法的目标列 → 400，不产生任何写入")
        void rejectsInvalidColumn() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "TODO", 1000));

            assertBadRequest(() -> service.move(TASK_ID, PROJECT_ID, moveDto("ARCHIVED", null, null, 3)));
            verify(taskMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("跨项目操作：任务按 task_id + project_id 查，查不到直接 404")
        void scopedToProject() {
            when(taskMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> service.move(TASK_ID, OTHER_PROJECT_ID, moveDto("DOING", null, null, 3)))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(404));

            ArgumentCaptor<Wrapper<Task>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(taskMapper).selectOne(captor.capture());
            assertThat(captor.getValue().getTargetSql()).contains("project_id");
        }
    }

    // ==================================================================
    // 四、更新 / 指派 / 删除
    // ==================================================================

    @Nested
    @DisplayName("更新、指派与删除")
    class Maintenance {

        private TaskUpdateDTO updateDto(Long assigneeId) {
            TaskUpdateDTO dto = new TaskUpdateDTO();
            dto.setTitle("主角宇航服面数优化");
            dto.setPriority("P0");
            dto.setTaskType("ART");
            dto.setAssigneeId(assigneeId);
            dto.setStoryPoint(8);
            return dto;
        }

        @Test
        @DisplayName("更新：版本对不上 → 409")
        void updateConflictsOnStaleVersion() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "TODO", 1000));
            when(taskMapper.update(isNull(), any())).thenReturn(0);

            assertThatThrownBy(() -> service.update(TASK_ID, PROJECT_ID, updateDto(ARTIST)))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(409));
        }

        @Test
        @DisplayName("更新：负责人变了才推 TASK_ASSIGNED")
        void updateNotifiesOnlyWhenAssigneeChanges() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "TODO", 1000));
            when(taskMapper.update(isNull(), any())).thenReturn(1);

            service.update(TASK_ID, PROJECT_ID, updateDto(ARTIST));
            verify(notificationService, never()).push(any(), any(), any(), any(), any(), any(), any());

            service.update(TASK_ID, PROJECT_ID, updateDto(ME));
            verify(notificationService).push(eq(NotificationType.TASK_ASSIGNED), eq(PROJECT_ID), eq(ME),
                    any(), any(), eq(TASK_ID));
        }

        @Test
        @DisplayName("指派：取消指派（assigneeId 为空）不推通知，但确实写库")
        void assignNullCancelsWithoutNotification() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "TODO", 1000));
            when(taskMapper.update(isNull(), any())).thenReturn(1);
            TaskAssignDTO dto = new TaskAssignDTO();
            dto.setAssigneeId(null);

            service.assign(TASK_ID, PROJECT_ID, dto);

            verify(notificationService, never()).push(any(), any(), any(), any(), any(), any(), any());
            assertThat(captureGuardedUpdate().getSqlSet()).contains("assignee_id");
        }

        @Test
        @DisplayName("指派给非成员 → 400，且不写库")
        void assignRejectsNonMember() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "TODO", 1000));
            when(projectMemberMapper.selectCount(any())).thenReturn(0L);

            TaskAssignDTO dto = new TaskAssignDTO();
            dto.setAssigneeId(777L);
            assertBadRequest(() -> service.assign(TASK_ID, PROJECT_ID, dto));
            verify(taskMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("删除：逻辑删除（deleteById），评论与状态日志一律不动")
        void deleteKeepsHistory() {
            when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "TODO", 1000));

            service.delete(TASK_ID, PROJECT_ID);

            verify(taskMapper).deleteById(TASK_ID);
            verify(taskCommentService, never()).delete(any(), any());
            verify(taskStatusLogMapper, never()).deleteById(any(Long.class));
            verify(overviewCache).evict(PROJECT_ID);
        }

        @Test
        @DisplayName("相关任务：按 project_id + asset_id 查，供资产详情页展示")
        void relatedTasksScopedToProjectAndAsset() {
            when(taskMapper.selectList(any())).thenReturn(List.of());

            service.relatedTasks(PROJECT_ID, ASSET_ID);

            ArgumentCaptor<Wrapper<Task>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(taskMapper).selectList(captor.capture());
            assertThat(captor.getValue().getTargetSql())
                    .contains("project_id").contains("asset_id");
        }

        @Test
        @DisplayName("相关任务：资产ID为空时返回空列表，不查库")
        void relatedTasksWithoutAssetSkipsQuery() {
            assertThat(service.relatedTasks(PROJECT_ID, null)).isEmpty();
            verify(taskMapper, never()).selectList(any());
        }
    }

    // ==================================================================
    // 五、列表筛选
    // ==================================================================

    @Nested
    @DisplayName("任务列表筛选")
    class PageQuery {

        @Test
        @DisplayName("列表：project_id 是第一个条件，逾期筛选同时限定 due_date 与「未完成」")
        void pageScopesAndFiltersOverdue() {
            when(taskMapper.selectPage(any(), any())).thenReturn(new Page<>());
            TaskQueryDTO query = new TaskQueryDTO();
            query.setOverdueOnly(true);
            query.setAssigneeId(ARTIST);

            service.page(PROJECT_ID, query);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<LambdaQueryWrapper<Task>> captor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(taskMapper).selectPage(any(), captor.capture());
            String sql = captor.getValue().getTargetSql();
            assertThat(sql).contains("project_id").contains("assignee_id");
            assertThat(sql).contains("due_date").contains("column_status");
        }

        @Test
        @DisplayName("列表：只看未完成时不带 due_date 条件，仍然排除 DONE")
        void unfinishedFilterExcludesDone() {
            when(taskMapper.selectPage(any(), any())).thenReturn(new Page<>());
            TaskQueryDTO query = new TaskQueryDTO();
            query.setUnfinishedOnly(true);

            service.page(PROJECT_ID, query);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<LambdaQueryWrapper<Task>> captor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(taskMapper).selectPage(any(), captor.capture());
            String sql = captor.getValue().getTargetSql();
            assertThat(sql).contains("column_status").doesNotContain("due_date");
        }
    }

    // ==================================================================
    // 六、详情
    // ==================================================================

    @Test
    @DisplayName("详情：带上描述、评论与状态日志，并复用卡片字段")
    void detailCarriesCommentsAndLogs() {
        when(taskMapper.selectOne(any())).thenReturn(task(TASK_ID, "DOING", 1000));
        TaskCardVO c = card(TASK_ID, "DOING", 5);
        c.setAssigneeName("李阳");
        when(viewSupport.toCard(any(), any())).thenReturn(c);

        TaskVO vo = service.detail(TASK_ID, PROJECT_ID);

        assertThat(vo.getId()).isEqualTo(TASK_ID);
        assertThat(vo.getAssigneeName()).isEqualTo("李阳");
        assertThat(vo.getDescription()).isNull();
        assertThat(vo.getComments()).isEmpty();
        assertThat(vo.getStatusLogs()).isEmpty();
        verify(taskCommentService).list(TASK_ID, PROJECT_ID);
        verify(taskStatusLogMapper).selectList(any());
    }

    private void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(400));
    }
}
