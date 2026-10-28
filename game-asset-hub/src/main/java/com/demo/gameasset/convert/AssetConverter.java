package com.demo.gameasset.convert;

import com.demo.gameasset.common.enums.AssetPriority;
import com.demo.gameasset.common.enums.AssetStatus;
import com.demo.gameasset.entity.Asset;
import com.demo.gameasset.entity.AssetCategory;
import com.demo.gameasset.vo.AssetVersionVO;
import com.demo.gameasset.vo.AssetVO;
import org.springframework.beans.BeanUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 资产实体 → VO。
 *
 * <p>转换需要若干「外部上下文」（分类、负责人昵称、当前版本），
 * 因此做成显式多参数方法而不是让 Converter 去查库：
 * 转换器一旦开始查库，就无法在单元测试里脱离 Mapper 使用，
 * 也容易在循环里被调用而写出 N+1。参数全部由 Service 批量查好后传入。</p>
 *
 * @author 管理员
 */
public final class AssetConverter {

    private AssetConverter() {
    }

    /**
     * 完整转换。
     *
     * @param entity           资产实体
     * @param category         所属分类，可为 null（分类被并发删除）
     * @param ownerName        负责人昵称
     * @param reviewerName     审核人昵称
     * @param currentVersion   当前版本，可为 null（历史脏数据）
     * @param lastUploaderName 最近一次上传人昵称
     */
    public static AssetVO toVO(Asset entity, AssetCategory category, String ownerName,
                               String reviewerName, AssetVersionVO currentVersion,
                               String lastUploaderName) {
        if (entity == null) {
            return null;
        }
        AssetVO vo = new AssetVO();
        BeanUtils.copyProperties(entity, vo);

        if (category != null) {
            vo.setCategoryId(category.getId());
            vo.setCategoryName(category.getName());
            vo.setCategoryCode(category.getCode());
            vo.setCategoryColor(category.getColor());
        }

        vo.setStatusLabel(AssetStatus.label(entity.getStatus()));
        vo.setPriorityLabel(AssetPriority.label(entity.getPriority()));
        vo.setTagList(splitTags(entity.getTags()));
        vo.setOwnerName(ownerName);
        vo.setReviewerName(reviewerName);
        vo.setCurrentVersion(currentVersion);
        vo.setLastUploaderName(lastUploaderName);
        vo.setNextStatuses(new ArrayList<>(AssetStatus.nextStatuses(entity.getStatus())));

        // 卡片墙取缩略图时不想再钻一层 currentVersion，这里顺手提上来
        if (currentVersion != null) {
            vo.setThumbnailUrl(currentVersion.getThumbnailUrl());
        }
        return vo;
    }

    /** 精简转换：只做实体本身的字段映射，供单元测试与内部轻量场景使用 */
    public static AssetVO toVO(Asset entity) {
        return toVO(entity, null, null, null, null, null);
    }

    /**
     * 标签串 → 列表。
     *
     * <p>{@code "场景, 道具, 场景"} → {@code [场景, 道具]}：
     * 去空白 + 去重。美术手写标签时重复与多空格非常常见，
     * 前端卡片上出现两个一模一样的标签会显得系统很粗糙。</p>
     */
    public static List<String> splitTags(String tags) {
        if (!StringUtils.hasText(tags)) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .forEach(unique::add);
        return new ArrayList<>(unique);
    }

    /** 列表视图体积控制：卡片与表格都不需要完整版本对象里的 fileKey / md5 */
    public static void stripHeavyFields(AssetVO vo) {
        if (vo != null) {
            vo.setCurrentVersion(null);
        }
    }
}
