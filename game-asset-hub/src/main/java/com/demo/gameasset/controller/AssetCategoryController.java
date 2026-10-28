package com.demo.gameasset.controller;

import com.demo.gameasset.common.annotation.ProjectId;
import com.demo.gameasset.common.annotation.ProjectPermission;
import com.demo.gameasset.common.enums.Permission;
import com.demo.gameasset.common.result.Result;
import com.demo.gameasset.dto.AssetCategoryDTO;
import com.demo.gameasset.service.AssetCategoryService;
import com.demo.gameasset.vo.AssetCategoryVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 资产分类接口（模块2，文档 4.2 分类部分）。
 *
 * <p>路径前缀是 {@code /api/projects/{projectId}/categories}，
 * 项目ID 直接来自路径变量，因此 {@code ProjectIdResolver} 不需要请求头兜底。</p>
 *
 * <p>权限：读用 {@code ASSET_READ}，增改用 {@code ASSET_WRITE}，
 * 删除用 {@code ASSET_DELETE}（与「资产管理读写」的矩阵一致：
 * ARTIST 具备，PROGRAMMER / DESIGNER / VIEWER 只读）。</p>
 *
 * @author 管理员
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class AssetCategoryController {

    private final AssetCategoryService categoryService;

    /** 分类树（一级 + 二级，带各分类下的资产数量） */
    @ProjectPermission(Permission.ASSET_READ)
    @GetMapping("/{projectId}/categories")
    public Result<List<AssetCategoryVO>> tree(@PathVariable("projectId") Long projectId) {
        return Result.success(categoryService.tree(projectId));
    }

    /** 新增分类 */
    @ProjectPermission(Permission.ASSET_WRITE)
    @PostMapping("/{projectId}/categories")
    public Result<AssetCategoryVO> create(@PathVariable("projectId") Long projectId,
                                          @Valid @RequestBody AssetCategoryDTO dto) {
        return Result.success("分类已创建", categoryService.create(projectId, dto));
    }

    /**
     * 修改分类。
     *
     * <p>路径里同时有 {@code projectId} 与 {@code id}，用 {@link ProjectId}
     * 显式标注哪个是项目ID，让「切面按哪个值做校验」在代码里一眼可见——
     * 标错的后果是拿别的项目去校验，等于没有校验。</p>
     */
    @ProjectPermission(Permission.ASSET_WRITE)
    @PutMapping("/{projectId}/categories/{id}")
    public Result<AssetCategoryVO> update(@PathVariable("projectId") @ProjectId Long projectId,
                                          @PathVariable("id") Long id,
                                          @Valid @RequestBody AssetCategoryDTO dto) {
        return Result.success("分类已更新", categoryService.update(projectId, id, dto));
    }

    /** 删除分类（分类下有资产或子分类时拒绝） */
    @ProjectPermission(Permission.ASSET_DELETE)
    @DeleteMapping("/{projectId}/categories/{id}")
    public Result<Void> delete(@PathVariable("projectId") @ProjectId Long projectId,
                              @PathVariable("id") Long id) {
        categoryService.delete(projectId, id);
        return Result.success("分类已删除", null);
    }
}
