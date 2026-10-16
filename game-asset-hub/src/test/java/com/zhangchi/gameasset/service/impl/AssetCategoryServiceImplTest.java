package com.zhangchi.gameasset.service.impl;

import com.zhangchi.gameasset.common.enums.ProjectRole;
import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.context.UserContext;
import com.zhangchi.gameasset.dto.AssetCategoryDTO;
import com.zhangchi.gameasset.entity.AssetCategory;
import com.zhangchi.gameasset.mapper.AssetCategoryMapper;
import com.zhangchi.gameasset.mapper.AssetMapper;
import com.zhangchi.gameasset.service.support.AssetActionGuard;
import com.zhangchi.gameasset.service.support.ProjectAccessGuard;
import com.zhangchi.gameasset.service.support.ProjectOverviewCache;
import com.zhangchi.gameasset.support.MybatisPlusTestSupport;
import com.zhangchi.gameasset.vo.AssetCategoryVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资产分类服务测试。
 *
 * <p>重点是文档 9 节点名的边界用例：<b>删除含资产的分类应被拒绝</b>。
 * 这条规则如果漏掉，表现是分类被删、资产还在，但资产编号的前缀
 * （{@code PROP_0001} 里的 {@code PROP}）失去了可解释性，
 * 团队再看到这个编号就不知道它属于哪一类。</p>
 *
 * <p>纯逻辑测试：mock 掉 Mapper，不加载 Spring、不连 MySQL / Redis。</p>
 *
 * @author 张忠驰
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AssetCategoryServiceImpl 资产分类")
class AssetCategoryServiceImplTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long OTHER_PROJECT_ID = 99L;
    private static final Long USER_ID = 1L;
    private static final Long ROOT_ID = 1L;
    private static final Long CHILD_ID = 2L;

    @Mock
    private AssetCategoryMapper categoryMapper;
    @Mock
    private AssetMapper assetMapper;
    @Mock
    private ProjectAccessGuard projectAccessGuard;
    @Mock
    private ProjectOverviewCache overviewCache;

    private AssetCategoryServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleTwoEntities();
        service = new AssetCategoryServiceImpl(categoryMapper, assetMapper,
                new AssetActionGuard(projectAccessGuard), overviewCache);
        UserContext.setUserId(USER_ID);
        UserContext.setUsername("zhangchi");
        UserContext.setProjectRole(ProjectRole.OWNER);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private AssetCategory category(Long id, Long parentId, String name, String code) {
        AssetCategory category = new AssetCategory();
        category.setId(id);
        category.setProjectId(PROJECT_ID);
        category.setParentId(parentId);
        category.setName(name);
        category.setCode(code);
        category.setColor("#409EFF");
        category.setSortOrder(1);
        return category;
    }

    private AssetCategoryDTO dto(String name, String code) {
        AssetCategoryDTO dto = new AssetCategoryDTO();
        dto.setName(name);
        dto.setCode(code);
        return dto;
    }

    // ==================== 删除保护（文档 9 的边界用例） ====================

    @Test
    @DisplayName("分类下有资产 → 拒绝删除，并告知有几个资产")
    void rejectsDeleteWhenCategoryHasAssets() {
        when(categoryMapper.selectOne(any())).thenReturn(category(CHILD_ID, ROOT_ID, "场景道具", "PROP"));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(assetMapper.selectCount(any())).thenReturn(5L);

        assertThatThrownBy(() -> service.delete(PROJECT_ID, CHILD_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("还有 5 个资产")
                .hasMessageContaining("无法删除");

        verify(categoryMapper, never()).delete(any());
        verify(overviewCache, never()).evict(any());
    }

    @Test
    @DisplayName("一级分类下还有子分类 → 拒绝删除")
    void rejectsDeleteWhenCategoryHasChildren() {
        when(categoryMapper.selectOne(any())).thenReturn(category(ROOT_ID, 0L, "模型", "MODEL"));
        when(categoryMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(PROJECT_ID, ROOT_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("还有 2 个子分类");

        // 有子分类时根本不该再去查资产，避免给出误导性的提示
        verify(assetMapper, never()).selectCount(any());
        verify(categoryMapper, never()).delete(any());
    }

    @Test
    @DisplayName("空分类可以删除，删除 SQL 带 project_id")
    void deletesEmptyCategory() {
        when(categoryMapper.selectOne(any())).thenReturn(category(CHILD_ID, ROOT_ID, "空分类", "EMPTY"));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(assetMapper.selectCount(any())).thenReturn(0L);

        service.delete(PROJECT_ID, CHILD_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<AssetCategory>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(categoryMapper).delete(captor.capture());
        assertThat(captor.getValue().getTargetSql()).contains("project_id");
        verify(overviewCache).evict(PROJECT_ID);
    }

    @Test
    @DisplayName("分类不属于本项目 → 403；分类不存在 → 404")
    void distinguishesForbiddenFromNotFound() {
        when(categoryMapper.selectOne(any())).thenReturn(null);
        when(categoryMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(PROJECT_ID, CHILD_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));

        when(categoryMapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> service.delete(PROJECT_ID, CHILD_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(404));
    }

    @Test
    @DisplayName("只读角色（VIEWER）不能删除分类")
    void viewerCannotDelete() {
        UserContext.setProjectRole(ProjectRole.VIEWER);
        when(categoryMapper.selectOne(any())).thenReturn(category(CHILD_ID, ROOT_ID, "x", "X"));

        assertThatThrownBy(() -> service.delete(PROJECT_ID, CHILD_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
    }

    // ==================== 新增与层级约束 ====================

    @Test
    @DisplayName("新增一级分类：parentId 缺省为 0")
    void createsRootCategory() {
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        org.mockito.Mockito.doAnswer(inv -> {
            AssetCategory inserted = inv.getArgument(0);
            inserted.setId(9L);
            return 1;
        }).when(categoryMapper).insert(any(AssetCategory.class));

        AssetCategoryVO vo = service.create(PROJECT_ID, dto("音效", "sfx"));

        assertThat(vo.getParentId()).isZero();
        assertThat(vo.getCode()).as("编码统一转大写").isEqualTo("SFX");
        assertThat(vo.getColor()).isEqualTo("#409EFF");
    }

    @Test
    @DisplayName("最多两级：不能把分类挂在二级分类下")
    void rejectsThirdLevel() {
        AssetCategory child = category(CHILD_ID, ROOT_ID, "场景道具", "PROP");
        when(categoryMapper.selectOne(any())).thenReturn(child);

        AssetCategoryDTO dto = dto("三级分类", "THIRD");
        dto.setParentId(CHILD_ID);

        assertThatThrownBy(() -> service.create(PROJECT_ID, dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多支持两级");

        verify(categoryMapper, never()).insert(any(AssetCategory.class));
    }

    @Test
    @DisplayName("同一项目内编码重复 → 400（并发下的兜底是 uk_project_code 唯一约束）")
    void rejectsDuplicateCodeInSameProject() {
        when(categoryMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(PROJECT_ID, dto("模型", "MODEL")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");

        verify(categoryMapper, never()).insert(any(AssetCategory.class));
    }

    @Test
    @DisplayName("新增分类的编码查重查询带 project_id（不同项目可以有同名编码）")
    void duplicateCheckIsProjectScoped() {
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.selectOne(any())).thenReturn(null);

        try {
            service.create(PROJECT_ID, dto("模型", "MODEL"));
        } catch (BizException ignored) {
            // 主键回填缺失不影响本次断言
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<AssetCategory>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(categoryMapper).selectCount(captor.capture());
        assertThat(captor.getValue().getTargetSql()).contains("project_id");
    }

    @Test
    @DisplayName("改编码时查重条件为「本项目 + 该编码 + 排除自身」，不会撞到自己")
    void updateExcludesSelfFromCodeCheck() {
        when(categoryMapper.selectOne(any())).thenReturn(category(CHILD_ID, ROOT_ID, "场景道具", "PROP"));
        when(categoryMapper.selectCount(any())).thenReturn(0L);

        AssetCategoryVO vo = service.update(PROJECT_ID, CHILD_ID, dto("场景道具（改）", "PROP_NEW"));

        assertThat(vo.getCode()).isEqualTo("PROP_NEW");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<AssetCategory>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(categoryMapper).selectCount(captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertThat(sql).contains("project_id").contains("code");

        // 三个绑定参数 = project_id + code + id(排除自身)。
        // 少了最后一个，改分类名（编码不变）时会误报「编码已存在」
        @SuppressWarnings({"unchecked", "rawtypes"})
        java.util.Map<String, Object> params =
                ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper) captor.getValue())
                        .getParamNameValuePairs();
        assertThat(params).as("查重必须排除自身：project_id + code + id").hasSize(3);
    }

    @Test
    @DisplayName("编码不变时不触发查重（省一次查询，也不会自己撞自己）")
    void updateSkipsCodeCheckWhenCodeUnchanged() {
        when(categoryMapper.selectOne(any())).thenReturn(category(CHILD_ID, ROOT_ID, "场景道具", "PROP"));

        service.update(PROJECT_ID, CHILD_ID, dto("场景道具（改名）", "PROP"));

        verify(categoryMapper, never()).selectCount(any());
    }

    // ==================== 分类树 ====================

    @Test
    @DisplayName("分类树：一级分类的资产数含子分类，二级只算自己")
    void treeAggregatesChildCountsIntoParent() {
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category(ROOT_ID, 0L, "模型", "MODEL"),
                category(CHILD_ID, ROOT_ID, "场景道具", "PROP")));
        when(assetMapper.selectMaps(any())).thenReturn(countRows(Map.of(ROOT_ID, 2L, CHILD_ID, 5L)));

        List<AssetCategoryVO> tree = service.tree(PROJECT_ID);

        assertThat(tree).hasSize(1);
        AssetCategoryVO root = tree.get(0);
        assertThat(root.getName()).isEqualTo("模型");
        assertThat(root.getChildren()).hasSize(1);
        assertThat(root.getChildren().get(0).getAssetCount()).isEqualTo(5);
        assertThat(root.getAssetCount()).as("一级分类 = 自身 2 + 子级 5").isEqualTo(7);
    }

    @Test
    @DisplayName("分类树：没有分类时返回空列表，不抛异常")
    void treeOfEmptyProject() {
        when(categoryMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.tree(PROJECT_ID)).isEmpty();
    }

    @Test
    @DisplayName("父分类缺失的孤儿分类按一级展示，而不是从界面上消失")
    void treeSurfacesOrphansAsRoots() {
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category(CHILD_ID, 999L, "孤儿分类", "ORPHAN")));
        when(assetMapper.selectMaps(any())).thenReturn(countRows(Map.of(CHILD_ID, 3L)));

        List<AssetCategoryVO> tree = service.tree(PROJECT_ID);

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getAssetCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("分类树的查询带 project_id（不会把别的项目的分类混进来）")
    void treeQueryIsProjectScoped() {
        when(categoryMapper.selectList(any())).thenReturn(List.of());
        service.tree(PROJECT_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<AssetCategory>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(categoryMapper).selectList(captor.capture());
        assertThat(captor.getValue().getTargetSql()).contains("project_id");
    }

    @Test
    @DisplayName("新增分类也要写 project_id（不能建到别的项目下）")
    void createIsProjectScoped() {
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        org.mockito.Mockito.doAnswer(inv -> {
            AssetCategory inserted = inv.getArgument(0);
            assertThat(inserted.getProjectId()).isEqualTo(PROJECT_ID);
            inserted.setId(9L);
            return 1;
        }).when(categoryMapper).insert(any(AssetCategory.class));

        service.create(PROJECT_ID, dto("模型", "MODEL"));

        verify(categoryMapper).insert(any(AssetCategory.class));
    }

    /** 把 {@code selectMaps} 的聚合结果伪装成 MyBatis 返回的 Map 列表 */
    private List<Map<String, Object>> countRows(Map<Long, Long> counts) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        counts.forEach((categoryId, count) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("categoryId", categoryId);
            row.put("assetCount", count);
            rows.add(row);
        });
        return rows;
    }
}
