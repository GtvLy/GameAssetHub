package com.zhangchi.gameasset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhangchi.gameasset.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 任务 Mapper（模块3）。
 *
 * <p>除了 MyBatis-Plus 的通用方法，只有一条手写 SQL：{@link #updateSortOrder}。
 * 它存在的理由很具体——整列重排时<b>不能</b>走 {@code updateById}：
 * 实体上带 {@code @Version}，而重排只是「把位置摆正」，
 * 并不是用户发起的修改。若走乐观锁，一次重排会把整列任务的版本号都推进一格，
 * 别的成员手里刚拿到的 version 全部作废，紧接着的拖拽会莫名其妙收到 409。</p>
 *
 * <p>同理，它也不能顺手改 {@code update_time}：重排不是业务上的「更新」。</p>
 *
 * <p>SQL 里带 {@code project_id} 是硬要求（模块1 建立的数据隔离第二层），
 * {@code MapperSqlContractTest} 会逐条核查。</p>
 *
 * @author 张忠驰
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {

    /**
     * 只更新列内排序位置（整列重排用）。
     *
     * @param id        任务ID
     * @param projectId 项目ID，越权防护的第二道条件
     * @param sortOrder 新的排序值
     * @return 受影响行数
     */
    @Update("UPDATE task SET sort_order = #{sortOrder} "
            + "WHERE id = #{id} AND project_id = #{projectId} AND deleted = 0")
    int updateSortOrder(@Param("id") Long id,
                        @Param("projectId") Long projectId,
                        @Param("sortOrder") Integer sortOrder);
}
