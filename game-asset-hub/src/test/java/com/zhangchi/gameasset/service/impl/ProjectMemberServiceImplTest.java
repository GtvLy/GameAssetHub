package com.zhangchi.gameasset.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.Update;
import com.zhangchi.gameasset.common.enums.ProjectMemberStatus;
import com.zhangchi.gameasset.common.enums.ProjectRole;
import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.context.UserContext;
import com.zhangchi.gameasset.dto.MemberInviteDTO;
import com.zhangchi.gameasset.entity.Project;
import com.zhangchi.gameasset.entity.ProjectMember;
import com.zhangchi.gameasset.entity.SysUser;
import com.zhangchi.gameasset.mapper.ProjectMapper;
import com.zhangchi.gameasset.mapper.ProjectMemberMapper;
import com.zhangchi.gameasset.mapper.SysUserMapper;
import com.zhangchi.gameasset.service.support.ProjectAccessGuard;
import com.zhangchi.gameasset.service.support.ProjectOverviewCache;
import com.zhangchi.gameasset.support.MybatisPlusTestSupport;
import com.zhangchi.gameasset.vo.ProjectMemberVO;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成员服务测试：成员邀请、角色调整、移除、凭邀请码加入。
 *
 * <p>重点覆盖两条硬性规则：</p>
 * <ol>
 *   <li><b>项目里唯一的 OWNER 不能被移除或降级</b>（否则项目将无人可管理）；</li>
 *   <li>成员的单资源查询始终带 {@code project_id}，不允许跨项目操作。</li>
 * </ol>
 *
 * <p>Mockito mock 掉三个 Mapper 与缓存，服务与守卫用真实对象，
 * 不加载 Spring 上下文、不连 MySQL / Redis，因此 {@code mvn test} 在有网无网、
 * 数据库起没起的情况下都能跑。</p>
 *
 * @author 张忠驰
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProjectMemberServiceImpl 成员管理")
class ProjectMemberServiceImplTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long OPERATOR_ID = 1L;   // 当前登录用户：OWNER
    private static final Long TARGET_ID = 2L;     // 被操作的目标成员

    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private ProjectOverviewCache overviewCache;

    private ProjectAccessGuard accessGuard;
    private ProjectMemberServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleOneEntities();
        accessGuard = new ProjectAccessGuard(projectMemberMapper);
        service = new ProjectMemberServiceImpl(projectMemberMapper, projectMapper,
                sysUserMapper, accessGuard, overviewCache);
        UserContext.setUserId(OPERATOR_ID);
        UserContext.setUsername("zhangchi");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 测试夹具 ====================

    private ProjectMember member(Long userId, String role, String status) {
        ProjectMember m = new ProjectMember();
        m.setId(userId);
        m.setProjectId(PROJECT_ID);
        m.setUserId(userId);
        m.setRole(role);
        m.setStatus(status);
        return m;
    }

    private Project project(String status) {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setName("星港计划");
        p.setOwnerId(OPERATOR_ID);
        p.setStatus(status);
        p.setMemberCount(2);
        return p;
    }

    private SysUser user(Long id, String username) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        u.setNickname(username);
        u.setRole(SysUser.SYS_ROLE_MEMBER);
        u.setStatus(SysUser.STATUS_ENABLED);
        return u;
    }

    /** 让 requireOwner 通过：第一次 selectOne 返回操作者，第二次返回目标成员 */
    private void stubOperatorAndTarget(ProjectMember operator, ProjectMember target) {
        when(projectMemberMapper.selectOne(any())).thenReturn(operator, target);
    }

    private String capturedUpdateSetClause() {
        ArgumentCaptor<Wrapper<ProjectMember>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(projectMemberMapper).update(isNull(), captor.capture());
        Wrapper<ProjectMember> wrapper = captor.getValue();
        assertThat(wrapper).as("应使用 UpdateWrapper 才能把 invite_code 显式置为 null").isInstanceOf(Update.class);
        return ((Update<?, ?>) wrapper).getSqlSet();
    }

    // ==================== 唯一 OWNER：不可移除 ====================

    @Test
    @DisplayName("移除项目里唯一的 OWNER 应被拒绝，且不产生任何写操作")
    void remove_rejectsRemovingTheOnlyOwner() {
        ProjectMember operator = member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        ProjectMember target = member(TARGET_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        stubOperatorAndTarget(operator, target);
        // 项目内 ACTIVE 状态的 OWNER 只有 1 个
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.remove(PROJECT_ID, TARGET_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少需要保留一名负责人");

        // 关键：被拦下后不能留下任何痕迹
        verify(projectMemberMapper, never()).update(any(), any());
        verify(projectMapper, never()).update(any(), any());
        verify(overviewCache, never()).evict(any());
    }

    @Test
    @DisplayName("负责人本人也不能移除自己（他是最后一名）")
    void remove_rejectsOwnerRemovingHimselfAsLastOwner() {
        ProjectMember operator = member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        stubOperatorAndTarget(operator, operator);
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.remove(PROJECT_ID, OPERATOR_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少需要保留一名负责人");

        verify(projectMemberMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("项目有两名 OWNER 时，可以移除其中一名")
    void remove_allowsWhenAnotherOwnerRemains() {
        ProjectMember operator = member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        ProjectMember target = member(TARGET_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        stubOperatorAndTarget(operator, target);
        when(projectMemberMapper.selectCount(any())).thenReturn(2L);

        assertThatCode(() -> service.remove(PROJECT_ID, TARGET_ID)).doesNotThrowAnyException();

        String setClause = capturedUpdateSetClause();
        assertThat(setClause)
                .as("成员移除是状态位软删，并作废邀请码")
                .contains("status")
                .contains("invite_code");

        // 移除后要重算冗余成员数并失效概览缓存
        verify(projectMapper).update(isNull(), any());
        verify(overviewCache).evict(PROJECT_ID);
    }

    @Test
    @DisplayName("移除普通成员不受唯一负责人规则影响")
    void remove_allowsRemovingNormalMember() {
        ProjectMember operator = member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        ProjectMember target = member(TARGET_ID, ProjectRole.ARTIST, ProjectMemberStatus.ACTIVE);
        stubOperatorAndTarget(operator, target);

        assertThatCode(() -> service.remove(PROJECT_ID, TARGET_ID)).doesNotThrowAnyException();

        // 目标不是 OWNER，不该触发「还剩几个负责人」的查询。
        // 注意 remove 之后必然有一次 selectCount——那是 refreshMemberCount 在重算冗余成员数，
        // 所以这里不能简单断言 never()，而要断言那次查询里没有 role 条件
        ArgumentCaptor<Wrapper<ProjectMember>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(projectMemberMapper, times(1)).selectCount(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .as("唯一一次 count 应来自成员数重算，而不是负责人数量校验")
                .doesNotContain("role");

        verify(projectMemberMapper).update(isNull(), any());
    }

    // ==================== 唯一 OWNER：不可降级 ====================

    @Test
    @DisplayName("把项目里唯一的 OWNER 降级为 ARTIST 应被拒绝")
    void updateRole_rejectsDemotingTheOnlyOwner() {
        ProjectMember operator = member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        ProjectMember target = member(TARGET_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        stubOperatorAndTarget(operator, target);
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.updateRole(PROJECT_ID, TARGET_ID, ProjectRole.ARTIST))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少需要保留一名负责人")
                .hasMessageContaining("降级");

        verify(projectMemberMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("项目有两名 OWNER 时，允许把其中一名降级")
    void updateRole_allowsDemotingWhenAnotherOwnerRemains() {
        ProjectMember operator = member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        ProjectMember target = member(TARGET_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        stubOperatorAndTarget(operator, target);
        when(projectMemberMapper.selectCount(any())).thenReturn(2L);
        when(sysUserMapper.selectById(TARGET_ID)).thenReturn(user(TARGET_ID, "liyang"));

        ProjectMemberVO vo = service.updateRole(PROJECT_ID, TARGET_ID, ProjectRole.ARTIST);

        assertThat(vo.getRole()).isEqualTo(ProjectRole.ARTIST);
        assertThat(vo.getRoleLabel()).isEqualTo("美术");
        assertThat(capturedUpdateSetClause()).contains("role");
        verify(overviewCache).evict(PROJECT_ID);
    }

    @Test
    @DisplayName("把 OWNER 降级为 OWNER 是幂等的，不写库")
    void updateRole_isIdempotentWhenRoleUnchanged() {
        ProjectMember operator = member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        ProjectMember target = member(TARGET_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        stubOperatorAndTarget(operator, target);
        when(sysUserMapper.selectById(TARGET_ID)).thenReturn(user(TARGET_ID, "liyang"));

        ProjectMemberVO vo = service.updateRole(PROJECT_ID, TARGET_ID, ProjectRole.OWNER);

        assertThat(vo.getRole()).isEqualTo(ProjectRole.OWNER);
        verify(projectMemberMapper, never()).update(any(), any());
        verify(projectMemberMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("非法角色值应被拒绝，不会把任意字符串写进数据库")
    void updateRole_rejectsInvalidRole() {
        ProjectMember operator = member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        stubOperatorAndTarget(operator, member(TARGET_ID, ProjectRole.ARTIST, ProjectMemberStatus.ACTIVE));

        assertThatThrownBy(() -> service.updateRole(PROJECT_ID, TARGET_ID, "SUPER_ADMIN"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法的成员角色");
    }

    @Test
    @DisplayName("非 OWNER 调用成员管理 → 403")
    void memberManagement_requiresOwnerRole() {
        stubOperatorAndTarget(member(OPERATOR_ID, ProjectRole.ARTIST, ProjectMemberStatus.ACTIVE),
                member(TARGET_ID, ProjectRole.ARTIST, ProjectMemberStatus.ACTIVE));

        assertThatThrownBy(() -> service.remove(PROJECT_ID, TARGET_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
        assertThatThrownBy(() -> service.updateRole(PROJECT_ID, TARGET_ID, ProjectRole.VIEWER))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("目标成员不属于该项目 → 404，不做任何写操作")
    void updateRole_rejectsMemberOfAnotherProject() {
        ProjectMember operator = member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        // findMember 按 (project_id, user_id) 查，跨项目的用户在查询里就落空了
        stubOperatorAndTarget(operator, null);

        assertThatThrownBy(() -> service.updateRole(PROJECT_ID, 999L, ProjectRole.VIEWER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("成员不存在或不属于当前项目");

        verify(projectMemberMapper, never()).update(any(), any());
    }

    // ==================== 邀请 ====================

    @Test
    @DisplayName("邀请：落库为 INVITED 并生成邀请码，默认角色 ARTIST")
    void invite_createsInvitedMemberWithCode() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("ACTIVE"));
        // 第 1 次 selectOne 是 requireOwner 的操作者，第 2 次是 findMember，目标还不是成员 → null
        stubOperatorAndTarget(member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE), null);
        when(sysUserMapper.selectOne(any())).thenReturn(user(TARGET_ID, "liyang"));

        MemberInviteDTO dto = new MemberInviteDTO();
        dto.setAccount("liyang");
        dto.setRole(null);

        ProjectMemberVO vo = service.invite(PROJECT_ID, dto);

        ArgumentCaptor<ProjectMember> captor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberMapper).insert(captor.capture());
        ProjectMember inserted = captor.getValue();
        assertThat(inserted.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(inserted.getUserId()).isEqualTo(TARGET_ID);
        assertThat(inserted.getRole()).as("未指定角色时默认 ARTIST").isEqualTo(ProjectRole.ARTIST);
        assertThat(inserted.getStatus()).isEqualTo(ProjectMemberStatus.INVITED);
        assertThat(inserted.getInviteCode()).as("必须生成邀请码").isNotBlank().hasSize(32);
        assertThat(inserted.getJoinTime()).as("尚未加入，join_time 应为空").isNull();

        assertThat(vo.getInviteCode()).isEqualTo(inserted.getInviteCode());
        verify(projectMapper).update(isNull(), any());
        verify(overviewCache).evict(PROJECT_ID);
    }

    @Test
    @DisplayName("邀请：账号不存在 → 404")
    void invite_rejectsUnknownAccount() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("ACTIVE"));
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE));
        when(sysUserMapper.selectOne(any())).thenReturn(null);

        MemberInviteDTO dto = new MemberInviteDTO();
        dto.setAccount("nobody@nowhere.dev");

        assertThatThrownBy(() -> service.invite(PROJECT_ID, dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未找到用户");
        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
    }

    @Test
    @DisplayName("邀请：对方已是项目成员 → 400")
    void invite_rejectsExistingActiveMember() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("ACTIVE"));
        ProjectMember existing = member(TARGET_ID, ProjectRole.ARTIST, ProjectMemberStatus.ACTIVE);
        // 第 1 次是 requireOwner 的操作者，第 2 次是 findMember 的目标
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE), existing);
        when(sysUserMapper.selectOne(any())).thenReturn(user(TARGET_ID, "liyang"));

        MemberInviteDTO dto = new MemberInviteDTO();
        dto.setAccount("liyang");

        assertThatThrownBy(() -> service.invite(PROJECT_ID, dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已经是该项目成员");
        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
    }

    @Test
    @DisplayName("邀请：被移除过的成员重新邀请，复用原行而不新增（避开 uk_project_user）")
    void invite_revivesRemovedMemberOnSameRow() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("ACTIVE"));
        ProjectMember removed = member(TARGET_ID, ProjectRole.ARTIST, ProjectMemberStatus.REMOVED);
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE), removed);
        when(sysUserMapper.selectOne(any())).thenReturn(user(TARGET_ID, "liyang"));

        MemberInviteDTO dto = new MemberInviteDTO();
        dto.setAccount("liyang");
        dto.setRole(ProjectRole.DESIGNER);

        ProjectMemberVO vo = service.invite(PROJECT_ID, dto);

        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
        String setClause = capturedUpdateSetClause();
        assertThat(setClause)
                .contains("role")
                .contains("status")
                .contains("invite_code")
                .contains("join_time");
        assertThat(vo.getStatus()).isEqualTo(ProjectMemberStatus.INVITED);
        assertThat(vo.getRole()).isEqualTo(ProjectRole.DESIGNER);
    }

    @Test
    @DisplayName("邀请：项目已归档 → 拒绝")
    void invite_rejectsArchivedProject() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("ARCHIVED"));
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE));

        MemberInviteDTO dto = new MemberInviteDTO();
        dto.setAccount("liyang");

        assertThatThrownBy(() -> service.invite(PROJECT_ID, dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已归档");
    }

    // ==================== 凭邀请码加入 ====================

    @Test
    @DisplayName("加入：邀请码无效或已被使用 → 400")
    void join_rejectsUnknownOrUsedCode() {
        when(projectMemberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.joinByInviteCode("NOT_A_REAL_CODE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("邀请码无效或已被使用");
        verify(overviewCache, never()).evict(any());
    }

    @Test
    @DisplayName("加入：被邀请人凭码加入 → 置 ACTIVE、写 join_time、清空邀请码")
    void join_activatesInvitationForTheInvitee() {
        ProjectMember invitation = member(OPERATOR_ID, ProjectRole.ARTIST, ProjectMemberStatus.INVITED);
        invitation.setInviteCode("ABC123");
        when(projectMemberMapper.selectOne(any())).thenReturn(invitation);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("ACTIVE"));

        Long joinedProjectId = service.joinByInviteCode("ABC123");

        assertThat(joinedProjectId).isEqualTo(PROJECT_ID);
        String setClause = capturedUpdateSetClause();
        assertThat(setClause)
                .as("加入后必须置 ACTIVE、写 join_time、清空 invite_code")
                .contains("status")
                .contains("join_time")
                .contains("invite_code");
        verify(projectMapper).update(isNull(), any());
        verify(overviewCache).evict(PROJECT_ID);
    }

    @Test
    @DisplayName("加入：已经是项目成员 → 400，不重复加入")
    void join_rejectsAlreadyActiveMember() {
        ProjectMember invitation = member(TARGET_ID, ProjectRole.ARTIST, ProjectMemberStatus.INVITED);
        invitation.setInviteCode("ABC123");
        ProjectMember existing = member(OPERATOR_ID, ProjectRole.ARTIST, ProjectMemberStatus.ACTIVE);
        // 第 1 次按邀请码查，第 2 次是 findMember 查当前用户
        when(projectMemberMapper.selectOne(any())).thenReturn(invitation, existing);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("ACTIVE"));

        assertThatThrownBy(() -> service.joinByInviteCode("ABC123"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已经是该项目成员");
        verify(projectMemberMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("加入：项目已归档 → 拒绝")
    void join_rejectsArchivedProject() {
        ProjectMember invitation = member(OPERATOR_ID, ProjectRole.ARTIST, ProjectMemberStatus.INVITED);
        invitation.setInviteCode("ABC123");
        when(projectMemberMapper.selectOne(any())).thenReturn(invitation);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("ARCHIVED"));

        assertThatThrownBy(() -> service.joinByInviteCode("ABC123"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已归档");
    }

    // ==================== 冗余成员数 ====================

    @Test
    @DisplayName("refreshMemberCount 按「未移除」口径回写 member_count")
    void refreshMemberCount_writesCountExcludingRemoved() {
        when(projectMemberMapper.selectCount(any())).thenReturn(3L);

        service.refreshMemberCount(PROJECT_ID);

        verify(projectMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("refreshMemberCount 对 null 项目ID 安全跳过")
    void refreshMemberCount_ignoresNullProjectId() {
        service.refreshMemberCount(null);
        verify(projectMemberMapper, never()).selectCount(any());
    }

    // ==================== 成员列表 ====================

    @Test
    @DisplayName("成员列表：OWNER 能看到邀请码，普通成员看不到")
    void listMembers_onlyOwnerSeesInviteCode() {
        ProjectMember invited = member(TARGET_ID, ProjectRole.PROGRAMMER, ProjectMemberStatus.INVITED);
        invited.setInviteCode("SECRETCODE");
        invited.setCreateTime(LocalDateTime.now());

        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE));
        when(projectMemberMapper.selectList(any())).thenReturn(java.util.List.of(invited));
        when(sysUserMapper.selectBatchIds(any())).thenReturn(java.util.List.of(user(TARGET_ID, "liyang")));

        ProjectMemberVO asOwner = service.listMembers(PROJECT_ID).get(0);
        assertThat(asOwner.getInviteCode()).isEqualTo("SECRETCODE");

        // 换成 ARTIST 视角
        Mockito.reset(projectMemberMapper);
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(OPERATOR_ID, ProjectRole.ARTIST, ProjectMemberStatus.ACTIVE));
        when(projectMemberMapper.selectList(any())).thenReturn(java.util.List.of(invited));
        when(sysUserMapper.selectBatchIds(any())).thenReturn(java.util.List.of(user(TARGET_ID, "liyang")));

        ProjectMemberVO asArtist = service.listMembers(PROJECT_ID).get(0);
        assertThat(asArtist.getInviteCode())
                .as("非 OWNER 一律不返回邀请码，而不是靠前端不显示")
                .isNull();
    }
}
