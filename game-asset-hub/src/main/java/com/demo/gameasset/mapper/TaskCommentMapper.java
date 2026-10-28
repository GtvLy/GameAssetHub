package com.demo.gameasset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.gameasset.entity.TaskComment;
import com.demo.gameasset.vo.TaskCommentCountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 任务评论 Mapper（模块3）。
 *
 * <p>手写 SQL 只有一条 {@link #countByTaskIds}：看板一次要渲染整列卡片，
 * 每张卡片上都有评论数。逐张卡片查一次就是标准的 N+1——看板是打开项目后
 * 最常刷新的页面，这种查询必须一次带走。</p>
 *
 * @author 管理员
 */
@Mapper
public interface TaskCommentMapper extends BaseMapper<TaskComment> {

    /**
     * 批量统计若干任务的评论数（看板卡片上的评论角标）。
     *
     * <p>{@code deleted = 0} 必须显式写：这是手写 SQL，不走 MyBatis-Plus 的
     * {@code @TableLogic} 自动补条件，漏了就会把已删除的评论也算进角标。</p>
     *
     * @param projectId 项目ID，越权防护的第二道条件
     * @param taskIds   任务ID集合，调用方需保证非空
     */
    @Select("<script>"
            + "SELECT task_id AS taskId, COUNT(*) AS total FROM task_comment "
            + "WHERE project_id = #{projectId} AND deleted = 0 "
            + "AND task_id IN <foreach collection='taskIds' item='tid' open='(' separator=',' close=')'>#{tid}</foreach> "
            + "GROUP BY task_id"
            + "</script>")
    List<TaskCommentCountVO> countByTaskIds(@Param("projectId") Long projectId,
                                           @Param("taskIds") Collection<Long> taskIds);
}
