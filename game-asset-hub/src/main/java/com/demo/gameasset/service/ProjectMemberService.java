package com.demo.gameasset.service;

import com.demo.gameasset.dto.MemberInviteDTO;
import com.demo.gameasset.vo.ProjectMemberVO;

import java.util.List;

/**
 * 项目成员服务。
 *
 * <p>成员关系是 RBAC 的载体，所有写操作都会失效项目概览缓存。</p>
 *
 * @author 管理员
 */
public interface ProjectMemberService {

    /** 成员列表（不含已移除），仅 OWNER 能看到邀请码 */
    List<ProjectMemberVO> listMembers(Long projectId);

    /** 邀请成员（OWNER）：按用户名或邮箱定位用户，生成邀请码并落库为 INVITED */
    ProjectMemberVO invite(Long projectId, MemberInviteDTO dto);

    /** 修改成员角色（OWNER）；不能降级项目里唯一的负责人 */
    ProjectMemberVO updateRole(Long projectId, Long userId, String role);

    /** 移除成员（OWNER，置 status = REMOVED）；不能移除项目里唯一的负责人 */
    void remove(Long projectId, Long userId);

    /**
     * 凭邀请码加入项目。
     *
     * @return 加入的项目ID，供控制器回查项目详情返回给前端
     */
    Long joinByInviteCode(String inviteCode);

    /** 重算并回写 {@code project.member_count}（冗余字段的唯一维护入口） */
    void refreshMemberCount(Long projectId);
}
