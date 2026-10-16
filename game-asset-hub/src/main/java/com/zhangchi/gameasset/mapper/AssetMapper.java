package com.zhangchi.gameasset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhangchi.gameasset.entity.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 资产 Mapper。
 *
 * <p>同 {@link AssetCategoryMapper}：条件构造交给 Service 的 Lambda 包装器，
 * 便于用单元测试断言每一条单资源查询的 SQL 都带了 {@code project_id}。</p>
 *
 * <p><b>唯一的例外是编号查重</b>，见 {@link #countByAssetNoIncludeDeleted}——
 * 它必须绕开 {@code @TableLogic}，Lambda 包装器做不到，只能手写 SQL。</p>
 *
 * @author 张忠驰
 */
@Mapper
public interface AssetMapper extends BaseMapper<Asset> {

    /**
     * 该项目下是否已用过这个资产编号，<b>含逻辑删除的资产</b>。
     *
     * <p><b>为什么不能用 {@code selectCount} + Lambda 包装器</b>：
     * {@link Asset} 上有 {@code @TableLogic}，MyBatis-Plus 会自动补上
     * {@code deleted = 0}，于是被删掉的资产编号看起来是「可用」的。
     * 但数据库的 {@code uk_project_asset_no} 并不区分删没删——插入时才撞上，
     * 异常被全局处理器兜成「数据已存在，请勿重复提交」：
     * 用户明明在新建，却被告知重复提交，完全摸不着头脑。</p>
     *
     * <p><b>为什么不干脆允许复用删掉的编号</b>：编号是给人念的标识
     * （「你看下 CHR_SM_0003 那个箱子」），复用一个曾经存在过的号，
     * 会让历史截图、聊天记录、工单里的编号指向另一个资产。
     * 永不复用比「数据库允许」更符合直觉。</p>
     *
     * @return 命中行数，0 表示该编号从未被使用过（可安全分配）
     */
    @Select("SELECT COUNT(*) FROM asset "
            + "WHERE project_id = #{projectId} AND asset_no = #{assetNo}")
    int countByAssetNoIncludeDeleted(@Param("projectId") Long projectId,
                                     @Param("assetNo") String assetNo);
}
