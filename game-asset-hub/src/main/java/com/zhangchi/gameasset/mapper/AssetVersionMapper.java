package com.zhangchi.gameasset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhangchi.gameasset.entity.AssetVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 资产版本 Mapper。
 *
 * <p>这里有两个<b>自定义 SQL</b>方法（其余查询仍用 Lambda 包装器）：</p>
 * <ol>
 *   <li>{@link #clearCurrent}——切换 {@code is_current} 标记。
 *       它是一条「按条件批量置 0」的 UPDATE，用 Lambda 包装器写当然也可以，
 *       但这是文档 3.3 明确给出实现的三条语句之一，写成方法后调用点一眼可读；</li>
 *   <li>{@link #selectMaxVersionNo}——取最大版本号。
 *       用 {@code MAX()} 聚合而不是「查列表再取第一条」，
 *       避免为了拿一个数字把整行记录读回来。</li>
 * </ol>
 *
 * <p><b>两个方法都带 {@code project_id}</b>：虽然 {@code asset_id} 已经能唯一定位，
 * 但把项目条件一并带上，可以让「越权访问」在数据库层就查不到任何行，
 * 而不是先查出来再在 Java 里判断归属。这是模块1 建立的第二层防护。</p>
 *
 * @author 张忠驰
 */
@Mapper
public interface AssetVersionMapper extends BaseMapper<AssetVersion> {

    /**
     * 清除某资产下的当前版本标记。
     *
     * <p>切换 {@code is_current} 的第一步，必须在置新标记<b>之前</b>调用，
     * 否则同一资产会短暂出现两条 {@code is_current = 1}。</p>
     *
     * @return 受影响行数（正常为 1；0 表示该资产当前没有任何 current 版本）
     */
    @Update("UPDATE asset_version SET is_current = 0 "
            + "WHERE asset_id = #{assetId} AND project_id = #{projectId} AND is_current = 1")
    int clearCurrent(@Param("assetId") Long assetId, @Param("projectId") Long projectId);

    /**
     * 取某资产下的最大版本号。无版本时返回 0，调用方 +1 即为下一个版本号。
     *
     * <p>用 {@code COALESCE} 保证永远返回非 null，省掉调用方的一次判空。</p>
     */
    @Select("SELECT COALESCE(MAX(version_no), 0) FROM asset_version "
            + "WHERE asset_id = #{assetId} AND project_id = #{projectId}")
    int selectMaxVersionNo(@Param("assetId") Long assetId, @Param("projectId") Long projectId);
}
