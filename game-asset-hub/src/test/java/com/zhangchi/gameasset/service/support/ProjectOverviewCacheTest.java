package com.zhangchi.gameasset.service.support;

import com.zhangchi.gameasset.service.RedisCacheService;
import com.zhangchi.gameasset.vo.ProjectOverviewVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目概览缓存测试（模块1 的类，模块2 修改了 {@code put} 的口径）。
 *
 * <p>模块2 / 模块3 在这里各加了一步：写入缓存前先用 {@link AssetOverviewSupport}
 * 与 {@link TaskOverviewSupport} 补齐资产与任务指标。本测试盯住的就是这几步的性质：</p>
 * <ol>
 *   <li>写缓存<b>一定</b>发生在补齐之后——顺序反了就退化成
 *       「首次打开指标全是 0，刷新一下才对」；</li>
 *   <li>补齐是<b>原地修改</b>同一个对象，因此返回给前端的与缓存里的是同一份数据；</li>
 *   <li>入参为 null 时不炸，也不去打扰资产聚合。</li>
 * </ol>
 *
 * <p>纯逻辑测试：mock 掉 Redis 门面与资产聚合，不加载 Spring、不连 Redis / MySQL。</p>
 *
 * @author 张忠驰
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProjectOverviewCache 概览缓存与模块2 资产指标补齐")
class ProjectOverviewCacheTest {

    private static final Long PROJECT_ID = 7L;

    @Mock
    private RedisCacheService redisCacheService;
    @Mock
    private AssetOverviewSupport assetOverviewSupport;
    /** 模块3 接入的任务指标补齐（由 TaskOverviewSupportTest 覆盖其自身逻辑） */
    @Mock
    private TaskOverviewSupport taskOverviewSupport;
    /** 模块4 接入的统计图表缓存：由概览缓存的失效通道连带失效 */
    @Mock
    private ProjectStatCache projectStatCache;

    private ProjectOverviewCache cache;

    @BeforeEach
    void setUp() {
        cache = new ProjectOverviewCache(redisCacheService, assetOverviewSupport,
                taskOverviewSupport, projectStatCache);
    }

    @Test
    @DisplayName("写入缓存前先补齐资产与任务指标，且「补齐」发生在「写缓存」之前")
    void fillsMetricsBeforeWritingCache() {
        ProjectOverviewVO vo = new ProjectOverviewVO();
        vo.setProjectId(PROJECT_ID);

        cache.put(PROJECT_ID, vo);

        // 顺序反了就退化成「首次打开指标全是 0，刷新一下才对」：
        // 返回给调用方的是 put 之后被原地补齐的对象，而缓存里存的还是那一份的顺序
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
                assetOverviewSupport, taskOverviewSupport, redisCacheService);
        inOrder.verify(assetOverviewSupport).fill(vo, PROJECT_ID);
        inOrder.verify(taskOverviewSupport).fill(vo, PROJECT_ID);
        inOrder.verify(redisCacheService).set(eq("gah:project:overview:7"), eq(vo), any(Duration.class));
    }

    @Test
    @DisplayName("补齐是原地修改：缓存里存的与调用方持有的必须是同一个对象")
    void fillMutatesTheSameInstance() {
        ProjectOverviewVO vo = new ProjectOverviewVO();
        // 模拟 AssetOverviewSupport 原地填值
        org.mockito.Mockito.doAnswer(invocation -> {
            ProjectOverviewVO target = invocation.getArgument(0);
            target.setAssetTotal(148);
            target.setAssetApproved(96);
            return null;
        }).when(assetOverviewSupport).fill(any(), any());

        cache.put(PROJECT_ID, vo);

        assertThat(vo.getAssetTotal()).isEqualTo(148);
        assertThat(vo.getAssetApproved()).isEqualTo(96);
    }

    @Test
    @DisplayName("TTL 固定 10 分钟（文档 5.9）")
    void usesTenMinuteTtl() {
        cache.put(PROJECT_ID, new ProjectOverviewVO());

        verify(redisCacheService).set(anyString(), any(), eq(Duration.ofMinutes(10)));
        assertThat(ProjectOverviewCache.TTL).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("projectId 或 VO 为 null 时不去打扰资产与任务聚合，也不写缓存")
    void toleratesNullArguments() {
        cache.put(null, new ProjectOverviewVO());
        cache.put(PROJECT_ID, null);

        verify(assetOverviewSupport, never()).fill(any(), any());
        verify(taskOverviewSupport, never()).fill(any(), any());
    }

    @Test
    @DisplayName("读缓存：未命中返回 null，由上层直查数据库")
    void getReturnsNullOnMiss() {
        when(redisCacheService.get(anyString(), eq(ProjectOverviewVO.class))).thenReturn(null);

        assertThat(cache.get(PROJECT_ID)).isNull();
    }

    @Test
    @DisplayName("读缓存：命中时原样返回")
    void getReturnsCachedValue() {
        ProjectOverviewVO cached = new ProjectOverviewVO();
        cached.setAssetTotal(148);
        when(redisCacheService.get("gah:project:overview:7", ProjectOverviewVO.class)).thenReturn(cached);

        assertThat(cache.get(PROJECT_ID)).isSameAs(cached);
    }

    @Test
    @DisplayName("主动失效：删除对应的 key；资产写操作就是靠它让概览指标及时更新")
    void evictDeletesTheKey() {
        cache.evict(PROJECT_ID);

        verify(redisCacheService).delete("gah:project:overview:7");
    }

    @Test
    @DisplayName("失效概览时会连带失效模块4 的统计图表缓存（所有写路径都只调这一个入口）")
    void evictAlsoEvictsProjectStats() {
        cache.evict(PROJECT_ID);

        verify(projectStatCache).evict(PROJECT_ID);
    }

    @Test
    @DisplayName("失效时 projectId 为 null 直接返回，不产生无意义的 Redis 调用")
    void evictIgnoresNullProjectId() {
        cache.evict(null);

        verify(redisCacheService, never()).delete(anyString());
        verify(projectStatCache, never()).evict(any());
    }
}
