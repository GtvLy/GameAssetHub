package com.demo.gameasset.controller;

import com.demo.gameasset.common.annotation.ProjectId;
import com.demo.gameasset.common.annotation.ProjectPermission;
import com.demo.gameasset.common.enums.Permission;
import com.demo.gameasset.common.result.Result;
import com.demo.gameasset.dto.JoinProjectDTO;
import com.demo.gameasset.dto.MemberInviteDTO;
import com.demo.gameasset.dto.MemberRoleDTO;
import com.demo.gameasset.dto.ProjectCreateDTO;
import com.demo.gameasset.dto.ProjectStatusDTO;
import com.demo.gameasset.dto.ProjectUpdateDTO;
import com.demo.gameasset.service.ProjectMemberService;
import com.demo.gameasset.service.ProjectService;
import com.demo.gameasset.vo.ProjectMemberVO;
import com.demo.gameasset.vo.ProjectOverviewVO;
import com.demo.gameasset.vo.ProjectVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目与成员管理接口（模块1）。
 *
 * <p>除「我参与的项目列表」与「凭邀请码加入」外，
 * 每个方法都带 {@link ProjectPermission}，由切面在进入业务前完成
 * 「是项目 ACTIVE 成员 + 角色具备权限」的双重校验，不通过直接 403。</p>
 *
 * <p>注意路径里 {@code {id}} 的解析：{@code /api/projects/{id}} 这类路径中，
 * {@code id} 就是项目ID，{@code ProjectIdResolver} 依据路径里是否有
 * {@code projects} 段来判定，因此不必逐个加 {@code @ProjectId}。</p>
 *
 * @author 管理员
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectMemberService projectMemberService;

    // ==================== 项目 ====================

    /** 我参与的项目列表（含我的角色） */
    @GetMapping
    public Result<List<ProjectVO>> myProjects() {
        return Result.success(projectService.listMyProjects());
    }

    /** 创建项目，创建者自动成为 OWNER */
    @PostMapping
    public Result<ProjectVO> create(@Valid @RequestBody ProjectCreateDTO dto) {
        return Result.success("项目创建成功", projectService.create(dto));
    }

    /**
     * 凭邀请码加入项目。
     *
     * <p>路径是 {@code /join} 而不是 {@code /{id}/join}：此时调用者<b>还不是</b>
     * 项目成员，无法通过 {@code @ProjectPermission} 校验，因此这个接口只做
     * 「登录态校验 + 邀请码校验」。放在 {@code /{id}} 之后声明，避免
     * {@code join} 被当成路径变量匹配到「项目详情」。</p>
     */
    @PostMapping("/join")
    public Result<ProjectVO> join(@Valid @RequestBody JoinProjectDTO dto) {
        Long projectId = projectMemberService.joinByInviteCode(dto.getInviteCode());
        return Result.success("加入项目成功", projectService.detail(projectId));
    }

    /** 项目详情 */
    @ProjectPermission(Permission.PROJECT_READ)
    @GetMapping("/{id}")
    public Result<ProjectVO> detail(@PathVariable("id") Long id) {
        return Result.success(projectService.detail(id));
    }

    /** 修改项目信息（OWNER） */
    @ProjectPermission(Permission.PROJECT_WRITE)
    @PutMapping("/{id}")
    public Result<ProjectVO> update(@PathVariable("id") Long id,
                                    @Valid @RequestBody ProjectUpdateDTO dto) {
        return Result.success("项目信息已更新", projectService.update(id, dto));
    }

    /** 变更项目状态：ACTIVE / PAUSED / ARCHIVED（OWNER） */
    @ProjectPermission(Permission.PROJECT_WRITE)
    @PatchMapping("/{id}/status")
    public Result<ProjectVO> changeStatus(@PathVariable("id") Long id,
                                          @Valid @RequestBody ProjectStatusDTO dto) {
        ProjectVO vo = projectService.changeStatus(id, dto.getStatus());
        return Result.success("项目状态已更新为「" + vo.getStatusLabel() + "」", vo);
    }

    /** 归档项目（OWNER，逻辑删除） */
    @ProjectPermission(Permission.PROJECT_WRITE)
    @DeleteMapping("/{id}")
    public Result<Void> archive(@PathVariable("id") Long id) {
        projectService.archive(id);
        return Result.success("项目已归档", null);
    }

    /** 项目概览首页数据（Redis 缓存，TTL 10 分钟） */
    @ProjectPermission(Permission.PROJECT_READ)
    @GetMapping("/{id}/overview")
    public Result<ProjectOverviewVO> overview(@PathVariable("id") Long id) {
        return Result.success(projectService.overview(id));
    }

    // ==================== 成员 ====================

    /** 成员列表 */
    @ProjectPermission(Permission.PROJECT_READ)
    @GetMapping("/{id}/members")
    public Result<List<ProjectMemberVO>> members(@PathVariable("id") Long id) {
        return Result.success(projectMemberService.listMembers(id));
    }

    /** 邀请成员（OWNER），返回带邀请码的成员记录 */
    @ProjectPermission(Permission.MEMBER_MANAGE)
    @PostMapping("/{id}/members")
    public Result<ProjectMemberVO> invite(@PathVariable("id") Long id,
                                          @Valid @RequestBody MemberInviteDTO dto) {
        return Result.success("邀请已生成，请把邀请码发给对方", projectMemberService.invite(id, dto));
    }

    /**
     * 修改成员角色（OWNER）。
     *
     * <p>路径里同时有 {@code id}（项目）与 {@code uid}（用户），
     * 解析器按名字就能区分，此处显式用 {@link ProjectId} 标一下，
     * 让「哪个是项目ID」在代码里一眼可见。</p>
     */
    @ProjectPermission(Permission.MEMBER_MANAGE)
    @PutMapping("/{id}/members/{uid}")
    public Result<ProjectMemberVO> updateMemberRole(@PathVariable("id") @ProjectId Long id,
                                                    @PathVariable("uid") Long uid,
                                                    @Valid @RequestBody MemberRoleDTO dto) {
        return Result.success("成员角色已更新",
                projectMemberService.updateRole(id, uid, dto.getRole()));
    }

    /** 移除成员（OWNER，置 status = REMOVED） */
    @ProjectPermission(Permission.MEMBER_MANAGE)
    @DeleteMapping("/{id}/members/{uid}")
    public Result<Void> removeMember(@PathVariable("id") @ProjectId Long id,
                                     @PathVariable("uid") Long uid) {
        projectMemberService.remove(id, uid);
        return Result.success("成员已移除", null);
    }
}
