package com.demo.gameasset.interceptor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 拦截器专用的极简 Redis 访问入口。
 *
 * <p>为什么不让拦截器直接依赖 {@code RedisCacheService}：
 * 拦截器在 DispatcherServlet 的最外层，而 {@code RedisCacheService} 属于 Service 层；
 * 更实际的考虑是——这个接口只有一个语义动作「这个 jti 是否已被拉黑」，
 * 单独抽出来便于在单元测试里替换，不必为了测拦截器去构造整个缓存服务。</p>
 *
 * @author 管理员
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheGateway {

    private final StringRedisTemplate redisTemplate;

    /**
     * 判断令牌是否在登出黑名单中。
     *
     * <p>Redis 不可用时返回 false（视为未登出）：令牌自身仍有签名校验与过期时间，
     * 而把全部在线用户踢下线的影响面更大。异常会打 error 日志便于运维发现。</p>
     */
    public boolean isBlacklisted(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.error("[鉴权] 黑名单查询失败，降级为按未登出处理 key={}", key, e);
            return false;
        }
    }
}
