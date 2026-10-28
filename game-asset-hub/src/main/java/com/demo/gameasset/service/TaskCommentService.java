package com.demo.gameasset.service;

import com.demo.gameasset.dto.CommentCreateDTO;
import com.demo.gameasset.vo.TaskCommentVO;

import java.util.List;

/**
 * 任务评论与 @提及（模块3，文档 4.2 的三个评论接口）。
 *
 * @author 管理员
 */
public interface TaskCommentService {

    /** 评论列表（按时间正序，符合讨论的阅读顺序） */
    List<TaskCommentVO> list(Long taskId, Long projectId);

    /**
     * 发表评论。
     *
     * <p>副作用有两个：给任务负责人推 {@code COMMENT}，
     * 给被 @ 到的人推 {@code MENTION}。两者都不给自己发（文档 5.8）。</p>
     */
    TaskCommentVO add(Long taskId, Long projectId, CommentCreateDTO dto);

    /**
     * 删除评论（逻辑删除）。
     *
     * <p>只能删自己的：别人的评论不是「我的记录」。
     * 越权时返回 403 而不是静默成功——静默成功会让用户以为自己删掉了，
     * 刷新之后评论又出现。</p>
     */
    void delete(Long commentId, Long projectId);
}
