package com.zhangchi.gameasset.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhangchi.gameasset.common.enums.ProjectMemberStatus;
import com.zhangchi.gameasset.common.enums.ProjectRole;
import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.entity.ProjectMember;
import com.zhangchi.gameasset.mapper.ProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 项目成员身份与角色的守卫。
 *
 * <p>为什么单独抽一个组件而不是写在某个 Service 里：这些判断
 * （是不是成员、是不是负责人、还剩几个负责人）会被项目服务与成员服务同时用到，
 * 而且它们是<b>越权防线</b>，必须只有一份实现——两份实现早晚会分叉，
 * 分叉出来的那一份就是漏洞。</p>
 *
 * <p><b>所有查询都带 {@code project_id}</b>：不存在「先按 user_id 查出成员记录，
 * 再在 Java 里比 projectId」的写法。前者由数据库保证作用域，
 * 后者一旦漏比就是跨项目越权。</p>
 *
 * @author 张忠驰
 */
@Component
@RequiredArgsConstructor
public class ProjectAccessGuard {

    private final ProjectMemberMapper projectMemberMapper;

    /**
     * 查成员记录（可空），查询条件强制带 project_id。
     *
     * @return 该用户在该项目下的成员记录；不是成员返回 null（含已移除）
     */
    public ProjectMember findMember(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            return null;
        }
        return projectMemberMapper.selectOne(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, userId));
    }

    /**
     * 要求当前用户是该项目的 ACTIVE 成员，否则 403。
     */
    public ProjectMember requireActiveMember(Long projectId, Long userId) {
        ProjectMember member = projectMemberMapper.selectOne(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, userId)
                        .eq(ProjectMember::getStatus, ProjectMemberStatus.ACTIVE));
        if (member == null) {
            throw BizException.forbidden("你不是该项目成员，无权访问");
        }
        return member;
    }

    /**
     * 要求当前用户是该项目的 OWNER，否则 403。
     *
     * <p>切面已经按 {@code MEMBER_MANAGE} 权限拦过一道，
     * 这里是服务层的第二道：Service 可能被非 HTTP 入口（定时任务、别的 Service）调用，
     * 那时切面不生效，不能只依赖注解。</p>
     */
    public ProjectMember requireOwner(Long projectId, Long userId) {
        ProjectMember member = requireActiveMember(projectId, userId);
        if (!ProjectRole.OWNER.equals(member.getRole())) {
            throw BizException.forbidden("仅项目负责人（OWNER）可执行此操作");
        }
        return member;
    }

    /**
     * 统计项目内 ACTIVE 状态的 OWNER 数量。
     * 只算 ACTIVE：被邀请但未加入（INVITED）的负责人还不能算数，
     * 否则唯一负责人被移除后项目会处于无人可管理的状态。
     */
    public long countActiveOwners(Long projectId) {
        Long count = projectMemberMapper.selectCount(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getRole, ProjectRole.OWNER)
                        .eq(ProjectMember::getStatus, ProjectMemberStatus.ACTIVE));
        return count == null ? 0L : count;
    }

    /**
     * 校验「不能动最后一个 ACTIVE 负责人」。
     *
     * <p>只在目标本身是 ACTIVE 的 OWNER 时才校验：去降级一个还没加入的
     * 受邀负责人、或移除一个普通美术，都不会让项目失去管理者。</p>
     *
     * @param action 动作中文名，用于拼错误提示，如「移除」「降级」
     */
    public void ensureNotLastActiveOwner(Long projectId, ProjectMember target, String action) {
        if (target == null) {
            return;
        }
        boolean targetIsActiveOwner = ProjectRole.OWNER.equals(target.getRole())
                && ProjectMemberStatus.ACTIVE.equals(target.getStatus());
        if (!targetIsActiveOwner) {
            return;
        }
        if (countActiveOwners(projectId) <= 1) {
            throw BizException.badRequest(
                    "项目至少需要保留一名负责人（OWNER），无法" + action + "唯一的负责人");
        }
    }
}
