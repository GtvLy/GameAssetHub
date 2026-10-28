package com.demo.gameasset.service.support;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.demo.gameasset.entity.Asset;
import com.demo.gameasset.mapper.AssetCategoryMapper;
import com.demo.gameasset.mapper.AssetMapper;
import com.demo.gameasset.mapper.AssetVersionMapper;
import com.demo.gameasset.mapper.SysUserMapper;
import com.demo.gameasset.support.MybatisPlusTestSupport;
import com.demo.gameasset.vo.ProjectOverviewVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目概览的资产指标聚合（模块2 对模块1 的交付）。
 *
 * <p>这几个数字是项目概览首页上最显眼的指标，算错不会有任何报错：
 * 完成率超过 100%、或者把别的项目的资产算进来，用户只会觉得「这数不对」
 * 但说不出哪里不对。所以这里把口径与除零边界都钉住。</p>
 *
 * <p>纯逻辑测试：mock 掉 Mapper，不加载 Spring、不连 MySQL / Redis。</p>
 *
 * @author 管理员
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AssetOverviewSupport 概览资产指标")
class AssetOverviewSupportTest {

    private static final Long PROJECT_ID = 7L;

    @Mock
    private AssetMapper assetMapper;
    @Mock
    private AssetCategoryMapper categoryMapper;
    @Mock
    private AssetVersionMapper assetVersionMapper;
    @Mock
    private SysUserMapper sysUserMapper;

