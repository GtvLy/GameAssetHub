package com.zhangchi.gameasset.service.support;

import com.zhangchi.gameasset.mapper.AssetMapper;
import com.zhangchi.gameasset.service.RedisCacheService;
import com.zhangchi.gameasset.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资产编号生成（文档 3.3 第 1 点）。
 *
 * <p>格式是 {@code {分类code}_{序号}}，如 {@code CHR_SM_0001}。
 * 编号会被团队成员口述引用，所以格式的稳定性比什么都重要——
 * 位数少一位、分隔符换了，沟通时就会对不上。</p>
 *
 * <p>纯逻辑测试：mock 掉 Redis 与 Mapper，不加载 Spring、不连 MySQL / Redis。</p>
 *
 * @author 张忠驰
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AssetNoGenerator 资产编号生成")
class AssetNoGeneratorTest {

    private static final Long PROJECT_ID = 7L;
    private static final String CODE = "CHR_SM";

    @Mock
    private RedisCacheService redisCacheService;
    @Mock
    private AssetMapper assetMapper;

    private AssetNoGenerator generator;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleTwoEntities();
        generator = new AssetNoGenerator(redisCacheService, assetMapper);
    }

    // ==================== 格式 ====================

    @ParameterizedTest(name = "{0} + 序号 {1} → {2}")
    @CsvSource({
            "CHR_SM, 1,    CHR_SM_0001",
            "CHR_SM, 42,   CHR_SM_0042",
            "PROP,   999,  PROP_0999",
            "TEX,    1000, TEX_1000",
            "SFX,    9999, SFX_9999"
    })
    @DisplayName("格式为「分类编码_四位序号」")
    void formatsAsCodeUnderscoreSequence(String code, long seq, String expected) {
        assertThat(AssetNoGenerator.format(code, seq)).isEqualTo(expected);
    }

    @Test
    @DisplayName("序号超过 9999 时自然进位到 5 位，不截断也不回绕")
    void growsBeyondFourDigits() {
        assertThat(AssetNoGenerator.format("PROP", 12345)).isEqualTo("PROP_12345");
    }

    @Test
    @DisplayName("序号最小为 1（0 或负数一律按 1 处理，避免出现 PROP_0000）")
    void clampsSequenceToAtLeastOne() {
        assertThat(AssetNoGenerator.format("PROP", 0)).isEqualTo("PROP_0001");
        assertThat(AssetNoGenerator.format("PROP", -5)).isEqualTo("PROP_0001");
    }

    // ==================== 序号来源 ====================

    @Test
    @DisplayName("正常路径用 Redis 原子自增，且 key 按「项目 + 分类编码」隔离")
    void usesRedisIncrementKeyedByProjectAndCode() {
        when(redisCacheService.increment("gah:asset:no:7:CHR_SM")).thenReturn(1L);

        assertThat(generator.next(PROJECT_ID, CODE)).isEqualTo("CHR_SM_0001");
        verify(redisCacheService).increment("gah:asset:no:7:CHR_SM");
    }

    @Test
    @DisplayName("连续调用返回递增编号，不会重复")
    void producesIncreasingNumbers() {
        when(redisCacheService.increment(any())).thenReturn(1L, 2L, 3L);

        assertThat(generator.next(PROJECT_ID, CODE)).isEqualTo("CHR_SM_0001");
        assertThat(generator.next(PROJECT_ID, CODE)).isEqualTo("CHR_SM_0002");
        assertThat(generator.next(PROJECT_ID, CODE)).isEqualTo("CHR_SM_0003");
    }

    @Test
    @DisplayName("不同分类使用各自的序列，编号空间互不干扰")
    void differentCategoriesUseSeparateSequences() {
        when(redisCacheService.increment("gah:asset:no:7:CHR_SM")).thenReturn(3L);
        when(redisCacheService.increment("gah:asset:no:7:PROP")).thenReturn(7L);

        assertThat(generator.next(PROJECT_ID, "CHR_SM")).isEqualTo("CHR_SM_0003");
        assertThat(generator.next(PROJECT_ID, "PROP")).isEqualTo("PROP_0007");
    }

    // ==================== Redis 不可用时的兜底 ====================

    @Test
    @DisplayName("Redis 自增失败时退化为查库兜底：已有 4 条则生成第 5 号")
    void fallsBackToDatabaseCountWhenRedisIsDown() {
        when(redisCacheService.increment(any())).thenReturn(null);
        when(assetMapper.selectCount(any())).thenReturn(4L);

        assertThat(generator.next(PROJECT_ID, CODE)).isEqualTo("CHR_SM_0005");
        verify(assetMapper).selectCount(any());
    }

    @Test
    @DisplayName("兜底时库里一条都没有则从 1 开始")
    void fallbackStartsFromOneOnEmptyTable() {
        when(redisCacheService.increment(any())).thenReturn(null);
        when(assetMapper.selectCount(any())).thenReturn(0L);

        assertThat(generator.next(PROJECT_ID, CODE)).isEqualTo("CHR_SM_0001");
    }

    @Test
    @DisplayName("兜底查询也带 project_id，不会把别的项目的资产数算进来")
    void fallbackQueryIsScopedByProject() {
        when(redisCacheService.increment(any())).thenReturn(null);
        when(assetMapper.selectCount(any())).thenReturn(2L);

        generator.next(PROJECT_ID, CODE);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<com.zhangchi.gameasset.entity.Asset>>
                captor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(assetMapper).selectCount(captor.capture());
        assertThat(captor.getValue().getTargetSql()).contains("project_id");
        assertThat(captor.getValue().getTargetSql()).contains("asset_no");
    }

    @Test
    @DisplayName("兜底时 selectCount 返回 null（Mapper 异常）也当作 0 处理，不抛 NPE")
    void fallbackToleratesNullCount() {
        when(redisCacheService.increment(any())).thenReturn(null);
        when(assetMapper.selectCount(any())).thenReturn(null);

        assertThat(generator.next(PROJECT_ID, CODE)).isEqualTo("CHR_SM_0001");
    }
}
