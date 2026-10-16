package com.zhangchi.gameasset.service.support;

import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.service.RedisCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资产版本上传的分布式锁（文档 5.2 的并发控制）。
 *
 * <p>锁本身不出现在业务结果里，所以它的失败方式全是「无声的」：
 * 该拒的没拒 → 版本号冲突；该放的没放 → 后续上传全部 409。
 * 因此这里把「抢到执行、抢不到拒绝、无论如何都释放」三件事各测一遍。</p>
 *
 * <p>Mockito mock 掉 Redis 门面，不加载 Spring、不连 Redis；
 * 等待时间取 1ms，抢不到锁时不会真的睡 3 秒。</p>
 *
 * @author 张忠驰
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AssetVersionLock 版本上传分布式锁")
class AssetVersionLockTest {

    private static final Long ASSET_ID = 128L;

    @Mock
    private RedisCacheService redisCacheService;

    /** waitMillis = 1：抢不到就立刻返回，测试不会因为重试而变慢 */
    private AssetVersionLock lock() {
        return new AssetVersionLock(redisCacheService, 1L, 120L);
    }

    @Test
    @DisplayName("抢到锁时执行临界区，并把返回值透传出去")
    void executesActionWhenLockAcquired() {
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        String result = lock().execute(ASSET_ID, () -> "V5");

        assertThat(result).isEqualTo("V5");
    }

    @Test
    @DisplayName("锁的 key 按资产维度拼装，不同资产互不阻塞")
    void lockKeyIsPerAsset() {
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        lock().execute(ASSET_ID, () -> null);
        lock().execute(999L, () -> null);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(redisCacheService, org.mockito.Mockito.times(2))
                .setIfAbsent(keys.capture(), anyString(), any());
        assertThat(keys.getAllValues()).containsExactly(
                "gah:lock:asset:upload:128", "gah:lock:asset:upload:999");
    }

    @Test
    @DisplayName("锁带 TTL，进程崩溃时锁会自然过期而不是永久阻塞该资产")
    void lockIsAcquiredWithTtl() {
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        lock().execute(ASSET_ID, () -> null);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redisCacheService).setIfAbsent(anyString(), anyString(), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("抢不到锁时抛 409，且不执行临界区（避免写出重复版本号）")
    void rejectsWhenLockNotAcquired() {
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        AtomicBoolean executed = new AtomicBoolean(false);

        assertThatThrownBy(() -> lock().execute(ASSET_ID, () -> {
            executed.set(true);
            return null;
        }))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("正在上传新版本")
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(409));

        assertThat(executed).as("没抢到锁就绝不能进入临界区").isFalse();
    }

    @Test
    @DisplayName("执行完成后释放锁，且释放时带上自己写入的 token")
    void releasesLockWithOwnTokenAfterSuccess() {
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        lock().execute(ASSET_ID, () -> "ok");

        ArgumentCaptor<String> acquired = ArgumentCaptor.forClass(String.class);
        verify(redisCacheService).setIfAbsent(anyString(), acquired.capture(), any());
        // 释放时必须比较 token：否则会把别人超时后重新抢到的锁误删
        verify(redisCacheService).releaseIfValueMatches(
                eq("gah:lock:asset:upload:128"), eq(acquired.getValue()));
    }

    @Test
    @DisplayName("临界区抛异常也要释放锁，否则这个资产会被永久锁死")
    void releasesLockEvenWhenActionFails() {
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        assertThatThrownBy(() -> lock().execute(ASSET_ID, () -> {
            throw BizException.badRequest("版本号冲突");
        })).isInstanceOf(BizException.class).hasMessageContaining("版本号冲突");

        verify(redisCacheService).releaseIfValueMatches(anyString(), anyString());
    }

    @Test
    @DisplayName("抢锁失败时不释放锁（锁是别人的，误删会让第三个人同时进入）")
    void doesNotReleaseWhenAcquisitionFailed() {
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> lock().execute(ASSET_ID, () -> null))
                .isInstanceOf(BizException.class);

        verify(redisCacheService, never()).releaseIfValueMatches(anyString(), anyString());
    }

    @Test
    @DisplayName("每次执行的 token 都不同，两个请求不会互相释放对方的锁")
    void tokenIsUniquePerInvocation() {
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        lock().execute(ASSET_ID, () -> null);
        lock().execute(ASSET_ID, () -> null);

        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(redisCacheService, org.mockito.Mockito.times(2))
                .setIfAbsent(anyString(), tokens.capture(), any());
        assertThat(tokens.getAllValues().get(0)).isNotEqualTo(tokens.getAllValues().get(1));
    }
}
