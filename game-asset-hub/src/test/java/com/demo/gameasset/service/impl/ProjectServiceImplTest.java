package com.demo.gameasset.service.impl;

import com.demo.gameasset.common.enums.ProjectMemberStatus;
import com.demo.gameasset.common.enums.ProjectRole;
import com.demo.gameasset.common.enums.ProjectStatus;
import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.dto.ProjectCreateDTO;
import com.demo.gameasset.dto.ProjectUpdateDTO;
import com.demo.gameasset.entity.Project;
import com.demo.gameasset.entity.ProjectMember;
import com.demo.gameasset.mapper.ProjectMapper;
import com.demo.gameasset.mapper.ProjectMemberMapper;
import com.demo.gameasset.service.support.ProjectAccessGuard;
import com.demo.gameasset.service.support.ProjectOverviewCache;
import com.demo.gameasset.service.support.TaskOverviewSupport;
import com.demo.gameasset.support.MybatisPlusTestSupport;
import com.demo.gameasset.vo.ProjectOverviewVO;
import com.demo.gameasset.vo.ProjectVO;
import com.demo.gameasset.vo.TaskCardVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目服务测试：创建者自动成为 OWNER、概览缓存读写与主动失效。
 *
 * <p>Mockito mock 掉 Mapper 与缓存，不加载 Spring、不连 MySQL / Redis。</p>
 *
 * @author 管理员
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProjectServiceImpl 项目管理")
class ProjectServiceImplTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long USER_ID = 1L;

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @Mock
    private ProjectOverviewCache overviewCache;
    /** 模块3 接入：项目维度的任务指标 + 用户维度的「我的待办」（由 TaskOverviewSupportTest 覆盖） */
    @Mock
    private TaskOverviewSupport taskOverviewSupport;

    private ProjectServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleOneEntities();
        ProjectAccessGuard accessGuard = new ProjectAccessGuard(projectMemberMapper);
        service = new ProjectServiceImpl(projectMapper, projectMemberMapper, accessGuard,
                overviewCache, taskOverviewSupport);
        // 默认让 withMyTasks 原样返回：本类的用例关心的是「概览怎么算、什么时候进缓存」，
        // 「我的待办怎么查」由 TaskOverviewSupport 自己的测试负责
        Mockito.lenient().when(taskOverviewSupport.withMyTasks(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UserContext.setUserId(USER_ID);
        UserContext.setUsername("demo");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Project project(String status, Integer memberCount, LocalDate planEndDate) {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setName("星港计划");
        p.setOwnerId(USER_ID);
        p.setStatus(status);
        p.setMemberCount(memberCount);
        p.setPlanEndDate(planEndDate);
        return p;
    }

    private ProjectMember member(Long userId, String role, String status) {
        ProjectMember m = new ProjectMember();
        m.setId(userId);
        m.setProjectId(PROJECT_ID);
        m.setUserId(userId);
        m.setRole(role);
        m.setStatus(status);
        return m;
    }

    // ==================== 创建项目 ====================

    @Test
    @DisplayName("创建项目：创建者自动成为 OWNER，同事务写入一条 ACTIVE 的 ONWER 成员记录")
    void create_makesCreatorTheOwner() {
        // 模拟数据库回填自增主键
        Mockito.doAnswer(invocation -> {
            Project inserted = invocation.getArgument(0);
            inserted.setId(PROJECT_ID);
            return 1;
        }).when(projectMapper).insert(any(Project.class));

        ProjectCreateDTO dto = new ProjectCreateDTO();
        dto.setName("星港计划");
        dto.setCode("XG");
        dto.setEngine("UE5");
        dto.setStartDate(LocalDate.of(2026, 9, 1));
        dto.setPlanEndDate(LocalDate.of(2026, 12, 31));

        ProjectVO vo = service.create(dto);

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).insert(projectCaptor.capture());
        Project savedProject = projectCaptor.getValue();
        assertThat(savedProject.getOwnerId()).isEqualTo(USER_ID);
        assertThat(savedProject.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(savedProject.getMemberCount()).as("创建者自己先算一个成员").isEqualTo(1);

        ArgumentCaptor<ProjectMember> memberCaptor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberMapper).insert(memberCaptor.capture());
        ProjectMember owner = memberCaptor.getValue();
        assertThat(owner.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(owner.getUserId()).isEqualTo(USER_ID);
        assertThat(owner.getRole()).isEqualTo(ProjectRole.OWNER);
        assertThat(owner.getStatus()).isEqualTo(ProjectMemberStatus.ACTIVE);
        assertThat(owner.getJoinTime()).as("创建即加入，必须写 join_time").isNotNull();

        assertThat(vo.getMyRole()).isEqualTo(ProjectRole.OWNER);
        assertThat(vo.getCanManage()).isTrue();
        assertThat(vo.getStatusLabel()).isEqualTo("进行中");

        verify(overviewCache).evict(PROJECT_ID);
    }

    @Test
    @DisplayName("创建项目：计划结束日期早于开始日期 → 400，且不落库")
    void create_rejectsInvalidDateRange() {
        ProjectCreateDTO dto = new ProjectCreateDTO();
        dto.setName("星港计划");
        dto.setStartDate(LocalDate.of(2026, 12, 31));
        dto.setPlanEndDate(LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("计划结束日期不能早于项目开始日期");

        verify(projectMapper, never()).insert(any(Project.class));
        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
    }

    // ==================== 状态变更 ====================

    @Test
    @DisplayName("状态变更：非法状态值 → 400")
    void changeStatus_rejectsInvalidStatus() {
        assertThatThrownBy(() -> service.changeStatus(PROJECT_ID, "DELETED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法的项目状态");
        verify(projectMapper, never()).updateById(any(Project.class));
    }

    @Test
    @DisplayName("状态变更：非 OWNER → 403")
    void changeStatus_requiresOwner() {
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(USER_ID, ProjectRole.ARTIST, ProjectMemberStatus.ACTIVE));

        assertThatThrownBy(() -> service.changeStatus(PROJECT_ID, ProjectStatus.PAUSED))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("状态变更：OWNER 修改成功后失效概览缓存")
    void changeStatus_byOwnerEvictsOverviewCache() {
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(USER_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE));
        when(projectMapper.selectById(PROJECT_ID))
                .thenReturn(project(ProjectStatus.ACTIVE, 3, null));

        ProjectVO vo = service.changeStatus(PROJECT_ID, ProjectStatus.PAUSED);

        assertThat(vo.getStatus()).isEqualTo(ProjectStatus.PAUSED);
        assertThat(vo.getStatusLabel()).isEqualTo("已暂停");
        verify(projectMapper).updateById(any(Project.class));
        verify(overviewCache).evict(PROJECT_ID);
    }

    @Test
    @DisplayName("状态变更：状态未变化时幂等，不写库也不失效缓存")
    void changeStatus_isIdempotentWhenUnchanged() {
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(USER_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE));
        when(projectMapper.selectById(PROJECT_ID))
                .thenReturn(project(ProjectStatus.ACTIVE, 3, null));

        service.changeStatus(PROJECT_ID, ProjectStatus.ACTIVE);

        verify(projectMapper, never()).updateById(any(Project.class));
        verify(overviewCache, never()).evict(any());
    }

    // ==================== 归档 ====================

    @Test
    @DisplayName("归档项目：OWNER 执行，走逻辑删除并失效缓存")
    void archive_byOwnerUsesLogicalDelete() {
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(USER_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE));
        when(projectMapper.selectById(PROJECT_ID))
                .thenReturn(project(ProjectStatus.ACTIVE, 3, null));

        service.archive(PROJECT_ID);

        // deleteById 在 @TableLogic 下实际是 UPDATE ... SET deleted = 1
        verify(projectMapper).deleteById(PROJECT_ID);
        verify(overviewCache).evict(PROJECT_ID);
    }

    @Test
    @DisplayName("归档项目：非 OWNER → 403，不发生删除")
    void archive_requiresOwner() {
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(USER_ID, ProjectRole.DESIGNER, ProjectMemberStatus.ACTIVE));

        assertThatThrownBy(() -> service.archive(PROJECT_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
        verify(projectMapper, never()).deleteById(any(Long.class));
    }

    // ==================== 概览缓存 ====================

    @Test
    @DisplayName("概览：命中缓存时直接返回，不再查库")
    void overview_returnsCachedValueWithoutHittingDatabase() {
        ProjectOverviewVO cached = new ProjectOverviewVO();
        cached.setProjectId(PROJECT_ID);
        cached.setProjectName("星海计划（缓存）");
        when(overviewCache.get(PROJECT_ID)).thenReturn(cached);

        ProjectOverviewVO result = service.overview(PROJECT_ID);

        assertThat(result).isSameAs(cached);
        verify(projectMapper, never()).selectById(any(Long.class));
        verify(overviewCache, never()).put(any(), any());
    }

    @Test
    @DisplayName("概览：未命中时聚合后写入缓存，模块2/3 的指标由缓存写入口补齐")
    void overview_computesAndCachesOnMiss() {
        when(overviewCache.get(PROJECT_ID)).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID))
                .thenReturn(project(ProjectStatus.ACTIVE, 6, LocalDate.now().plusDays(74)));

        ProjectOverviewVO vo = service.overview(PROJECT_ID);

        assertThat(vo.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(vo.getProjectName()).isEqualTo("星港计划");
        assertThat(vo.getMemberCount()).isEqualTo(6);
        assertThat(vo.getDaysToDeadline()).isEqualTo(74L);

        // 这里填的是「占位 0 与空数组」——真正的聚合由 ProjectOverviewCache#put
        // 在写入缓存前调用 AssetOverviewSupport / TaskOverviewSupport 完成，
        // 因此本方法必须把它们原样交给 put（下面用 captor 断言 put 拿到了这一份）
        assertThat(vo.getAssetTotal()).isZero();
        assertThat(vo.getAssetApproved()).isZero();
        assertThat(vo.getAssetPending()).isZero();
        assertThat(vo.getAssetCompletionRate()).isEqualTo(0.0);
        assertThat(vo.getTaskTotal()).isZero();
        assertThat(vo.getTaskDone()).isZero();
        assertThat(vo.getTaskOverdue()).isZero();
        assertThat(vo.getRecentAssets()).isEmpty();
        assertThat(vo.getMyTasks()).isEmpty();

        ArgumentCaptor<ProjectOverviewVO> captor = ArgumentCaptor.forClass(ProjectOverviewVO.class);
        verify(overviewCache).put(Mockito.eq(PROJECT_ID), captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo(PROJECT_ID);
    }

    /**
     * 这条盯住的是模块3 接入概览时最容易出的事故：
     * 把「我的待办」写进了按项目维度共享的缓存对象里。
     *
     * <p>表现会非常隐蔽——A 打开概览正常，B 打开看到的是 A 的任务清单，
     * 而 B 完全不知道自己做错了什么。因此断言两件事：返回给调用方的那份
     * 带着 myTasks，而<b>进缓存的那份不带</b>，且两者不是同一个对象。</p>
     */
    @Test
    @DisplayName("概览：myTasks 挂在副本上，绝不进缓存（否则 A 的待办会被缓存给 B 看）")
    void overview_fillsMyTasksOutsideTheCachedInstance() {
        when(overviewCache.get(PROJECT_ID)).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID))
                .thenReturn(project(ProjectStatus.ACTIVE, 3, null));

        TaskCardVO mine = new TaskCardVO();
        mine.setId(901L);
        mine.setTitle("给气闸舱门接入交互提示");
        // 模拟 TaskOverviewSupport 的真实行为：返回副本，副本上带 myTasks
        Mockito.when(taskOverviewSupport.withMyTasks(any(), Mockito.eq(PROJECT_ID), Mockito.eq(USER_ID)))
                .thenAnswer(invocation -> {
                    ProjectOverviewVO base = invocation.getArgument(0);
                    ProjectOverviewVO copy = new ProjectOverviewVO();
                    org.springframework.beans.BeanUtils.copyProperties(base, copy);
                    copy.setMyTasks(new ArrayList<>(List.of(mine)));
                    return copy;
                });

        ProjectOverviewVO returned = service.overview(PROJECT_ID);

        assertThat(returned.getMyTasks()).hasSize(1);
        assertThat(((TaskCardVO) returned.getMyTasks().get(0)).getTitle())
                .isEqualTo("给气闸舱门接入交互提示");

        ArgumentCaptor<ProjectOverviewVO> captor = ArgumentCaptor.forClass(ProjectOverviewVO.class);
        verify(overviewCache).put(Mockito.eq(PROJECT_ID), captor.capture());
        assertThat(captor.getValue())
                .as("进缓存的必须是项目维度的那一份，不能是带 myTasks 的副本")
                .isNotSameAs(returned);
        assertThat(captor.getValue().getMyTasks()).isEmpty();

        verify(taskOverviewSupport).withMyTasks(any(), Mockito.eq(PROJECT_ID), Mockito.eq(USER_ID));
    }

    @Test
    @DisplayName("概览：未设置计划结束日期时 daysToDeadline 为 null，不抛异常")
    void overview_handlesMissingPlanEndDate() {
        when(overviewCache.get(PROJECT_ID)).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID))
                .thenReturn(project(ProjectStatus.ACTIVE, 1, null));

        assertThat(service.overview(PROJECT_ID).getDaysToDeadline()).isNull();
    }

    @Test
    @DisplayName("概览：项目不存在 → 404，不写缓存")
    void overview_throwsNotFoundForMissingProject() {
        when(overviewCache.get(PROJECT_ID)).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.overview(PROJECT_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("项目不存在或已归档");
        verify(overviewCache, never()).put(any(), any());
    }

    // ==================== 项目列表 ====================

    @Test
    @DisplayName("我的项目列表：以成员关系为准，只有 ACTIVE 成员关系才出现")
    void listMyProjects_isDrivenByActiveMembership() {
        ProjectMember m1 = member(USER_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        ProjectMember m2 = member(USER_ID, ProjectRole.ARTIST, ProjectMemberStatus.ACTIVE);
        m2.setProjectId(20L);
        when(projectMemberMapper.selectList(any())).thenReturn(java.util.List.of(m1, m2));

        Project p1 = project(ProjectStatus.ACTIVE, 2, null);
        Project p2 = new Project();
        p2.setId(20L);
        p2.setName("像素农场");
        p2.setStatus(ProjectStatus.PAUSED);
        p2.setMemberCount(3);
        when(projectMapper.selectList(any())).thenReturn(java.util.List.of(p1, p2));

        java.util.List<ProjectVO> projects = service.listMyProjects();

        assertThat(projects).hasSize(2);
        assertThat(projects).extracting(ProjectVO::getMyRole)
                .containsExactly(ProjectRole.OWNER, ProjectRole.ARTIST);
        assertThat(projects).extracting(ProjectVO::getCanManage)
                .containsExactly(true, false);
        assertThat(projects).extracting(ProjectVO::getStatusLabel)
                .containsExactly("进行中", "已暂停");
    }

    @Test
    @DisplayName("我的项目列表：没有任何成员关系时返回空列表，不再查项目表")
    void listMyProjects_returnsEmptyWhenNoMembership() {
        when(projectMemberMapper.selectList(any())).thenReturn(java.util.List.of());

        assertThat(service.listMyProjects()).isEmpty();
        verify(projectMapper, never()).selectList(any());
    }

    // ==================== 修改项目 ====================

    @Test
    @DisplayName("修改项目：未传的字段保持原值，不会被 null 覆盖")
    void update_keepsUnsetFieldsUntouched() {
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(USER_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE));
        Project existing = project(ProjectStatus.ACTIVE, 3, LocalDate.of(2026, 12, 31));
        existing.setDescription("原简介");
        existing.setEngine("UE5");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(existing);

        ProjectUpdateDTO dto = new ProjectUpdateDTO();
        dto.setName("星港计划 第二章");
        // description / engine / code 均不传

        ProjectVO vo = service.update(PROJECT_ID, dto);

        assertThat(vo.getName()).isEqualTo("星港计划 第二章");
        assertThat(vo.getDescription()).as("未传的简介应保持原值").isEqualTo("原简介");
        assertThat(vo.getEngine()).isEqualTo("UE5");
        assertThat(vo.getPlanEndDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        verify(overviewCache).evict(PROJECT_ID);
    }

    @Test
    @DisplayName("修改项目：非 OWNER → 403")
    void update_requiresOwner() {
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(USER_ID, ProjectRole.PROGRAMMER, ProjectMemberStatus.ACTIVE));

        ProjectUpdateDTO dto = new ProjectUpdateDTO();
        dto.setName("改名试试");

        assertThatThrownBy(() -> service.update(PROJECT_ID, dto))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
        verify(projectMapper, never()).updateById(any(Project.class));
    }

    @Test
    @DisplayName("项目详情：非成员 → 403")
    void detail_requiresMembership() {
        when(projectMemberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.detail(PROJECT_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("项目详情：成员可读，并返回自己的角色")
    void detail_returnsMyRole() {
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(USER_ID, ProjectRole.VIEWER, ProjectMemberStatus.ACTIVE));
        when(projectMapper.selectById(PROJECT_ID))
                .thenReturn(project(ProjectStatus.ACTIVE, 4, null));

        ProjectVO vo = service.detail(PROJECT_ID);

        assertThat(vo.getMyRole()).isEqualTo(ProjectRole.VIEWER);
        assertThat(vo.getCanManage()).isFalse();
    }

    @Test
    @DisplayName("未登录时任何需要身份的入口都抛 401")
    void requiresLoginForIdentityDependentOperations() {
        UserContext.clear();

        assertThatThrownBy(() -> service.listMyProjects())
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(401));
        assertThatThrownBy(() -> service.detail(PROJECT_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(401));
        assertThatThrownBy(() -> service.create(new ProjectCreateDTO()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(401));
    }

    @Test
    @DisplayName("概览不参与成员校验：它是公共指标，权限由 @ProjectPermission(PROJECT_READ) 把关")
    void overviewDoesNotDependOnCurrentUser() {
        UserContext.clear();
        when(overviewCache.get(PROJECT_ID)).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID))
                .thenReturn(project(ProjectStatus.ACTIVE, 3, null));

        // 概览的公共部分（含模块2/3 的指标）与登录用户无关，没有登录上下文也能算出来；
        // 模块3 的 myTasks 是唯一例外，它取不到 userId 时直接跳过而不是抛 401
        // （否则定时任务、内部调用这类没有会话的入口一调概览就炸）。
        // 真正的访问控制由切面在控制器层完成，不在服务层重复判断
        assertThatCode(() -> service.overview(PROJECT_ID)).doesNotThrowAnyException();
        verify(taskOverviewSupport).withMyTasks(any(), Mockito.eq(PROJECT_ID), isNull());
    }
}
