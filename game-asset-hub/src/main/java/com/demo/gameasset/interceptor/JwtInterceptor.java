package com.demo.gameasset.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.demo.gameasset.common.constant.RedisKeys;
import com.demo.gameasset.common.result.Result;
import com.demo.gameasset.common.result.ResultCode;
import com.demo.gameasset.common.util.JwtUtil;
import com.demo.gameasset.context.UserContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * JWT 鉴权拦截器。
 *
 * <p>用 {@link HandlerInterceptor} 而非 Spring Security 过滤链：
 * 本项目的鉴权模型是「登录态 + 项目级 RBAC」，
 * 项目级部分由 {@code ProjectPermissionAspect} 在方法级完成，
 * 引 Security 反而要把整套过滤器链和它的默认行为一起接过来，得不偿失。</p>
 *
 * <p>失败时直接写响应体而不是抛异常：拦截器抛异常要经过
 * {@code HandlerExceptionResolver} 才能落到 {@code @RestControllerAdvice}，
 * 路径上存在「handler 为 null 时是否命中全局 advice」的实现细节差异。
 * 这里自己写死响应，输出格式与全局异常处理器完全一致，行为百分之百确定。</p>
 *
 * @author 管理员
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final RedisCacheGateway redisCacheGateway;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // CORS 预检请求不带 Authorization 头，必须放行，否则浏览器跨域直接失败
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
            writeError(response, ResultCode.UNAUTHORIZED.getCode(), "未登录或缺少访问令牌");
            return false;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (ExpiredJwtException e) {
            writeError(response, ResultCode.UNAUTHORIZED.getCode(), "登录已过期，请重新登录");
            return false;
        } catch (Exception e) {
            log.warn("[鉴权] 令牌非法：{}", e.getMessage());
            writeError(response, ResultCode.UNAUTHORIZED.getCode(), "登录凭证无效，请重新登录");
            return false;
        }

        // 登出黑名单：jti 命中即视为已登出
        String jti = claims.getId();
        if (StringUtils.hasText(jti)
                && redisCacheGateway.isBlacklisted(RedisKeys.tokenBlacklist(jti))) {
            writeError(response, ResultCode.UNAUTHORIZED.getCode(), "登录状态已失效，请重新登录");
            return false;
        }

        UserContext.setUserId(Long.valueOf(claims.getSubject()));
        UserContext.setUsername(claims.get(JwtUtil.CLAIM_USERNAME, String.class));
        UserContext.setTokenId(jti);
        UserContext.setToken(token);
        return true;
    }

    /**
     * 请求结束清理线程变量。
     *
     * <p><b>这一句是数据隔离的底线</b>：Tomcat 复用工作线程，
     * 不清理的话下一个请求会读到上一个用户的 userId / 项目角色，
     * 表现为「随机地以别人的身份操作」，且难以复现。</p>
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private void writeError(HttpServletResponse response, int code, String message) throws Exception {
        HttpStatus status = HttpStatus.resolve(code);
        response.setStatus(status == null ? HttpStatus.UNAUTHORIZED.value() : status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
        response.getWriter().flush();
    }
}
