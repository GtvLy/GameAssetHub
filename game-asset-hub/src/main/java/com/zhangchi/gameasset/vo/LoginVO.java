package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录响应。
 *
 * @author 张忠驰
 */
@Data
public class LoginVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** JWT 访问令牌 */
    private String token;

    /** 令牌类型，固定 Bearer，前端拼 Authorization 头用 */
    private String tokenType = "Bearer";

    /** 有效期（秒） */
    private Long expiresIn;

    /** 登录用户信息 */
    private UserVO user;
}
