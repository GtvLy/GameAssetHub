package com.demo.gameasset.service;

import com.demo.gameasset.dto.AssetCategoryDTO;
import com.demo.gameasset.vo.AssetCategoryVO;

import java.util.List;

/**
 * 资产分类服务（文档 4.2 分类接口）。
 *
 * <p>分类支持二级。所有方法都以 {@code projectId} 为第一参数：
 * 分类是<b>项目私有</b>的，两个项目可以有同名同码的分类，
 * 因此不存在任何「按分类ID跨项目查」的场景。</p>
 *
 * @author 管理员
 */
public interface AssetCategoryService {

    /** 分类树（一级 + 二级），带各分类下的资产数量 */
    List<AssetCategoryVO> tree(Long projectId);

    /** 新增分类 */
    AssetCategoryVO create(Long projectId, AssetCategoryDTO dto);

    /** 修改分类（名称、编码、图标、颜色、排序） */
    AssetCategoryVO update(Long projectId, Long categoryId, AssetCategoryDTO dto);

    /**
     * 删除分类。
     *
     * <p>分类下有资产、或有一级分类下还有子分类时<b>拒绝删除</b>：
     * 分类是资产编号的组成来源，静默删掉会让资产编号失去可解释性，
     * 也会让「按分类统计」出现归属不明的资产。</p>
     */
    void delete(Long projectId, Long categoryId);
}
