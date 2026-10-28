package com.demo.gameasset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.gameasset.entity.AssetStatusLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 资产状态流转记录 Mapper（只追加，不修改删除）。
 *
 * @author 管理员
 */
@Mapper
public interface AssetStatusLogMapper extends BaseMapper<AssetStatusLog> {
}
