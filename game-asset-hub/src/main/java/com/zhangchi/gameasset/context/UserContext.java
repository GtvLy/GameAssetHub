package com.zhangchi.gameasset.context;

import com.zhangchi.gameasset.common.exception.BizException;

/**
 * 当前请求上下文（线程级）。
 *
 * <p>{@code JwtInterceptor} 在 preHandle 里写入登录用户，
 * {@code ProjectPermissionAspect} 通过后补写项目上下文（当前项目ID + 当前项目角色），
 * 供 Service / Mapper 层直接取用，避免每个方法都往下传 userId、projectId。</p>
 *
 * <p><b>务必清空</b>：Tomcat 线程池会复用线程，若不清空，下一个请求会读到
 * 上一个用户的身份，造成越权（A 用户的请求被当成 B 用户处理）。
 * 因此 {@code JwtInterceptor#afterCompletion} 里必须调用 {@link #clear()}。</p>
 *
 * @author 张忠驰
 */
public final class UserContext {

    /** 当前登录用户ID */
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    /** 当前登录用户名 */
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    /** 当前登录令牌ID（jti），登出时写入 Redis 黑名单 */
    private static final ThreadLocal<String> TOKEN_ID = new ThreadLocal<>();

    /** 当前请求携带的原始令牌，登出时用它算出剩余有效期作为黑名单 TTL */
    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

    /** 当前项目ID（由权限切面写入） */
    private static final ThreadLocal<Long> PROJECT_ID = new ThreadLocal<>();

    /** 当前用户在该项目中的角色（由权限切面写入） */
    private static final ThreadLocal<String> PROJECT_ROLE = new ThreadLocal<>();

    private UserContext() {
    }

    // ==================== 登录用户 ====================

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    /**
     * 取当前登录用户ID，未登录直接抛 401。
     * Service 层拿不到用户就说明拦截器配置漏了，属于编码错误，但要给出明确响应而不是 NPE。
     */
    public static Long requireUserId() {
        Long userId = USER_ID.get();
        if (userId == null) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        return userId;
    }

    public static String getUsername() {
        return USERNAME.get();
    }

    public static void setUsername(String username) {
        USERNAME.set(username);
    }

    public static String getTokenId() {
        return TOKEN_ID.get();
    }

    public static void setTokenId(String tokenId) {
        TOKEN_ID.set(tokenId);
    }

    public static String getToken() {
        return TOKEN.get();
    }

    public static void setToken(String token) {
        TOKEN.set(token);
    }

    // ==================== 项目上下文 ====================

    public static Long getProjectId() {
        return PROJECT_ID.get();
    }

    public static void setProjectId(Long projectId) {
        PROJECT_ID.set(projectId);
    }

    /**
     * 取当前项目ID，没有则抛 400。
     *
     * <p>与 {@link #requireUserId()} 对称：模块2 的资产接口路径形如
     * {@code /api/assets/{id}}，路径里没有项目段，projectId 完全依赖
     * {@code ProjectPermissionAspect} 解析后写入。拿不到就说明该接口
     * 漏标了 {@code @ProjectPermission}——这属于编码错误，
     * 必须显式报错而不是让 {@code null} 一路传到 SQL 里变成全表查询。</p>
     */
    public static Long requireProjectId() {
        Long projectId = PROJECT_ID.get();
        if (projectId == null) {
            throw BizException.badRequest("无法确定当前项目上下文，请通过 X-Project-Id 请求头或路径指定项目");
        }
        return projectId;
    }

    public static String getProjectRole() {
        return PROJECT_ROLE.get();
    }

    public static void setProjectRole(String projectRole) {
        PROJECT_ROLE.set(projectRole);
    }

    // ==================== 生命周期 ====================

    /**
     * 清空全部线程变量。
     * <b>必须在 {@code JwtInterceptor#afterCompletion} 中调用</b>，否则线程池复用会串号。
     */
    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
        TOKEN_ID.remove();
        TOKEN.remove();
        PROJECT_ID.remove();
        PROJECT_ROLE.remove();
    }
}
