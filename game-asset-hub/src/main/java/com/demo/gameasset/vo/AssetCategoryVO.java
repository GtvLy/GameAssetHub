package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 资产分类（树形）。一级分类的 {@link #children} 装载二级分类。
 *
 * @author 管理员
 */
@Data
public class AssetCategoryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long projectId;

    /** 父分类ID，0 表示一级分类 */
    private Long parentId;

    private String name;

    /** 分类编码，资产编号前缀 */
    private String code;

    private String icon;

    private String color;

    private Integer sortOrder;

    /**
     * 该分类下的资产数量。
     * 一级分类上这个数字<b>含子分类</b>——树形展示时用户看到「模型 24」
     * 期望的是整个模型大类，而不是只挂在「模型」节点下那几条。
     */
    private Integer assetCount;

    /** 二级分类（仅一级分类有此值） */
    private List<AssetCategoryVO> children = new ArrayList<>();

    private LocalDateTime createTime;
}
