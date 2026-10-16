package com.zhangchi.gameasset.convert;

import com.zhangchi.gameasset.entity.AssetCategory;
import com.zhangchi.gameasset.vo.AssetCategoryVO;
import org.springframework.beans.BeanUtils;

/**
 * 资产分类实体 → VO。
 *
 * <p>与模块1 的 Converter 一样是「静态方法 + BeanUtils」，
 * 不引 MapStruct：转换逻辑本身没有分支，注解处理器带来的
 * 编译期复杂度远大于它省下的代码量。</p>
 *
 * @author 张忠驰
 */
public final class AssetCategoryConverter {

    private AssetCategoryConverter() {
    }

    /** 基础字段转换（不含 assetCount 与 children，由调用方按需填充） */
    public static AssetCategoryVO toVO(AssetCategory entity) {
        if (entity == null) {
            return null;
        }
        AssetCategoryVO vo = new AssetCategoryVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
