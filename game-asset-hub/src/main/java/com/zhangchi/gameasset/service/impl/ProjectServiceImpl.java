package com.zhangchi.gameasset.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhangchi.gameasset.common.enums.ProjectMemberStatus;
import com.zhangchi.gameasset.common.enums.ProjectRole;
import com.zhangchi.gameasset.common.enums.ProjectStatus;
import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.context.UserContext;
import com.zhangchi.gameasset.convert.ProjectConverter;
import com.zhangchi.gameasset.dto.ProjectCreateDTO;
import com.zhangchi.gameasset.dto.ProjectUpdateDTO;
import com.zhangchi.gameasset.entity.Project;
import com.zhangchi.gameasset.entity.ProjectMember;
import com.zhangchi.gameasset.mapper.ProjectMapper;
import com.zhangchi.gameasset.mapper.ProjectMemberMapper;
import com.zhangchi.gameasset.service.ProjectService;
import com.zhangchi.gameasset.service.support.ProjectAccessGuard;
import com.zhangchi.gameasset.service.support.ProjectOverviewCache;
import com.zhangchi.gameasset.service.support.TaskOverviewSupport;
import com.zhangchi.gameasset.vo.ProjectOverviewVO;
import com.zhangchi.gameasset.vo.ProjectVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目服务实现。
 *
 * @author 张忠驰
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectAccessGuard accessGuard;
    private final ProjectOverviewCache overviewCache;

    /**
     * 任务指标与「我的待办」（模块3 提供）。
     * 公共指标由它填进缓存，用户维度数据由它在缓存之外挂上。
     */
    private final TaskOverviewSupport taskOverviewSupport;

    @Override
    public List<ProjectVO> listMyProjects() {
        Long userId = UserContext.requireUserId();

        // 第一步：从成员关系（而不是项目表）出发，天然只能列出「我参与的」项目。
        // 条件带 user_id + status，且 project_member 无逻辑删除列，不必担心 deleted 干扰
        List<ProjectMember> memberships = projectMemberMapper.selectList(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getUserId, userId)
                        .eq(ProjectMember::getStatus, ProjectMemberStatus.ACTIVE)
                        .orderByDesc(ProjectMember::getId));

        if (memberships.isEmpty()) {
            return Collections.emptyList();
        }

        // 同一用户在同一项目只可能有一条成员记录（uk_project_user 保证），
        // 但仍用 merge 兜一下，避免历史脏数据导致 toMap 抛 IllegalStateException
        Map<Long, String> roleByProject = new LinkedHashMap<>();
        for (ProjectMember member : memberships) {
            roleByProject.putIfAbsent(member.getProjectId(), member.getRole());
        }

        List<Project> projects = projectMapper.selectList(
                Wrappers.<Project>lambdaQuery()
                        .in(Project::getId, roleByProject.keySet())
                        .orderByDesc(Project::getCreateTime));

        List<ProjectVO> result = new ArrayList<>(projects.size());
        for (Project project : projects) {
            result.add(ProjectConverter.toVO(project, roleByProject.get(project.getId())));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO create(ProjectCreateDTO dto) {
        Long userId = UserContext.requireUserId();
        validateDateRange(dto.getStartDate(), dto.getPlanEndDate());

        Project project = new Project();
        BeanUtils.copyProperties(dto, project);
        project.setName(dto.getName().trim());
        project.setCode(StringUtils.hasText(dto.getCode()) ? dto.getCode().trim() : null);
        project.setOwnerId(userId);
        project.setStatus(ProjectStatus.ACTIVE);
        // 创建者自己先算一个成员，避免出现「0 人项目」
        project.setMemberCount(1);
        projectMapper.insert(project);

        // 创建者自动成为 OWNER：这一步必须先写 project 再写成员，
        // 两者在同一事务里，任一步失败都回滚，不会出现「有项目没人管」的中间态
        ProjectMember owner = new ProjectMember();
        owner.setProjectId(project.getId());
        owner.setUserId(userId);
        owner.setRole(ProjectRole.OWNER);
        owner.setStatus(ProjectMemberStatus.ACTIVE);
        owner.setJoinTime(LocalDateTime.now());
        projectMemberMapper.insert(owner);

        overviewCache.evict(project.getId());
        log.info("[项目] 创建成功 projectId={} name={} ownerId={}",
                project.getId(), project.getName(), userId);
        return ProjectConverter.toVO(project, ProjectRole.OWNER);
    }

    @Override
    public ProjectVO detail(Long projectId) {
        Long userId = UserContext.requireUserId();
        // 详情只对项目成员开放（切面已按 PROJECT_READ 拦过一道，这里是服务层兜底）
        ProjectMember member = accessGuard.requireActiveMember(projectId, userId);
        Project project = getExistingProject(projectId);
        return ProjectConverter.toVO(project, member.getRole());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO update(Long projectId, ProjectUpdateDTO dto) {
        Long userId = UserContext.requireUserId();
        accessGuard.requireOwner(projectId, userId);

        Project project = getExistingProject(projectId);
        LocalDate startDate = dto.getStartDate() != null ? dto.getStartDate() : project.getStartDate();
        LocalDate planEndDate = dto.getPlanEndDate() != null ? dto.getPlanEndDate() : project.getPlanEndDate();
        validateDateRange(startDate, planEndDate);

        // 逐字段判空：null 表示「不改这一项」而不是「清空」。
        // 用 BeanUtils.copyProperties 会把 null 一并覆盖，只想改名字却把简介清掉
        project.setName(dto.getName().trim());
        if (dto.getCode() != null) {
            project.setCode(StringUtils.hasText(dto.getCode()) ? dto.getCode().trim() : null);
        }
        if (dto.getDescription() != null) {
            project.setDescription(dto.getDescription());
        }
        if (dto.getEngine() != null) {
            project.setEngine(dto.getEngine());
        }
        if (dto.getCoverUrl() != null) {
            project.setCoverUrl(dto.getCoverUrl());
        }
        if (dto.getStartDate() != null) {
            project.setStartDate(dto.getStartDate());
        }
        if (dto.getPlanEndDate() != null) {
            project.setPlanEndDate(dto.getPlanEndDate());
        }
        projectMapper.updateById(project);

        overviewCache.evict(projectId);
        log.info("[项目] 修改成功 projectId={} operator={}", projectId, userId);
        return ProjectConverter.toVO(project, ProjectRole.OWNER);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO changeStatus(Long projectId, String status) {
        Long userId = UserContext.requireUserId();
        if (!ProjectStatus.isValid(status)) {
            throw BizException.badRequest("非法的项目状态：" + status
                    + "，仅支持 " + ProjectStatus.all());
        }
        accessGuard.requireOwner(projectId, userId);

        Project project = getExistingProject(projectId);
        String fromStatus = project.getStatus();
        if (status.equals(fromStatus)) {
            // 幂等：状态没变就不写库、不失效缓存，避免无意义的一次 UPDATE
            return ProjectConverter.toVO(project, ProjectRole.OWNER);
        }

        project.setStatus(status);
        projectMapper.updateById(project);

        overviewCache.evict(projectId);
        log.info("[项目] 状态变更 projectId={} {} → {} operator={}",
                projectId, fromStatus, status, userId);
        return ProjectConverter.toVO(project, ProjectRole.OWNER);
    }

    /**
     * 归档项目 = 逻辑删除。
     *
     * <p>只置 {@code project.deleted = 1}，<b>不动 project_member</b>：
     * 成员关系是历史协作记录，删掉会丢失「谁在什么时候参与过」的追溯能力。
     * 项目不可见后，成员记录也不会被单独查询到——所有成员查询都带 project_id。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archive(Long projectId) {
        Long userId = UserContext.requireUserId();
        accessGuard.requireOwner(projectId, userId);
        getExistingProject(projectId);

        // Project 上有 @TableLogic，deleteById 实际执行 UPDATE project SET deleted = 1
        projectMapper.deleteById(projectId);

        overviewCache.evict(projectId);
        log.info("[项目] 已归档（逻辑删除）projectId={} operator={}", projectId, userId);
    }

    /**
     * 项目概览：成员数直查；资产指标（模块2）与任务指标（模块3）
     * 由 {@code ProjectOverviewCache#put} 在写缓存前补齐。
     *
     * <p>走 Redis 缓存（TTL 10 分钟），项目信息、成员、资产、任务的任何写操作
     * 都会主动失效该 key。</p>
     *
     * <p><b>唯一不进缓存的是 {@code myTasks}</b>：它是「当前登录用户的待办」，
     * 而缓存按项目维度共享。因此在缓存之外单独查、单独挂到副本上——
     * 直接写在 {@code vo} 上等于把这份 VO 也写进了缓存，
     * 下一个打开概览的成员会看到别人的待办清单。</p>
     */
    @Override
    public ProjectOverviewVO overview(Long projectId) {
        ProjectOverviewVO vo = baseOverview(projectId);
        // userId 为空时（定时任务、内部调用）原样返回，概览的公共部分不依赖登录上下文
        return taskOverviewSupport.withMyTasks(vo, projectId, UserContext.getUserId());
    }

    /** 项目维度的概览数据（可缓存的那部分） */
    private ProjectOverviewVO baseOverview(Long projectId) {
        ProjectOverviewVO cached = overviewCache.get(projectId);
        if (cached != null) {
            return cached;
        }

        Project project = getExistingProject(projectId);

        ProjectOverviewVO vo = new ProjectOverviewVO();
        vo.setProjectId(project.getId());
        vo.setProjectName(project.getName());
        vo.setProjectStatus(project.getStatus());
        vo.setMemberCount(project.getMemberCount() == null ? 0 : project.getMemberCount());

        // 资产指标（assetTotal / assetApproved / assetPending / assetCompletionRate /
        // recentAssets）在这里按接口契约填 0 与空数组，真正的聚合由模块2 完成：
        // ProjectOverviewCache#put 在写入缓存前调用 AssetOverviewSupport#fill，
        // 原地补齐这几个字段。写在这里的原因是保证「进缓存的」与「返回给前端的」
        // 是同一份数据，不依赖「先填后 put」的调用顺序。任务指标（模块3）同理，
        // 由 TaskOverviewSupport#fill 补齐。
        vo.setAssetTotal(0);
        vo.setAssetApproved(0);
        vo.setAssetPending(0);
        vo.setAssetCompletionRate(completionRate(vo.getAssetApproved(), vo.getAssetTotal()));
        vo.setTaskTotal(0);
        vo.setTaskDone(0);
        vo.setTaskOverdue(0);

        // 距计划结束日期天数：已过期返回负数，未设置返回 null（前端显示「未设置」）
        vo.setDaysToDeadline(project.getPlanEndDate() == null
                ? null
                : ChronoUnit.DAYS.between(LocalDate.now(), project.getPlanEndDate()));

        // recentAssets 同样由模块2 的 AssetOverviewSupport 在写缓存前补齐；
        // myTasks 由 overview() 在缓存之外挂上（见方法注释）
        vo.setRecentAssets(new ArrayList<>());
        vo.setMyTasks(new ArrayList<>());

        overviewCache.put(projectId, vo);
        log.debug("[项目] 概览聚合完成并写入缓存 projectId={}", projectId);
        return vo;
    }

    // ==================== 内部工具 ====================

    /**
     * 按主键查项目，查不到即 404。
     * 这里之所以敢只按 id 查，是因为调用它的每个方法都已经先用
     * {@link ProjectAccessGuard} 校验过「调用者是该项目 ACTIVE 成员」。
     * 模块2/3 的资产、任务等<b>从属资源</b>则必须把 project_id 拼进 SQL 条件，
     * 不允许照搬这个写法。
     */
    private Project getExistingProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw BizException.notFound("项目不存在或已归档");
        }
        return project;
    }

    private void validateDateRange(LocalDate startDate, LocalDate planEndDate) {
        if (startDate != null && planEndDate != null && planEndDate.isBefore(startDate)) {
            throw BizException.badRequest("计划结束日期不能早于项目开始日期");
        }
    }

    /** 完成率（%），保留一位小数；分母为 0 时返回 0.0，避免除零 */
    private Double completionRate(Integer approved, Integer total) {
        if (total == null || total == 0 || approved == null) {
            return 0.0;
        }
        return BigDecimal.valueOf(approved * 100.0 / total)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
