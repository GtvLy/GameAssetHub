package com.demo.gameasset.service;

import com.demo.gameasset.dto.TaskAssignDTO;
import com.demo.gameasset.dto.TaskCreateDTO;
import com.demo.gameasset.dto.TaskMoveDTO;
import com.demo.gameasset.dto.TaskQueryDTO;
import com.demo.gameasset.dto.TaskUpdateDTO;
import com.demo.gameasset.vo.BoardVO;
import com.demo.gameasset.vo.PageResultVO;
import com.demo.gameasset.vo.TaskCardVO;
import com.demo.gameasset.vo.TaskMoveVO;
import com.demo.gameasset.vo.TaskVO;

import java.util.List;

/**
 * 任务看板与任务协作（模块3，文档 4.2 的 {@code /api/tasks} 一节）。
 *
 * @author 管理员
 */
public interface TaskService {

    /**
     * 整块看板：一次请求返回四列分组与每列的卡片、数量、故事点合计。
     *
     * <p>四列恒定返回（空列也返回），前端直接按 {@code columns} 渲染。</p>
     */
    BoardVO board(Long projectId);

    /** 任务列表（分页 + 筛选） */
    PageResultVO<TaskCardVO> page(Long projectId, TaskQueryDTO query);

    /** 创建任务（可关联资产、可指派） */
    TaskVO create(Long projectId, TaskCreateDTO dto);

    /** 任务详情（含评论与状态日志） */
    TaskVO detail(Long taskId, Long projectId);

    /** 更新任务内容（不含列状态——改列只走 {@link #move}） */
    TaskVO update(Long taskId, Long projectId, TaskUpdateDTO dto);

    /**
     * 拖拽移动（列 + 列内位置）。
     *
     * <p>返回服务端最终计算的 {@code sortOrder} 与新的 {@code version}，
     * 前端以响应为准修正本地状态（文档 5.4）。乐观锁冲突时抛 409。</p>
     */
    TaskMoveVO move(Long taskId, Long projectId, TaskMoveDTO dto);

    /** 指派任务（{@code assigneeId} 为空表示取消指派） */
    TaskVO assign(Long taskId, Long projectId, TaskAssignDTO dto);

    /**
     * 删除任务（逻辑删除）。
     *
     * <p>评论与状态日志<b>全部保留</b>：协作记录的价值在事后，
     * 删任务不该把它一起抹掉。看板自然不再显示（逻辑删除的行查不出来）。</p>
     */
    void delete(Long taskId, Long projectId);

    /** 某个资产的相关任务（文档 5.5：资产详情页的「相关任务」区块） */
    List<TaskCardVO> relatedTasks(Long projectId, Long assetId);
}
