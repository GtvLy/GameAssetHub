package com.zhangchi.gameasset.convert;

import com.zhangchi.gameasset.common.enums.ProjectRole;
import com.zhangchi.gameasset.entity.ProjectMember;
import com.zhangchi.gameasset.entity.SysUser;
import com.zhangchi.gameasset.vo.ProjectMemberVO;
import org.springframework.beans.BeanUtils;

/**
 * 项目成员实体 → VO（需拼上用户资料）。
 *
 * @author 张忠驰
 */
public final class ProjectMemberConverter {

    private ProjectMemberConverter() {
    }

    /**
     * @param member       成员记录
     * @param user         对应用户，可为 null（用户被删时仍要能显示成员行）
     * @param showInviteCode 是否暴露邀请码（仅 OWNER 可见）
     */
    public static ProjectMemberVO toVO(ProjectMember member, SysUser user, boolean showInviteCode) {
        if (member == null) {
            return null;
        }
        ProjectMemberVO vo = new ProjectMemberVO();
        vo.setId(member.getId());
        vo.setProjectId(member.getProjectId());
        vo.setUserId(member.getUserId());
        vo.setRole(member.getRole());
        vo.setRoleLabel(ProjectRole.label(member.getRole()));
        vo.setStatus(member.getStatus());
        vo.setJoinTime(member.getJoinTime());
        vo.setCreateTime(member.getCreateTime());
        // 邀请码是最敏感的一列：拿到它就能把外部人员拉进项目，
        // 因此非 OWNER 一律置空，而不是靠前端「不显示」。
        vo.setInviteCode(showInviteCode ? member.getInviteCode() : null);

        if (user != null) {
            vo.setUsername(user.getUsername());
            vo.setNickname(user.getNickname() == null || user.getNickname().isBlank()
                    ? user.getUsername() : user.getNickname());
            vo.setEmail(user.getEmail());
            vo.setAvatar(user.getAvatar());
        }
        return vo;
    }
}
