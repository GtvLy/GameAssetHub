package com.zhangchi.gameasset.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collections;

/**
 * Redis 缓存门面。
 *
 * <p>刻意基于 {@link StringRedisTemplate} + 显式 {@link ObjectMapper}
 * 做 JSON 序列化，而不是 {@code RedisTemplate<String, Object>} +
 * {@code GenericJackson2JsonRedisSerializer}：后者依赖默认类型信息，
 * 遇到 {@code LocalDateTime} 或泛型集合时反序列化经常抛
 * {@code ClassCastException} / {@code InvalidDefinitionException}，
 * 排查成本远高于自己写两行 JSON。这样读出来的类型完全由调用方指定，行为可预期。</p>
 *
 * <p><b>缓存不可用时的策略</b>：读写异常只记日志并当作「未命中」返回，
 * 不向上抛。项目概览是展示型数据，Redis 挂掉时退化为直查数据库，
 * 而不是让整个概览接口 500。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 读缓存并反序列化为指定类型。
     *
     * @return 命中返回对象；未命中、类型不匹配或 Redis 异常返回 null
     */
    public <T> T get(String key, Class<T> type) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("[缓存] 读取失败并降级为直查数据库 key={} 原因={}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 读缓存并反序列化为<b>泛型类型</b>（模块4 新增）。
     *
     * <p>只传 {@code Class} 时无法表达 {@code List<AssetCategoryStatVO>}——
     * 泛型在运行期被擦除，Jackson 会把它反序列化成 {@code List<LinkedHashMap>}，
     * 调用方一取元素就 {@code ClassCastException}。凡是缓存集合类型的
     * 数据都必须走这个重载。</p>
     *
     * <p>失败策略与 {@link #get(String, Class)} 完全一致：异常只记日志、
     * 当作未命中返回 null，让调用方退化为直查数据库。</p>
     */
    public <T> T get(String key, TypeReference<T> type) {
        if (!StringUtils.hasText(key) || type == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("[缓存] 读取失败并降级为直查数据库 key={} 原因={}", key, e.getMessage());
            return null;
        }
    }

    /** 写缓存，ttl 为 null 时不设过期（谨慎使用） */    public void set(String key, Object value, Duration ttl) {
        if (!StringUtils.hasText(key) || value == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            if (ttl == null) {
                redisTemplate.opsForValue().set(key, json);
            } else {
                redisTemplate.opsForValue().set(key, json, ttl);
            }
        } catch (Exception e) {
            log.warn("[缓存] 写入失败（不影响业务）key={} 原因={}", key, e.getMessage());
        }
    }

    /** 删除缓存：写操作后主动失效，是「缓存 + 主动失效」策略的落点 */
    public void delete(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        try {
            redisTemplate.delete(key);
            log.debug("[缓存] 已主动失效 key={}", key);
        } catch (Exception e) {
            log.warn("[缓存] 删除失败 key={} 原因={}", key, e.getMessage());
        }
    }

    /**
     * 写入带 TTL 的字符串值（令牌黑名单用）。
     *
     * <p>黑名单的<b>读取</b>不在这里，而在 {@code interceptor.RedisCacheGateway}：
     * 拦截器位于 DispatcherServlet 最外层，不让它依赖 Service 层。
     * 刻意只保留一条读取路径——两处各写一份「怎么判断被拉黑」，
     * 早晚会有一处忘了同步，那就是登出失效的漏洞。</p>
     */
    public void setString(String key, String value, Duration ttl) {
        if (!StringUtils.hasText(key) || value == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.error("[缓存] 写入字符串失败 key={}", key, e);
        }
    }

    // ==================== 原子操作（模块2：编号自增 + 分布式锁） ====================

    /**
     * 原子自增并返回新值（Redis {@code INCR}）。
     *
     * <p>放在这个门面里而不是让模块2 直接注入 {@code StringRedisTemplate}：
     * 本项目所有 Redis 访问都从这一处出去，key 前缀、异常降级策略、
     * 序列化方式才不会出现「某个模块自己写了一套」的分叉。</p>
     *
     * <p><b>失败策略与缓存读写刻意相反</b>：缓存失败可以静默降级为直查数据库，
     * 但编号自增失败不能假装成功——返回 {@code null} 让调用方走
     * 「查库兜底 + 唯一约束重试」的路径，而不是稀里糊涂拿到一个重复编号。</p>
     *
     * @return 自增后的值；Redis 不可用时返回 null
     */
    public Long increment(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            log.warn("[Redis] 自增失败 key={} 原因={}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 仅当 key 不存在时写入（{@code SET key value NX EX ttl}），即抢占一把分布式锁。
     *
     * @return 抢到返回 true；已被占用或 Redis 不可用返回 false
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        if (!StringUtils.hasText(key) || value == null) {
            return false;
        }
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, value, ttl);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("[Redis] 加锁失败 key={} 原因={}", key, e.getMessage());
            return false;
        }
    }

    /**
     * 释放分布式锁：<b>仅当值仍然是自己写入的那个 token 时</b>才删除。
     *
     * <p>必须先比较再删除，否则会出现经典的锁误删：</p>
     * <pre>
     *   A 拿锁 → A 的业务卡住超时 → 锁 TTL 到期自动释放
     *   → B 拿到同一把锁 → A 终于跑完，直接 DEL → 把 B 的锁删了
     *   → C 立刻又拿到锁，B 与 C 同时进入临界区
     * </pre>
     *
     * <p>比较与删除用 Lua 脚本在一次 Redis 调用里完成。
     * 若拆成 {@code GET} + {@code DEL} 两步，两步之间同样存在上述竞态——
     * 这类 bug 只在并发压测时偶发，靠人工测试几乎抓不到。</p>
     *
     * @return 真正删除了返回 true；token 不匹配（锁已易主）或 Redis 不可用返回 false
     */
    public boolean releaseIfValueMatches(String key, String token) {
        if (!StringUtils.hasText(key) || token == null) {
            return false;
        }
        try {
            Long deleted = redisTemplate.execute(
                    RELEASE_LOCK_SCRIPT, Collections.singletonList(key), token);
            return deleted != null && deleted > 0;
        } catch (Exception e) {
            log.warn("[Redis] 解锁失败 key={} 原因={}", key, e.getMessage());
            return false;
        }
    }

    /** 比较并删除的原子脚本：值等于传入 token 才 DEL，返回 1；否则返回 0 */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
}
