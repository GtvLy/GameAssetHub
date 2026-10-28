package com.demo.gameasset.dto;

import lombok.Data;

/**
 * 资产列表筛选与分页参数（文档 4.2：分页 + 分类/状态/负责人/优先级/关键字）。
 *
 * <p>全部字段可选，不传即不参与筛选。分页参数给了默认值，
 * 前端首次进入页面可以不传任何参数直接拿到第一页。</p>
 *
 * @author 管理员
 */
@Data
public class AssetQueryDTO {

    /** 页码，从 1 开始 */
    private Integer page = 1;

    /** 每页条数，上限由 MyBatis-Plus 分页插件统一限制（200） */
    private Integer size = 20;

    /** 按分类筛选。传一级分类时是否包含其子分类见 categoryWithChildren */
    private Long categoryId;

    /**
     * 传一级分类时是否把子分类的资产也算进来。
     * 分类支持二级，前端点一级分类时通常期望看到全部后代，
     * 因此默认 true。
     */
    private Boolean categoryWithChildren = Boolean.TRUE;

    /** 状态：IN_PROGRESS / PENDING / APPROVED / REJECTED / DEPRECATED */
    private String status;

    /** 负责人（主负责人 userId） */
    private Long ownerId;

    /** 优先级：P0 / P1 / P2 */
    private String priority;

    /** 关键字：同时模糊匹配资产名称与资产编号 */
    private String keyword;

    /** 归一化页码：非法值（null / 0 / 负数）一律回落到第 1 页 */
    public int normalizedPage() {
        return page == null || page < 1 ? 1 : page;
    }

    /** 归一化页大小：非法值回落到 20，过大值由分页插件再兜一道 */
    public int normalizedSize() {
        if (size == null || size < 1) {
            return 20;
        }
        return Math.min(size, 200);
    }

    /** 是否按一级分类展开子分类 */
    public boolean includeChildren() {
        return categoryWithChildren == null || categoryWithChildren;
    }
}
