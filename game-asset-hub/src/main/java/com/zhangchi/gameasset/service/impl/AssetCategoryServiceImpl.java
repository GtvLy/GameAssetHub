package com.zhangchi.gameasset.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhangchi.gameasset.common.enums.Permission;
import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.convert.AssetCategoryConverter;
import com.zhangchi.gameasset.dto.AssetCategoryDTO;
import com.zhangchi.gameasset.entity.Asset;
import com.zhangchi.gameasset.entity.AssetCategory;
import com.zhangchi.gameasset.mapper.AssetCategoryMapper;
import com.zhangchi.gameasset.mapper.AssetMapper;
import com.zhangchi.gameasset.service.AssetCategoryService;
import com.zhangchi.gameasset.service.support.AssetActionGuard;
import com.zhangchi.gameasset.service.support.ProjectOverviewCache;
import com.zhangchi.gameasset.vo.AssetCategoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产分类服务实现。
 *
 * @author 张忠驰
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetCategoryServiceImpl implements AssetCategoryService {

    private final AssetCategoryMapper categoryMapper;
    private final AssetMapper assetMapper;
    private final AssetActionGuard actionGuard;
    private final ProjectOverviewCache overviewCache;

    @Override
    public List<AssetCategoryVO> tree(Long projectId) {
        List<AssetCategory> categories = categoryMapper.selectList(
                Wrappers.<AssetCategory>lambdaQuery()
                        .eq(AssetCategory::getProjectId, projectId)
                        .orderByAsc(AssetCategory::getSortOrder)
                        .orderByAsc(AssetCategory::getId));
        if (categories.isEmpty()) {
            return new ArrayList<>();
        }

        Map<Long, Integer> counts = countAssetsByCategory(projectId);

        // 先建好一级节点，再把二级挂上去，避免两层循环里反复查找
        Map<Long, AssetCategoryVO> roots = new LinkedHashMap<>();
        List<AssetCategory> children = new ArrayList<>();
        for (AssetCategory category : categories) {
            AssetCategoryVO vo = AssetCategoryConverter.toVO(category);
            if (isRoot(category)) {
                vo.setAssetCount(counts.getOrDefault(category.getId(), 0));
                roots.put(category.getId(), vo);
            } else {
                children.add(category);
            }
        }

        for (AssetCategory child : children) {
            AssetCategoryVO parent = roots.get(child.getParentId());
            if (parent == null) {
                // 父分类被删/数据异常：把孤儿分类提升为一级，而不是让它从界面上消失
                log.warn("[分类] 二级分类的父节点不存在，按一级展示 categoryId={} parentId={}",
                        child.getId(), child.getParentId());
                AssetCategoryVO orphan = AssetCategoryConverter.toVO(child);
                orphan.setAssetCount(counts.getOrDefault(child.getId(), 0));
                roots.put(orphan.getId(), orphan);
                continue;
            }
            AssetCategoryVO childVO = AssetCategoryConverter.toVO(child);
            int own = counts.getOrDefault(child.getId(), 0);
            childVO.setAssetCount(own);
            parent.getChildren().add(childVO);
            // 一级分类的数量含子分类：用户点「模型」期望看到整个大类
            parent.setAssetCount(parent.getAssetCount() == null ? own : parent.getAssetCount() + own);
        }

        List<AssetCategoryVO> result = new ArrayList<>(roots.values());
        result.sort(Comparator.comparing(vo -> vo.getSortOrder() == null ? 0 : vo.getSortOrder()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssetCategoryVO create(Long projectId, AssetCategoryDTO dto) {
        actionGuard.require(projectId, Permission.ASSET_WRITE);

        Long parentId = dto.getParentId() == null ? AssetCategory.ROOT_PARENT_ID : dto.getParentId();
        if (!AssetCategory.ROOT_PARENT_ID.equals(parentId)) {
            // 二级分类的父节点必须是本项目的一级分类，否则会出现三级或跨项目挂载
            AssetCategory parent = getCategoryInProject(parentId, projectId);
            if (!isRoot(parent)) {
                throw BizException.badRequest("分类最多支持两级，不能挂在二级分类下");
            }
        }

        String code = normalizeCode(dto.getCode());
        ensureCodeAvailable(projectId, code, null);

        AssetCategory category = new AssetCategory();
        category.setProjectId(projectId);
        category.setParentId(parentId);
        category.setName(dto.getName().trim());
        category.setCode(code);
        category.setIcon(dto.getIcon());
        category.setColor(StringUtils.hasText(dto.getColor()) ? dto.getColor() : "#409EFF");
        category.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        categoryMapper.insert(category);

        log.info("[分类] 新增成功 projectId={} categoryId={} code={}", projectId, category.getId(), code);
        return AssetCategoryConverter.toVO(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssetCategoryVO update(Long projectId, Long categoryId, AssetCategoryDTO dto) {
        actionGuard.require(projectId, Permission.ASSET_WRITE);

        AssetCategory category = getCategoryInProject(categoryId, projectId);
        String code = normalizeCode(dto.getCode());
        boolean codeChanged = !code.equals(category.getCode());
        if (codeChanged) {
            ensureCodeAvailable(projectId, code, categoryId);
        }

        categoryMapper.update(null,
                Wrappers.<AssetCategory>lambdaUpdate()
                        .eq(AssetCategory::getId, categoryId)
                        .eq(AssetCategory::getProjectId, projectId)
                        .set(AssetCategory::getName, dto.getName().trim())
                        .set(AssetCategory::getCode, code)
                        .set(AssetCategory::getIcon, dto.getIcon())
                        .set(AssetCategory::getColor,
                                StringUtils.hasText(dto.getColor()) ? dto.getColor() : "#409EFF")
                        .set(AssetCategory::getSortOrder,
                                dto.getSortOrder() == null ? 0 : dto.getSortOrder()));

        if (codeChanged) {
            // 分类编码是资产编号的前缀。改编码不会自动改历史资产编号——
            // 资产编号是团队口述引用的标识，静默改写会让已有的沟通记录全部失效。
            // 这里只记一条 warn，把影响说清楚，是否批量改编号交给使用者决定。
            log.warn("[分类] 编码已变更 projectId={} categoryId={} {} → {}；"
                            + "已生成的历史资产编号不会随之变化",
                    projectId, categoryId, category.getCode(), code);
        }

        category.setName(dto.getName().trim());
        category.setCode(code);
        category.setIcon(dto.getIcon());
        category.setColor(StringUtils.hasText(dto.getColor()) ? dto.getColor() : "#409EFF");
        category.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        return AssetCategoryConverter.toVO(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long projectId, Long categoryId) {
        actionGuard.require(projectId, Permission.ASSET_DELETE);

        // 单资源查询带 project_id：不带的话删掉的就是别的项目的分类
        getCategoryInProject(categoryId, projectId);

        Long childCount = categoryMapper.selectCount(
                Wrappers.<AssetCategory>lambdaQuery()
                        .eq(AssetCategory::getProjectId, projectId)
                        .eq(AssetCategory::getParentId, categoryId));
        if (childCount != null && childCount > 0) {
            throw BizException.badRequest("该分类下还有 " + childCount + " 个子分类，请先删除子分类");
        }

        Long assetCount = assetMapper.selectCount(
                Wrappers.<Asset>lambdaQuery()
                        .eq(Asset::getProjectId, projectId)
                        .eq(Asset::getCategoryId, categoryId));
        if (assetCount != null && assetCount > 0) {
            throw BizException.badRequest("该分类下还有 " + assetCount
                    + " 个资产，无法删除。请先把这些资产移到其他分类，或删除资产");
        }

        categoryMapper.delete(Wrappers.<AssetCategory>lambdaQuery()
                .eq(AssetCategory::getId, categoryId)
                .eq(AssetCategory::getProjectId, projectId));
        // 分类数量会影响概览上的分类分布（模块4 的统计图），顺手失效一次
        overviewCache.evict(projectId);
        log.info("[分类] 删除成功 projectId={} categoryId={}", projectId, categoryId);
    }

    // ==================== 内部工具 ====================

    /**
     * 按 ID + 项目查分类，查不到时区分「越权」与「不存在」。
     *
     * <p>权威查询<b>带 project_id</b>（第二层防护）；只有查不到时才用一个
     * 仅含主键的计数探针来区分 403 与 404——探针不取回任何业务数据，
     * 只是为了让「访问别人的分类」返回 403 而不是被 404 掩盖。</p>
     */
    private AssetCategory getCategoryInProject(Long categoryId, Long projectId) {
        if (categoryId == null) {
            throw BizException.badRequest("分类ID不能为空");
        }
        AssetCategory category = categoryMapper.selectOne(
                Wrappers.<AssetCategory>lambdaQuery()
                        .eq(AssetCategory::getId, categoryId)
                        .eq(AssetCategory::getProjectId, projectId));
        if (category != null) {
            return category;
        }
        Long exists = categoryMapper.selectCount(
                Wrappers.<AssetCategory>lambdaQuery().eq(AssetCategory::getId, categoryId));
        if (exists != null && exists > 0) {
            throw BizException.forbidden("该分类不属于当前项目，无权访问");
        }
        throw BizException.notFound("分类不存在");
    }

    /** 分类编码统一大写并去掉首尾空白，避免 CHR_SM 与 chr_sm 撞 uk_project_code */
    private String normalizeCode(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }

    /** 编码在本项目内是否可用（并发下的兜底仍是 uk_project_code 唯一约束） */
    private void ensureCodeAvailable(Long projectId, String code, Long excludeId) {
        Long count = categoryMapper.selectCount(
                Wrappers.<AssetCategory>lambdaQuery()
                        .eq(AssetCategory::getProjectId, projectId)
                        .eq(AssetCategory::getCode, code)
                        .ne(excludeId != null, AssetCategory::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.badRequest("分类编码「" + code + "」在本项目中已存在");
        }
    }

    private boolean isRoot(AssetCategory category) {
        return category.getParentId() == null
                || AssetCategory.ROOT_PARENT_ID.equals(category.getParentId());
    }

    /**
     * 按分类统计资产数量。
     *
     * <p>用一条 {@code GROUP BY category_id} 聚合，而不是「遍历分类逐个 count」：
     * 后者在分类有十几个时会打出十几次查询，属于典型的 N+1。
     * 这里用 {@code selectMaps} 直接拿聚合结果，避免把资产明细读回内存再分组。</p>
     */
    private Map<Long, Integer> countAssetsByCategory(Long projectId) {
        List<Map<String, Object>> rows = assetMapper.selectMaps(
                new QueryWrapper<Asset>()
                        .select("category_id AS categoryId", "COUNT(*) AS assetCount")
                        .eq("project_id", projectId)
                        .groupBy("category_id"));
        Map<Long, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object id = row.get("categoryId");
            Object count = row.get("assetCount");
            if (id instanceof Number idNum && count instanceof Number countNum) {
                counts.put(idNum.longValue(), countNum.intValue());
            }
        }
        return counts;
    }
}
