package com.demo.gameasset.service.support;

import com.demo.gameasset.common.constant.RedisKeys;
import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.common.result.ResultCode;
import com.demo.gameasset.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 资产版本上传的分布式锁（文档 5.2 的并发控制）。
 *
 * <p><b>要解决的问题</b>：两个请求同时给同一资产上传新版本时，
 * 两边都读到 {@code maxVersionNo = 3}，都想写 V4。
 * 数据库唯一约束 {@code uk_asset_version(asset_id, version_no)} 会让其中一条失败
 * ——数据不会错，但用户看到的是「上传失败」，得重传一次几百 MB 的文件。</p>
 *
 * <p>加锁后两个请求串行执行，第二个请求读到的是 4，自然写 V5。
 * <b>唯一约束仍然保留</b>，作为锁失效（Redis 抖动、锁 TTL 提前到期）时的兜底：
 * 锁是性能优化，唯一约束才是正确性保证。两层都要有。</p>
 *
 * <p><b>锁的粒度</b>是单个资产而非整个项目，见 {@link RedisKeys#assetUploadLock}。</p>
 *
 * @author 管理员
 */
@Slf4j
@Component
public class AssetVersionLock {

    private final RedisCacheService redisCacheService;

    /** 抢锁的最长等待时间：等太久不如直接让用户重试 */
    private final long waitMillis;

    /** 锁的持有上限。上传大文件可能很久，取一个远大于单次上传耗时的值 */
    private final long ttlSeconds;

    /** 重试间隔 */
    private static final long RETRY_INTERVAL_MILLIS = 100L;

    public AssetVersionLock(
            RedisCacheService redisCacheService,
            @Value("${gameasset.asset.lock-wait-millis:3000}") long waitMillis,
            @Value("${gameasset.asset.lock-ttl-seconds:120}") long ttlSeconds) {
        this.redisCacheService = redisCacheService;
        this.waitMillis = waitMillis;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 在锁的保护下执行动作。
     *
     * @param assetId 目标资产
     * @param action  临界区逻辑（读最大版本号 → 清 current → 插入新版本）
     * @return 动作的返回值
     * @throws BizException 409 抢锁超时（并发的另一次上传还占着锁）
     */
    public <T> T execute(Long assetId, Supplier<T> action) {
        String key = RedisKeys.assetUploadLock(assetId);
        // token 取 UUID：释放锁时用它确认「这把锁还是我加的」，
        // 避免把别人在超时后重新抢到的锁误删（见 RedisCacheService#releaseIfValueMatches）
        String token = UUID.randomUUID().toString();

        if (!tryAcquire(key, token)) {
            log.warn("[版本锁] 等待超时，该资产正在被其他请求上传 assetId={}", assetId);
            throw new BizException(ResultCode.CONFLICT,
                    "该资产正在上传新版本，请稍后重试");
        }

        try {
            return action.get();
        } finally {
            boolean released = redisCacheService.releaseIfValueMatches(key, token);
            if (!released) {
                // 走到这里通常是业务耗时超过了 TTL，锁已自动过期。
                // 记 warn 而不是 error：本次操作本身是对的，只是并发保护出现了窗口。
                log.warn("[版本锁] 锁已被自动释放或易主，可能存在并发窗口 assetId={}", assetId);
            }
        }
    }

    /** 在 waitMillis 内轮询抢锁，成功返回 true */
    private boolean tryAcquire(String key, String token) {
        long deadline = System.currentTimeMillis() + waitMillis;
        do {
            if (redisCacheService.setIfAbsent(key, token, Duration.ofSeconds(ttlSeconds))) {
                log.debug("[版本锁] 已获得 lock={}", key);
                return true;
            }
            try {
                Thread.sleep(RETRY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                // 恢复中断标记后放弃抢锁：线程已被要求停止，继续等待没有意义
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.currentTimeMillis() < deadline);
        return false;
    }
}
