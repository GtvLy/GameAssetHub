package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 单个分类下的资产数量与完成情况（文档 4.4 的 {@code /stat/asset-by-category}）。
 *
 * <p>分类是二级树，但这里<b>按叶子分类逐行返回</b>，不做层级汇总：
 * 「模型」这个一级分类下挂了「角色模型」与「场景道具」，
 * 若把三者都列出来，柱状图的合计会比资产总数多——相加不等于总数的图
 * 比不画还糟。前端按 {@link #parentId} 自己决定要不要折叠展示。</p>
 *
 * <p>完成率口径与 {@link AssetCompletionVO} 一致：APPROVED / 该分类总数。</p>
 *
 * @author 张忠驰
 */
@Data
public class AssetCategoryStatVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long categoryId;

    private String categoryName;

    /** 分类编码，与资产编号前缀同源（CHR → CHR_0001） */
    private String categoryCode;

    /** 父分类ID，0 表示一级分类 */
    private Long parentId;

    /** 分类配色，柱状图直接用 */
    private String color;

    /** 该分类下的资产总数 */
    private Integer total;

    /** 已通过审核数 */
    private Integer approved;

    /** 待审核数 */
    private Integer pending;

    /** 制作中数 */
    private Integer inProgress;

    /** 完成率（%），保留一位小数；该分类没有资产时为 0.0 */
    private Double completionRate;
}
