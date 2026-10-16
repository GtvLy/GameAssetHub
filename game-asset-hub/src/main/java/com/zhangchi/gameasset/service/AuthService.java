package com.zhangchi.gameasset.service;

import com.zhangchi.gameasset.dto.LoginDTO;
import com.zhangchi.gameasset.dto.RegisterDTO;
import com.zhangchi.gameasset.vo.CurrentUserVO;
import com.zhangchi.gameasset.vo.LoginVO;

/**
 * 认证服务。
 *
 * @author 张忠驰
 */
public interface AuthService {

    /** 注册新用户，密码以 BCrypt 存储 */
    void register(RegisterDTO dto);

    /** 登录，成功返回 JWT 与用户信息 */
    LoginVO login(LoginDTO dto);

    /** 登出，把当前令牌的 jti 写入 Redis 黑名单 */
    void logout();

    /** 当前登录用户信息 + 我参与的项目列表 */
    CurrentUserVO currentUser();
}
