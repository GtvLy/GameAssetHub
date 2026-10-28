package com.demo.gameasset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.gameasset.entity.AssetCategory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 资产分类 Mapper。
 *
 * <p>不定义任何自定义方法：分类的查询条件（{@code project_id}、{@code parent_id}、
 * {@code sort_order}）全部由 Service 用 Lambda 条件构造器拼装，
 * 这样「SQL 是否带了 project_id」可以在单元测试里直接读出来验证——
 * 写进 XML 的 SQL 就只能靠人工检查了。</p>
 *
 * @author 管理员
 */
@Mapper
public interface AssetCategoryMapper extends BaseMapper<AssetCategory> {
}
