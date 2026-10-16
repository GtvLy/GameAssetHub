package com.zhangchi.gameasset.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析（jjwt 0.12.6 新 API）。
 *
 * <p>0.12.x 相比 0.9.x 的 API 变化较大，这里用的是新写法：</p>
 * <ul>
 *   <li>签发：{@code Jwts.builder()...signWith(secretKey).compact()}
 *       ——不再需要传 {@code SignatureAlgorithm}，密钥长度已决定算法；</li>
 *   <li>解析：{@code Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)}
 *       ——{@code parseClaimsJws} 已废弃。</li>
 * </ul>
 *
 * <p><b>密钥长度必须 ≥ 32 字节</b>（HS256 要求），否则 0.12.x 会抛
 * {@code WeakKeyException: The specified key byte array is 0 bits which is not
 * secure enough}。这里用 {@code Keys.hmacShaKeyFor} 在启动阶段就把密钥转成
 * {@link SecretKey}，配置写错会立刻在启动时暴露，而不是等到第一次登录。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@Component
public class JwtUtil {

    /** HS256 要求的最小密钥字节数 */
    private static final int MIN_SECRET_BYTES = 32;

    /** 令牌中的用户名字段名 */
    public static final String CLAIM_USERNAME = "username";

    private final SecretKey secretKey;

    /** 有效期（毫秒） */
    private final long expireMillis;

    public JwtUtil(@Value("${gameasset.jwt.secret}") String secret,
                   @Value("${gameasset.jwt.expire-minutes:480}") long expireMinutes) {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "gameasset.jwt.secret 长度不足：HS256 要求至少 " + MIN_SECRET_BYTES
                            + " 字节，当前 " + keyBytes.length + " 字节");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.expireMillis = expireMinutes * 60L * 1000L;
        log.info("[JWT] 初始化完成，令牌有效期 {} 分钟", expireMinutes);
    }

    /**
     * 签发访问令牌。
     *
     * <p>{@code jti} 是登出黑名单的抓手：登出时把 jti 写进 Redis，
     * 有效期设为令牌剩余寿命，天然随令牌一起过期。</p>
     *
     * @param userId   用户ID，写入 subject
     * @param username 用户名，写入自定义 claim，省一次查库
     */
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireMillis);
        return Jwts.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析并校验令牌（签名、过期时间）。
     *
     * @throws io.jsonwebtoken.ExpiredJwtException 令牌过期
     * @throws io.jsonwebtoken.JwtException        签名错误 / 格式非法
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 从令牌中取用户ID */
    public Long getUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    /**
     * 计算令牌剩余有效毫秒数，用于设置黑名单 TTL。
     * 已过期返回 0，调用方据此跳过写黑名单。
     */
    public long getRemainingMillis(Claims claims) {
        if (claims == null || claims.getExpiration() == null) {
            return 0L;
        }
        long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
        return Math.max(remaining, 0L);
    }

    /** 令牌有效期（秒），登录响应里返回给前端 */
    public long getExpireSeconds() {
        return expireMillis / 1000L;
    }
}
