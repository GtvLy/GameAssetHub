package com.zhangchi.gameasset.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhangchi.gameasset.common.enums.AssetStatus;
import com.zhangchi.gameasset.convert.AssetConverter;
import com.zhangchi.gameasset.convert.AssetVersionConverter;
import com.zhangchi.gameasset.entity.Asset;
import com.zhangchi.gameasset.entity.AssetCategory;
import com.zhangchi.gameasset.entity.AssetVersion;
import com.zhangchi.gameasset.entity.SysUser;
import com.zhangchi.gameasset.mapper.AssetCategoryMapper;
import com.zhangchi.gameasset.mapper.AssetMapper;
import com.zhangchi.gameasset.mapper.AssetVersionMapper;
import com.zhangchi.gameasset.mapper.SysUserMapper;
import com.zhangchi.gameasset.vo.AssetVO;
import com.zhangchi.gameasset.vo.AssetVersionVO;
import com.zhangchi.gameasset.vo.ProjectOverviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目概览里的资产指标（模块2 对模块1 的交付）。
 *
 * <p>模块1 阶段 {@code ProjectOverviewVO} 的 {@code assetTotal / assetApproved /
 * assetPending / assetCompletionRate / recentAssets} 是占位 0 与空数组，
 * 因为当时还没有 {@code asset} 表。模块2 建表之后，这几个指标在这里真正接上。</p>
 *
 * <p><b>为什么抽成独立组件而不是写进 ProjectServiceImpl</b>：
 * 一是让「资产指标的统计口径」只有一处实现，模块4 的统计图表能直接复用；
 * 二是模块1 的 {@code ProjectServiceImpl} 不必因为模块2 的接入而改动，
 * 它对 {@code ProjectOverviewCache} 的调用方式保持原样。</p>
 *
 * <p><b>调用时机</b>：由 {@code ProjectOverviewCache#put} 在写入缓存前调用
 * （见该类注释）。这样「首次请求返回的 VO」与「缓存里存的 VO」是同一份对象，
 * 不会出现「第一次打开指标不全、刷新一下才正常」的诡异现象。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetOverviewSupport {

    /** 概览页「最近更新的资产」条数（文档 5.9 取 6） */
    private static final int RECENT_LIMIT = 6;

    private final AssetMapper assetMapper;
    private final AssetCategoryMapper assetCategoryMapper;
    private final AssetVersionMapper assetVersionMapper;
    private final SysUserMapper sysUserMapper;

    /**
     * 把资产指标填进概览 VO（原地修改）。
     *
     * @param overview  模块1 已经填好项目基础信息的 VO
     * @param projectId 项目ID
     */
    public void fill(ProjectOverviewVO overview, Long projectId) {
        if (overview == null || projectId == null) {
            return;
        }
        int total = countByStatus(projectId, null);
        int approved = countByStatus(projectId, AssetStatus.APPROVED.name());
        int pending = countByStatus(projectId, AssetStatus.PENDING.name());

        overview.setAssetTotal(total);
        overview.setAssetApproved(approved);
        overview.setAssetPending(pending);
        overview.setAssetCompletionRate(completionRate(approved, total));
        overview.setRecentAssets(recentAssets(projectId));
    }

    /**
     * 统计项目下的资产数。
     *
     * <p>查询强制带 {@code project_id}：这是模块1 建立的第二层防护，
     * 统计口径尤其不能漏——少一个条件就会把别的项目的资产算进来，
     * 表现为「完成率莫名其妙超过 100%」。</p>
     *
     * @param status 为 null 时统计全部状态
     */
    public int countByStatus(Long projectId, String status) {
        Long count = assetMapper.selectCount(
                Wrappers.<Asset>lambdaQuery()
                        .eq(Asset::getProjectId, projectId)
                        .eq(StringUtils.hasText(status), Asset::getStatus, status));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 资产完成率（%）= 已通过 / 总数 × 100，保留一位小数。
     * 分母为 0 时返回 0.0，避免除零（文档 9 节的边界用例）。
     */
    public static double completionRate(int approved, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(approved * 100.0 / total)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * 最近更新的资产（最多 6 条），用于概览页的资产速览。
     *
     * <p>三次<b>批量</b>查询取齐分类、当前版本、负责人，而不是在循环里逐个查：
     * 概览接口是打开项目后的第一个请求，它一旦产生 N+1，
     * 在资产上百的项目里就会明显卡顿，而这正是文档 6 节「概览命中缓存后
     * &lt; 100ms」要守住的地方。</p>
     */
    public List<Object> recentAssets(Long projectId) {
        List<Asset> assets = assetMapper.selectList(
                Wrappers.<Asset>lambdaQuery()
                        .eq(Asset::getProjectId, projectId)
                        .orderByDesc(Asset::getUpdateTime)
                        .last("LIMIT " + RECENT_LIMIT));
        if (assets.isEmpty()) {
            return new ArrayList<>();
        }

        Map<Long, AssetCategory> categoryMap = batchCategories(assets);
        Map<Long, AssetVersionVO> versionMap = batchCurrentVersions(assets);
        Map<Long, SysUser> userMap = batchUsers(assets.stream().map(Asset::getOwnerId).collect(Collectors.toSet()));

        List<Object> result = new ArrayList<>(assets.size());
        for (Asset asset : assets) {
            AssetVersionVO current = versionMap.get(asset.getCurrentVersionId());
            AssetVO vo = AssetConverter.toVO(asset,
                    categoryMap.get(asset.getCategoryId()),
                    displayName(userMap.get(asset.getOwnerId())),
                    null,
                    current,
                    current == null ? null : current.getUploaderName());
            // 概览只需要速览信息，去掉体积大又用不上的流转选项
            vo.setNextStatuses(Collections.emptyList());
            result.add(vo);
        }
        return result;
    }

    /** 批量取分类，避免每条资产一次查询 */
    private Map<Long, AssetCategory> batchCategories(List<Asset> assets) {
        Set<Long> ids = assets.stream().map(Asset::getCategoryId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return assetCategoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(AssetCategory::getId, Function.identity(), (a, b) -> a));
    }

    /** 批量取当前版本（缩略图与上传人在里面） */
    private Map<Long, AssetVersionVO> batchCurrentVersions(List<Asset> assets) {
        Set<Long> ids = assets.stream().map(Asset::getCurrentVersionId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<AssetVersion> versions = assetVersionMapper.selectBatchIds(ids);
        Map<Long, SysUser> uploaders = batchUsers(versions.stream()
                .map(AssetVersion::getUploaderId).collect(Collectors.toSet()));
        return versions.stream().collect(Collectors.toMap(AssetVersion::getId,
                v -> AssetVersionConverter.toVO(v, displayName(uploaders.get(v.getUploaderId()))),
                (a, b) -> a));
    }

    /** 批量取用户 */
    private Map<Long, SysUser> batchUsers(Set<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return sysUserMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));
    }

    /** 昵称优先，为空回落到登录名；用户不存在返回 null */
    private String displayName(SysUser user) {
        if (user == null) {
            return null;
        }
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }
}
