package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 项目成员列表项。
 *
 * @author 管理员
 */
@Data
public class ProjectMemberVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 成员记录ID（project_member.id） */
    private Long id;

    private Long projectId;

    /** 用户ID，修改角色 / 移除成员接口的路径参数 */
    private Long userId;

    private String username;

    private String nickname;

    private String email;

    private String avatar;

    /** 项目内角色 */
    private String role;

    /** 角色中文名 */
    private String roleLabel;

    /** INVITED / ACTIVE / REMOVED */
    private String status;

    /**
     * 邀请码。仅项目 OWNER 可见（非 OWNER 返回 null），
     * 避免普通成员把邀请码转发给外部人员。
     */
    private String inviteCode;

    private LocalDateTime joinTime;

    private LocalDateTime createTime;
}
