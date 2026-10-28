package com.demo.gameasset.service.support;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.demo.gameasset.common.enums.ProjectMemberStatus;
import com.demo.gameasset.common.enums.ProjectRole;
import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.entity.ProjectMember;
import com.demo.gameasset.mapper.ProjectMemberMapper;
import com.demo.gameasset.support.MybatisPlusTestSupport;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 项目成员守卫测试：唯一 OWNER 不许移除/降级 + 越权返回 403 + 查询强制带 project_id。
 *
 * <p>Mockito mock 掉 {@link ProjectMemberMapper}，不加载 Spring、不连 MySQL / Redis。</p>
 *
 * @author 管理员
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProjectAccessGuard 成员身份与唯一负责人校验")
class ProjectAccessGuardTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long OPERATOR_ID = 1L;
    private static final Long TARGET_ID = 2L;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    private ProjectAccessGuard guard;

    @BeforeEach
    void setUp() {
        // Lambda 条件构造器需要实体的表结构元数据，脱离 Spring 时必须手工注册
        MybatisPlusTestSupport.initModuleOneEntities();
        guard = new ProjectAccessGuard(projectMemberMapper);
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

    // ==================== 唯一负责人保护 ====================

    @Test
    @DisplayName("项目只剩一名 ACTIVE 负责人时，不允许移除")
    void rejectsRemovingTheOnlyActiveOwner() {
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);

        ProjectMember target = member(TARGET_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);

        assertThatThrownBy(() -> guard.ensureNotLastActiveOwner(PROJECT_ID, target, "移除"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少需要保留一名负责人")
                .hasMessageContaining("移除");
    }

    @Test
    @DisplayName("项目只剩一名 ACTIVE 负责人时，不允许降级")
    void rejectsDemotingTheOnlyActiveOwner() {
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);

        ProjectMember target = member(TARGET_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);

        assertThatThrownBy(() -> guard.ensureNotLastActiveOwner(PROJECT_ID, target, "降级"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少需要保留一名负责人")
                .hasMessageContaining("降级");
    }

    @Test
    @DisplayName("负责人数量为 0 的脏数据同样被拦下（<= 1 都算最后一名）")
    void rejectsWhenOwnerCountIsZero() {
        when(projectMemberMapper.selectCount(any())).thenReturn(0L);

        ProjectMember target = member(TARGET_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);

        assertThatThrownBy(() -> guard.ensureNotLastActiveOwner(PROJECT_ID, target, "移除"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("有两名 ACTIVE 负责人时，允许移除其中一名")
    void allowsRemovingWhenTwoActiveOwnersExist() {
        when(projectMemberMapper.selectCount(any())).thenReturn(2L);

        ProjectMember target = member(TARGET_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);

        assertThatCode(() -> guard.ensureNotLastActiveOwner(PROJECT_ID, target, "移除"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("目标是普通成员时不触发唯一负责人校验（不去查负责人数量）")
    void doesNotCheckOwnerCountForNonOwnerTarget() {
        ProjectMember target = member(TARGET_ID, ProjectRole.ARTIST, ProjectMemberStatus.ACTIVE);

        assertThatCode(() -> guard.ensureNotLastActiveOwner(PROJECT_ID, target, "移除"))
                .doesNotThrowAnyException();
        Mockito.verify(projectMemberMapper, Mockito.never()).selectCount(any());
    }

    @Test
    @DisplayName("目标是尚未加入（INVITED）的负责人时不算最后一名，允许移除/降级")
    void ignoresInvitedOwnerTarget() {
        ProjectMember target = member(TARGET_ID, ProjectRole.OWNER, ProjectMemberStatus.INVITED);

        assertThatCode(() -> guard.ensureNotLastActiveOwner(PROJECT_ID, target, "降级"))
                .doesNotThrowAnyException();
        Mockito.verify(projectMemberMapper, Mockito.never()).selectCount(any());
    }

    @Test
    @DisplayName("目标为 null 时安全跳过")
    void ignoresNullTarget() {
        assertThatCode(() -> guard.ensureNotLastActiveOwner(PROJECT_ID, null, "移除"))
                .doesNotThrowAnyException();
    }

    // ==================== 越权一律 403 ====================

    @Test
    @DisplayName("非项目成员访问 → 403（不是 404、不是 200）")
    void requireActiveMember_throws403WhenNotAMember() {
        when(projectMemberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> guard.requireActiveMember(PROJECT_ID, OPERATOR_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是该项目成员")
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("是成员但角色不是 OWNER → 403")
    void requireOwner_throws403WhenRoleIsNotOwner() {
        when(projectMemberMapper.selectOne(any()))
                .thenReturn(member(OPERATOR_ID, ProjectRole.ARTIST, ProjectMemberStatus.ACTIVE));

        assertThatThrownBy(() -> guard.requireOwner(PROJECT_ID, OPERATOR_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅项目负责人")
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("OWNER 通过校验")
    void requireOwner_passesForOwner() {
        ProjectMember owner = member(OPERATOR_ID, ProjectRole.OWNER, ProjectMemberStatus.ACTIVE);
        when(projectMemberMapper.selectOne(any())).thenReturn(owner);

        assertThat(guard.requireOwner(PROJECT_ID, OPERATOR_ID)).isSameAs(owner);
    }

    @Test
    @DisplayName("入参为 null 时不查库，直接判为无成员")
    void findMember_handlesNullArguments() {
        assertThat(guard.findMember(null, OPERATOR_ID)).isNull();
        assertThat(guard.findMember(PROJECT_ID, null)).isNull();
        Mockito.verify(projectMemberMapper, Mockito.never()).selectOne(any());
    }

    // ==================== 第二层：查询 SQL 强制带 project_id ====================

    @Test
    @DisplayName("成员查询的 SQL 条件必须同时带 project_id / user_id / status")
    void memberQueryAlwaysScopedByProjectId() {
        when(projectMemberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> guard.requireActiveMember(PROJECT_ID, OPERATOR_ID))
                .isInstanceOf(BizException.class);

        String sqlSegment = capturedQuerySqlSegment();
        assertThat(sqlSegment)
                .as("越权防线：单资源查询必须按项目维度下推，不能只按 user_id 查")
                .contains("project_id")
                .contains("user_id")
                .contains("status");
    }

    @Test
    @DisplayName("统计负责人数量时同样带 project_id + role + status")
    void ownerCountQueryIsScoped() {
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);

        assertThat(guard.countActiveOwners(PROJECT_ID)).isEqualTo(1L);

        ArgumentCaptor<Wrapper<ProjectMember>> captor = captureWrapper();
        Mockito.verify(projectMemberMapper).selectCount(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .contains("project_id")
                .contains("role")
                .contains("status");
    }

    @Test
    @DisplayName("project_id 作为参数值真实传入了 SQL")
    void projectIdIsBoundAsParameter() {
        when(projectMemberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> guard.requireActiveMember(PROJECT_ID, OPERATOR_ID))
                .isInstanceOf(BizException.class);

        ArgumentCaptor<Wrapper<ProjectMember>> captor = captureWrapper();
        Mockito.verify(projectMemberMapper).selectOne(captor.capture());
        Wrapper<ProjectMember> wrapper = captor.getValue();
        // getParamNameValuePairs 定义在 AbstractWrapper 上，不在 Wrapper 接口里
        assertThat(wrapper).isInstanceOf(AbstractWrapper.class);
        // 参数是「生成 SQL 片段时才登记」的（formatParam 是惰性求值），
        // 所以必须先触发一次 getSqlSegment()，否则拿到的是空 map
        assertThat(wrapper.getSqlSegment()).contains("project_id");
        assertThat(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values())
                .as("project_id 必须作为真实参数绑进 SQL，而不是只在 Java 里判断")
                .contains(PROJECT_ID, OPERATOR_ID, ProjectMemberStatus.ACTIVE);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Wrapper<ProjectMember>> captureWrapper() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    private String capturedQuerySqlSegment() {
        ArgumentCaptor<Wrapper<ProjectMember>> captor = captureWrapper();
        Mockito.verify(projectMemberMapper).selectOne(captor.capture());
        return captor.getValue().getSqlSegment();
    }
}