    private AssetOverviewSupport support;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleTwoEntities();
        support = new AssetOverviewSupport(assetMapper, categoryMapper, assetVersionMapper, sysUserMapper);
    }

    // ==================== 完成率口径 ====================

    @ParameterizedTest(name = "{0} / {1} → {2}%")
    @CsvSource({
            "96, 148, 64.9",
            "1, 3, 33.3",
            "2, 3, 66.7",
            "1, 1, 100.0",
            "0, 10, 0.0",
            "10, 10, 100.0"
    })
    @DisplayName("完成率 = 已通过 / 总数 × 100，保留一位小数（四舍五入）")
    void completionRateIsOneDecimal(int approved, int total, double expected) {
        assertThat(AssetOverviewSupport.completionRate(approved, total)).isEqualTo(expected);
    }

    @Test
    @DisplayName("空项目（总数 0）完成率为 0，不做除法（文档 9 节的除零边界）")
    void completionRateHandlesEmptyProject() {
        assertThat(AssetOverviewSupport.completionRate(0, 0)).isZero();
        assertThat(AssetOverviewSupport.completionRate(5, 0)).as("总数不可能为 0 却有已通过，但仍不能崩").isZero();
        assertThat(AssetOverviewSupport.completionRate(0, -1)).isZero();
    }

    // ==================== 指标填充 ====================

    @Test
    @DisplayName("fill 填齐总数 / 已通过 / 待审核 / 完成率 / 最近资产")
    void fillsAllAssetMetrics() {
        when(assetMapper.selectCount(any())).thenReturn(148L, 96L, 18L);
        when(assetMapper.selectList(any())).thenReturn(List.of());
        ProjectOverviewVO vo = new ProjectOverviewVO();
        vo.setProjectId(PROJECT_ID);
        vo.setProjectName("星港计划");
        vo.setMemberCount(6);

        support.fill(vo, PROJECT_ID);

        assertThat(vo.getAssetTotal()).isEqualTo(148);
        assertThat(vo.getAssetApproved()).isEqualTo(96);
        assertThat(vo.getAssetPending()).isEqualTo(18);
        assertThat(vo.getAssetCompletionRate()).isEqualTo(64.9);
        assertThat(vo.getRecentAssets()).isEmpty();
        assertThat(vo.getMemberCount()).as("模块1 填好的字段不受影响").isEqualTo(6);
        assertThat(vo.getProjectName()).isEqualTo("星港计划");
    }

    @Test
    @DisplayName("空项目：三个计数都是 0，完成率 0.0，不抛异常")
    void handlesProjectWithoutAssets() {
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(assetMapper.selectList(any())).thenReturn(List.of());
        ProjectOverviewVO vo = new ProjectOverviewVO();

        support.fill(vo, PROJECT_ID);

        assertThat(vo.getAssetTotal()).isZero();
        assertThat(vo.getAssetApproved()).isZero();
        assertThat(vo.getAssetPending()).isZero();
        assertThat(vo.getAssetCompletionRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("统计 SQL 全部带 project_id，不会把别的项目的资产算进来")
    void countsAreProjectScoped() {
        when(assetMapper.selectCount(any())).thenReturn(1L);
        when(assetMapper.selectList(any())).thenReturn(List.of());

        support.fill(new ProjectOverviewVO(), PROJECT_ID);

        ArgumentCaptor<Wrapper<Asset>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(assetMapper, org.mockito.Mockito.times(3)).selectCount(captor.capture());
        for (Wrapper<Asset> wrapper : captor.getAllValues()) {
            assertThat(wrapper.getTargetSql()).contains("project_id");
        }

        ArgumentCaptor<Wrapper<Asset>> recentCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(assetMapper).selectList(recentCaptor.capture());
        assertThat(recentCaptor.getValue().getTargetSql()).contains("project_id");
    }

    @Test
    @DisplayName("三个计数分别按「全部 / APPROVED / PENDING」统计")
    void countsUseCorrectStatuses() {
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(assetMapper.selectList(any())).thenReturn(List.of());

        support.fill(new ProjectOverviewVO(), PROJECT_ID);

        ArgumentCaptor<Wrapper<Asset>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(assetMapper, org.mockito.Mockito.times(3)).selectCount(captor.capture());
        List<String> sqls = captor.getAllValues().stream().map(Wrapper::getTargetSql).toList();
        // 第一次不带 status（总数），后两次分别带 APPROVED / PENDING 的绑定参数
        assertThat(sqls.get(0)).doesNotContain("status");
        assertThat(sqls.get(1)).contains("status");
        assertThat(sqls.get(2)).contains("status");
    }

    @Test
    @DisplayName("countByStatus 容忍 Mapper 返回 null（异常降级为 0，不让概览接口 500）")
    void countToleratesNullResult() {
        when(assetMapper.selectCount(any())).thenReturn(null);

        assertThat(support.countByStatus(PROJECT_ID, "APPROVED")).isZero();
    }

    @Test
    @DisplayName("最近资产：没有资产时返回空列表")
    void recentAssetsEmptyWhenNoAssets() {
        when(assetMapper.selectList(any())).thenReturn(List.of());

        assertThat(support.recentAssets(PROJECT_ID)).isEmpty();
    }

    @Test
    @DisplayName("最近资产：返回资产速览，且批量查询未命中时不会 NPE")
    void recentAssetsBuildsBriefViews() {
        Asset asset = new Asset();
        asset.setId(1L);
        asset.setProjectId(PROJECT_ID);
        asset.setCategoryId(3L);
        asset.setAssetNo("PROP_0001");
        asset.setName("木箱 A 型");
        asset.setStatus("APPROVED");
        asset.setPriority("P1");
        asset.setOwnerId(2L);
        asset.setVersionCount(3);
        asset.setCurrentVersionId(103L);
        asset.setUpdateTime(LocalDateTime.now());
        when(assetMapper.selectList(any())).thenReturn(List.of(asset));

        List<Object> recent = support.recentAssets(PROJECT_ID);

        assertThat(recent).hasSize(1);
        assertThat(recent.get(0)).isInstanceOf(com.demo.gameasset.vo.AssetVO.class);
        com.demo.gameasset.vo.AssetVO vo = (com.demo.gameasset.vo.AssetVO) recent.get(0);
        assertThat(vo.getAssetNo()).isEqualTo("PROP_0001");
        assertThat(vo.getStatusLabel()).isEqualTo("已通过");
        assertThat(vo.getNextStatuses()).as("概览速览不需要流转选项").isEmpty();
    }

    @Test
    @DisplayName("fill 对 null 入参直接返回，不查库")
    void fillIgnoresNullArguments() {
        support.fill(null, PROJECT_ID);
        support.fill(new ProjectOverviewVO(), null);

        verify(assetMapper, org.mockito.Mockito.never()).selectCount(any());
    }

    @Test
    @DisplayName("不做 N+1：分类/版本/用户都是批量查询，条数不随资产数增长")
    void usesBatchQueriesInsteadOfNPlusOne() {
        Asset first = new Asset();
        first.setId(1L);
        first.setProjectId(PROJECT_ID);
        first.setCategoryId(3L);
        first.setOwnerId(2L);
        first.setCurrentVersionId(103L);
        Asset second = new Asset();
        second.setId(2L);
        second.setProjectId(PROJECT_ID);
        second.setCategoryId(3L);
        second.setOwnerId(1L);
        second.setCurrentVersionId(104L);
        when(assetMapper.selectList(any())).thenReturn(List.of(first, second));

        support.recentAssets(PROJECT_ID);

        // 分类一次、版本一次、上传人一次、负责人一次 —— 与资产条数无关
        verify(categoryMapper, org.mockito.Mockito.times(1)).selectBatchIds(any());
        verify(assetVersionMapper, org.mockito.Mockito.times(1)).selectBatchIds(any());
        verify(sysUserMapper, org.mockito.Mockito.atMost(2)).selectBatchIds(any());
    }
}
