package com.zhangchi.gameasset.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhangchi.gameasset.common.constant.RedisKeys;
import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.common.util.JwtUtil;
import com.zhangchi.gameasset.context.UserContext;
import com.zhangchi.gameasset.convert.UserConverter;
import com.zhangchi.gameasset.dto.LoginDTO;
import com.zhangchi.gameasset.dto.RegisterDTO;
import com.zhangchi.gameasset.entity.SysUser;
import com.zhangchi.gameasset.mapper.SysUserMapper;
import com.zhangchi.gameasset.service.AuthService;
import com.zhangchi.gameasset.service.ProjectService;
import com.zhangchi.gameasset.service.RedisCacheService;
import com.zhangchi.gameasset.vo.CurrentUserVO;
import com.zhangchi.gameasset.vo.LoginVO;
import com.zhangchi.gameasset.vo.UserVO;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 认证服务实现。
 *
 * @author 张忠驰
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper sysUserMapper;
    private final ProjectService projectService;
    private final JwtUtil jwtUtil;
    private final RedisCacheService redisCacheService;

    /**
     * 直接用 BCryptPasswordEncoder，<b>不引 spring-boot-starter-security</b>：
     * 引了会连带开启整套 Security 过滤链，与本项目的拦截器鉴权方案打架。
     * 这里只把它当无状态工具类用。
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterDTO dto) {
        String username = dto.getUsername().trim();

        // 友好校验；并发注册时仍可能撞库，由 uk_username 唯一索引兜底，
        // 冲突抛 DuplicateKeyException，全局异常处理器转成 1001
        Long exists = sysUserMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
        if (exists != null && exists > 0) {
            throw BizException.badRequest("登录名「" + username + "」已被占用");
        }

        String email = StringUtils.hasText(dto.getEmail()) ? dto.getEmail().trim() : null;
        if (email != null) {
            Long emailExists = sysUserMapper.selectCount(
                    Wrappers.<SysUser>lambdaQuery().eq(SysUser::getEmail, email));
            if (emailExists != null && emailExists > 0) {
                throw BizException.badRequest("邮箱「" + email + "」已被注册");
            }
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        // BCrypt 每次加盐，同一密码两次加密结果不同，库里存的永远是哈希
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname().trim() : username);
        user.setEmail(email);
        user.setRole(SysUser.SYS_ROLE_MEMBER);
        user.setStatus(SysUser.STATUS_ENABLED);
        sysUserMapper.insert(user);

        log.info("[认证] 新用户注册成功 userId={} username={}", user.getId(), username);
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        SysUser user = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getUsername, dto.getUsername().trim())
                        .last("LIMIT 1"));

        // 用户不存在与密码错误返回同一句提示，避免被用来枚举已注册的登录名
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            log.warn("[认证] 登录失败 username={}", dto.getUsername());
            throw BizException.badRequest("登录名或密码错误");
        }

        if (user.getStatus() != null && user.getStatus() == SysUser.STATUS_DISABLED) {
            throw BizException.forbidden("账号已被禁用，请联系管理员");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setTokenType("Bearer");
        vo.setExpiresIn(jwtUtil.getExpireSeconds());
        vo.setUser(UserConverter.toVO(user));

        log.info("[认证] 登录成功 userId={} username={}", user.getId(), user.getUsername());
        return vo;
    }

    /**
     * 登出：把当前令牌的 jti 写入 Redis 黑名单。
     *
     * <p>TTL 取令牌的<b>剩余有效期</b>而不是固定值：令牌自然过期后
     * 黑名单条目也随之消失，不会让 Redis 无限增长；
     * 同时保证在令牌本来还能用的这段时间里，它一定是被拒的。</p>
     */
    @Override
    public void logout() {
        Long userId = UserContext.getUserId();
        String jti = UserContext.getTokenId();
        if (!StringUtils.hasText(jti)) {
            log.warn("[认证] 登出时未取到 jti，跳过黑名单写入 userId={}", userId);
            return;
        }

        Duration ttl = resolveBlacklistTtl();
        redisCacheService.setString(RedisKeys.tokenBlacklist(jti), "1", ttl);
        log.info("[认证] 用户登出 userId={} jti={} 黑名单 TTL={}秒", userId, jti, ttl.getSeconds());
    }

    /**
     * 计算黑名单 TTL。
     * 优先按令牌真实剩余时间；解析失败（理论上不会，拦截器已校验过）时
     * 退化为完整有效期——宁可多留一会儿，也不能因为算不出来就放行已登出的令牌。
     */
    private Duration resolveBlacklistTtl() {
        String token = UserContext.getToken();
        if (StringUtils.hasText(token)) {
            try {
                Claims claims = jwtUtil.parseToken(token);
                long remaining = jwtUtil.getRemainingMillis(claims);
                if (remaining > 0) {
                    return Duration.ofMillis(remaining);
                }
            } catch (Exception e) {
                log.warn("[认证] 解析令牌剩余有效期失败，退化为完整有效期：{}", e.getMessage());
            }
        }
        return Duration.ofSeconds(jwtUtil.getExpireSeconds());
    }

    @Override
    public CurrentUserVO currentUser() {
        Long userId = UserContext.requireUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw BizException.unauthorized("账号不存在或已被删除");
        }

        UserVO userVO = UserConverter.toVO(user);

        CurrentUserVO vo = new CurrentUserVO();
        vo.setUserId(userVO.getId());
        vo.setUsername(userVO.getUsername());
        vo.setNickname(userVO.getNickname());
        vo.setEmail(userVO.getEmail());
        vo.setAvatar(userVO.getAvatar());
        vo.setRole(userVO.getRole());
        // 我参与的项目列表（含我的角色）——登录后前端一次调用即可构建项目上下文
        vo.setProjects(projectService.listMyProjects());
        return vo;
    }
}
