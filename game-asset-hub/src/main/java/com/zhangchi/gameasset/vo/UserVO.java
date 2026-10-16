package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户信息（对外暴露）。
 *
 * <p>刻意不复用 {@code SysUser} 实体：实体带 {@code password} 字段，
 * 一旦有人图省事直接把实体返回给前端，密码哈希就泄露了。
 * 单独一个 VO 是最省事的防呆。</p>
 *
 * @author 张忠驰
 */
@Data
public class UserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String username;

    private String nickname;

    private String email;

    private String avatar;

    /** 系统角色：ADMIN / MEMBER */
    private String role;
}
