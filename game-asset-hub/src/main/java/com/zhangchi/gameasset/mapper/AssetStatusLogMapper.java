package com.zhangchi.gameasset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhangchi.gameasset.entity.AssetStatusLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 资产状态流转记录 Mapper（只追加，不修改删除）。
 *
 * @author 张忠驰
 */
@Mapper
public interface AssetStatusLogMapper extends BaseMapper<AssetStatusLog> {
}
