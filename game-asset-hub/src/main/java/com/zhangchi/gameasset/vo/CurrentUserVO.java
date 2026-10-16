package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/auth/me} 的响应：当前用户信息 + 我参与的项目列表。
 *
 * <p>前端启动时调一次这个接口就能同时拿到身份与项目上下文，
 * 不必再串行请求 {@code /api/projects}，首屏少一个往返。</p>
 *
 * @author 张忠驰
 */
@Data
public class CurrentUserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;

    private String username;

    private String nickname;

    private String email;

    private String avatar;

    /** 系统角色：ADMIN / MEMBER */
    private String role;

    /** 我参与的项目（仅 status = ACTIVE 的成员关系），含我在该项目中的角色 */
    private List<ProjectVO> projects = new ArrayList<>();
}
