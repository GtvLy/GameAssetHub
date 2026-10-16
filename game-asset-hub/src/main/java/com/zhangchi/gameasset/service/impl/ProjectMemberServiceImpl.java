package com.zhangchi.gameasset.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhangchi.gameasset.common.enums.ProjectMemberStatus;
import com.zhangchi.gameasset.common.enums.ProjectRole;
import com.zhangchi.gameasset.common.enums.ProjectStatus;
import com.zhangchi.gameasset.common.enums.RolePermissionMap;
import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.context.UserContext;
import com.zhangchi.gameasset.convert.ProjectMemberConverter;
import com.zhangchi.gameasset.dto.MemberInviteDTO;
import com.zhangchi.gameasset.entity.Project;
import com.zhangchi.gameasset.entity.ProjectMember;
import com.zhangchi.gameasset.entity.SysUser;
import com.zhangchi.gameasset.mapper.ProjectMapper;
import com.zhangchi.gameasset.mapper.ProjectMemberMapper;
import com.zhangchi.gameasset.mapper.SysUserMapper;
import com.zhangchi.gameasset.service.ProjectMemberService;
import com.zhangchi.gameasset.service.support.ProjectAccessGuard;
import com.zhangchi.gameasset.service.support.ProjectOverviewCache;
import com.zhangchi.gameasset.vo.ProjectMemberVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目成员服务实现。
 *
 * @author 张忠驰
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectMemberServiceImpl implements ProjectMemberService {

    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectMapper projectMapper;
    private final SysUserMapper sysUserMapper;
    private final ProjectAccessGuard accessGuard;
    private final ProjectOverviewCache overviewCache;

    @Override
    public List<ProjectMemberVO> listMembers(Long projectId) {
        Long currentUserId = UserContext.requireUserId();
        // 成员列表对全部项目成员可见（PROJECT_READ），但邀请码只有 OWNER 能看
        ProjectMember self = accessGuard.requireActiveMember(projectId, currentUserId);
        boolean showInviteCode = ProjectRole.OWNER.equals(self.getRole());

        List<ProjectMember> members = projectMemberMapper.selectList(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getProjectId, projectId)
                        .ne(ProjectMember::getStatus, ProjectMemberStatus.REMOVED));
        if (members.isEmpty()) {
            return Collections.emptyList();
        }

        // 一次批量取用户，避免每行一次查询（N+1）
        Set<Long> userIds = members.stream().map(ProjectMember::getUserId).collect(Collectors.toSet());
        Map<Long, SysUser> userMap = sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));

        List<ProjectMemberVO> result = new ArrayList<>(members.size());
        for (ProjectMember member : members) {
            result.add(ProjectMemberConverter.toVO(member, userMap.get(member.getUserId()), showInviteCode));
        }

        // 排序：负责人置顶 → 已加入优先 → 用户ID 升序，保证多次请求顺序稳定
        result.sort(Comparator
                .comparing((ProjectMemberVO vo) -> !ProjectRole.OWNER.equals(vo.getRole()))
                .thenComparing(vo -> !ProjectMemberStatus.ACTIVE.equals(vo.getStatus()))
                .thenComparing(ProjectMemberVO::getUserId, Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectMemberVO invite(Long projectId, MemberInviteDTO dto) {
        Long operatorId = UserContext.requireUserId();
        accessGuard.requireOwner(projectId, operatorId);
        Project project = getExistingProject(projectId);
        if (ProjectStatus.ARCHIVED.equals(project.getStatus())) {
            throw BizException.badRequest("项目已归档，无法邀请成员");
        }

        String account = dto.getAccount().trim();
        // 括号包裹 (username = ? OR email = ?)：MyBatis-Plus 的逻辑删除条件会以
        // AND 追加到 WHERE 末尾，不包括号会变成 username = ? OR (email = ? AND deleted = 0)，
        // 于是「已删除用户的用户名」也能匹配上
        SysUser target = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .and(w -> w.eq(SysUser::getUsername, account).or().eq(SysUser::getEmail, account))
                        .last("LIMIT 1"));
        if (target == null) {
            throw BizException.notFound("未找到用户「" + account + "」，请确认用户名或邮箱是否正确");
        }
        if (target.getId().equals(operatorId)) {
            throw BizException.badRequest("不能邀请自己，你已经是该项目成员");
        }

        String role = resolveRole(dto.getRole());
        String inviteCode = generateInviteCode();

        ProjectMember existing = accessGuard.findMember(projectId, target.getId());
        ProjectMember saved;
        if (existing == null) {
            ProjectMember member = new ProjectMember();
            member.setProjectId(projectId);
            member.setUserId(target.getId());
            member.setRole(role);
            member.setStatus(ProjectMemberStatus.INVITED);
            member.setInviteCode(inviteCode);
            projectMemberMapper.insert(member);
            saved = member;
        } else {
            if (ProjectMemberStatus.ACTIVE.equals(existing.getStatus())) {
                throw BizException.badRequest("「" + displayName(target) + "」已经是该项目成员");
            }
            // 已邀请未加入 → 重发邀请码；已移除 → 重新邀请。
            // 两种都复用同一行：project_member 有 uk_project_user 唯一约束，
            // 插入新行必然撞键
            projectMemberMapper.update(null,
                    Wrappers.<ProjectMember>lambdaUpdate()
                            .eq(ProjectMember::getId, existing.getId())
                            .eq(ProjectMember::getProjectId, projectId)
                            .set(ProjectMember::getRole, role)
                            .set(ProjectMember::getStatus, ProjectMemberStatus.INVITED)
                            .set(ProjectMember::getInviteCode, inviteCode)
                            // 显式置 null：updateById 会跳过 null 字段，清不掉上一次的加入时间
                            .set(ProjectMember::getJoinTime, null));
            existing.setRole(role);
            existing.setStatus(ProjectMemberStatus.INVITED);
            existing.setInviteCode(inviteCode);
            existing.setJoinTime(null);
            saved = existing;
        }

        refreshMemberCount(projectId);
        overviewCache.evict(projectId);
        log.info("[成员] 邀请成功 projectId={} targetUserId={} role={} operator={}",
                projectId, target.getId(), role, operatorId);
        return ProjectMemberConverter.toVO(saved, target, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectMemberVO updateRole(Long projectId, Long userId, String role) {
        Long operatorId = UserContext.requireUserId();
        accessGuard.requireOwner(projectId, operatorId);

        String newRole = resolveRequiredRole(role);

        // 单资源查询强制带 project_id：先按 userId 查出来再判断归属，
        // 一旦忘了判断就能把别的项目的成员改掉
        ProjectMember target = accessGuard.findMember(projectId, userId);
        if (target == null || ProjectMemberStatus.REMOVED.equals(target.getStatus())) {
            throw BizException.notFound("成员不存在或不属于当前项目");
        }

        if (newRole.equals(target.getRole())) {
            // 幂等：角色没变直接返回，不做无意义的写库与缓存失效
            return ProjectMemberConverter.toVO(target, sysUserMapper.selectById(userId), true);
        }

        // 不能把项目里唯一的负责人降级，否则项目将无人可管理
        accessGuard.ensureNotLastActiveOwner(projectId, target, "降级");

        projectMemberMapper.update(null,
                Wrappers.<ProjectMember>lambdaUpdate()
                        .eq(ProjectMember::getId, target.getId())
                        .eq(ProjectMember::getProjectId, projectId)
                        .set(ProjectMember::getRole, newRole));

        overviewCache.evict(projectId);
        log.info("[成员] 角色变更 projectId={} targetUserId={} {} → {} operator={}",
                projectId, userId, target.getRole(), newRole, operatorId);

        target.setRole(newRole);
        return ProjectMemberConverter.toVO(target, sysUserMapper.selectById(userId), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long projectId, Long userId) {
        Long operatorId = UserContext.requireUserId();
        accessGuard.requireOwner(projectId, operatorId);

        ProjectMember target = accessGuard.findMember(projectId, userId);
        if (target == null || ProjectMemberStatus.REMOVED.equals(target.getStatus())) {
            throw BizException.notFound("成员不存在或不属于当前项目");
        }

        // 不能移除项目里唯一的负责人
        accessGuard.ensureNotLastActiveOwner(projectId, target, "移除");

        // project_member 无 deleted 列 → 状态位软删，
        // 保留这一行既留了痕，也让 uk_project_user 继续拦住重复邀请
        projectMemberMapper.update(null,
                Wrappers.<ProjectMember>lambdaUpdate()
                        .eq(ProjectMember::getId, target.getId())
                        .eq(ProjectMember::getProjectId, projectId)
                        .set(ProjectMember::getStatus, ProjectMemberStatus.REMOVED)
                        // 移除即作废邀请码，防止被移除的人拿着旧码自己加回来
                        .set(ProjectMember::getInviteCode, null));

        refreshMemberCount(projectId);
        overviewCache.evict(projectId);
        log.info("[成员] 移除成功 projectId={} targetUserId={} role={} operator={}",
                projectId, userId, target.getRole(), operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long joinByInviteCode(String inviteCode) {
        Long userId = UserContext.requireUserId();
        if (!StringUtils.hasText(inviteCode)) {
            throw BizException.badRequest("邀请码不能为空");
        }

        // 邀请码只在「已邀请未加入」状态下有效；加入成功后邀请码会被清空，
        // 所以「查不到」天然等于「无效或已被使用」，不需要额外的 used 标志位
        ProjectMember invitation = projectMemberMapper.selectOne(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getInviteCode, inviteCode.trim())
                        .eq(ProjectMember::getStatus, ProjectMemberStatus.INVITED)
                        .last("LIMIT 1"));
        if (invitation == null) {
            throw BizException.badRequest("邀请码无效或已被使用");
        }

        Long projectId = invitation.getProjectId();
        Project project = getExistingProject(projectId);
        if (ProjectStatus.ARCHIVED.equals(project.getStatus())) {
            throw BizException.badRequest("项目已归档，无法加入");
        }

        ProjectMember mine = accessGuard.findMember(projectId, userId);
        if (mine != null && ProjectMemberStatus.ACTIVE.equals(mine.getStatus())) {
            throw BizException.badRequest("你已经是该项目成员，无需重复加入");
        }

        String role = RolePermissionMap.isValidRole(invitation.getRole())
                ? invitation.getRole() : ProjectRole.ARTIST;
        LocalDateTime now = LocalDateTime.now();
        boolean isInvitee = invitation.getUserId().equals(userId);

        if (isInvitee) {
            // 正常路径：被邀请人本人凭码加入，把邀请行直接激活
            projectMemberMapper.update(null,
                    Wrappers.<ProjectMember>lambdaUpdate()
                            .eq(ProjectMember::getId, invitation.getId())
                            .eq(ProjectMember::getProjectId, projectId)
                            .eq(ProjectMember::getStatus, ProjectMemberStatus.INVITED)
                            .set(ProjectMember::getStatus, ProjectMemberStatus.ACTIVE)
                            .set(ProjectMember::getJoinTime, now)
                            .set(ProjectMember::getInviteCode, null));
        } else {
            // 邀请码被转发给团队里其他人使用（邀请链接的常见用法）：
            // 当前用户自己入伙，角色沿用邀请时指定的角色
            if (mine == null) {
                ProjectMember newcomer = new ProjectMember();
                newcomer.setProjectId(projectId);
                newcomer.setUserId(userId);
                newcomer.setRole(role);
                newcomer.setStatus(ProjectMemberStatus.ACTIVE);
                newcomer.setJoinTime(now);
                projectMemberMapper.insert(newcomer);
            } else {
                // 曾被移除过：uk_project_user 唯一约束下必须复用原行
                projectMemberMapper.update(null,
                        Wrappers.<ProjectMember>lambdaUpdate()
                                .eq(ProjectMember::getId, mine.getId())
                                .eq(ProjectMember::getProjectId, projectId)
                                .set(ProjectMember::getRole, role)
                                .set(ProjectMember::getStatus, ProjectMemberStatus.ACTIVE)
                                .set(ProjectMember::getJoinTime, now)
                                .set(ProjectMember::getInviteCode, null));
            }
            // 邀请码是一次性的：被转发使用后立即作废，防止被反复拉人
            projectMemberMapper.update(null,
                    Wrappers.<ProjectMember>lambdaUpdate()
                            .eq(ProjectMember::getId, invitation.getId())
                            .eq(ProjectMember::getProjectId, projectId)
                            .set(ProjectMember::getInviteCode, null));
        }

        refreshMemberCount(projectId);
        overviewCache.evict(projectId);
        log.info("[成员] 凭邀请码加入成功 projectId={} userId={} role={} 作为被邀请人={}",
                projectId, userId, role, isInvitee);
        return projectId;
    }

    /**
     * 重算 project.member_count。
     *
     * <p>口径：<b>未移除的成员数</b>（含 INVITED）。冗余字段的唯一维护入口，
     * 所有会改变成员集合的操作都必须调用它，否则概览上的成员数会长期偏低。</p>
     */
    @Override
    public void refreshMemberCount(Long projectId) {
        if (projectId == null) {
            return;
        }
        Long count = projectMemberMapper.selectCount(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getProjectId, projectId)
                        .ne(ProjectMember::getStatus, ProjectMemberStatus.REMOVED));
        int memberCount = count == null ? 0 : count.intValue();

        projectMapper.update(null,
                Wrappers.<Project>lambdaUpdate()
                        .eq(Project::getId, projectId)
                        .set(Project::getMemberCount, memberCount));
    }

    // ==================== 内部工具 ====================

    private Project getExistingProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw BizException.notFound("项目不存在或已归档");
        }
        return project;
    }

    /** 角色为空时取默认值 ARTIST（文档 DDL 的默认角色） */
    private String resolveRole(String role) {
        if (!StringUtils.hasText(role)) {
            return ProjectRole.ARTIST;
        }
        String normalized = role.trim().toUpperCase();
        if (!RolePermissionMap.isValidRole(normalized)) {
            throw BizException.badRequest("非法的成员角色：" + role
                    + "，可选值：" + RolePermissionMap.roles());
        }
        return normalized;
    }

    /** 角色必填场景（修改成员角色），不允许走默认值 */
    private String resolveRequiredRole(String role) {
        if (!StringUtils.hasText(role)) {
            throw BizException.badRequest("成员角色不能为空");
        }
        return resolveRole(role);
    }

    /**
     * 生成邀请码。
     * 用完整 UUID（32 位十六进制）而不是截断的短码：invite_code 在本表没有唯一索引，
     * 短码在成员量上来后有碰撞风险，撞上会让两个人拿到同一个码。
     */
    private String generateInviteCode() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private String displayName(SysUser user) {
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }
}
