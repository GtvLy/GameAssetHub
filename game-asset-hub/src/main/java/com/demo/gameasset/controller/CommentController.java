package com.demo.gameasset.controller;

import com.demo.gameasset.common.annotation.ProjectPermission;
import com.demo.gameasset.common.enums.Permission;
import com.demo.gameasset.common.result.Result;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.dto.CommentCreateDTO;
import com.demo.gameasset.service.TaskCommentService;
import com.demo.gameasset.vo.TaskCommentVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务评论接口（模块3，文档 4.2）。
 *
 * <p>三条路径里都没有项目段，项目上下文同样来自 {@code X-Project-Id}；
 * 发表评论需要 {@code TASK_WRITE}（VIEWER 只读，不能评论）。</p>
 *
 * <p><b>路径 {@code /api/tasks/comments/{cid}} 与 {@code /api/tasks/{id}} 的关系</b>：
 * 前者三段、后者两段，Spring 的路径匹配不会混淆；
 * 而 {@code DELETE /api/tasks/comments}（两段）这种请求不存在对应的语义，
 * 这里也不打算把它当成「删除 id 为 comments 的任务」——路径变量是 Long，
 * 绑定失败会得到 400 而不是误删。</p>
 *
 * @author 管理员
 */
@RestController
@RequiredArgsConstructor
public class CommentController {

    private final TaskCommentService taskCommentService;

    /** 评论列表（按时间正序） */
    @ProjectPermission(Permission.TASK_READ)
    @GetMapping("/api/tasks/{id}/comments")
    public Result<List<TaskCommentVO>> list(@PathVariable("id") Long id) {
        return Result.success(taskCommentService.list(id, UserContext.requireProjectId()));
    }

    /** 发表评论，支持 {@code @用户名} 提及本项目成员 */
    @ProjectPermission(Permission.TASK_WRITE)
    @PostMapping("/api/tasks/{id}/comments")
    public Result<TaskCommentVO> add(@PathVariable("id") Long id,
                                     @Valid @RequestBody CommentCreateDTO dto) {
        return Result.success("评论已发表",
                taskCommentService.add(id, UserContext.requireProjectId(), dto));
    }

    /**
     * 删除自己的评论（逻辑删除）。
     *
     * <p>删除别人的评论返回 403。服务层判定而不是在这里比对 userId：
     * 评论的归属要连 {@code project_id} 一起查，那是持久层的活。</p>
     */
    @ProjectPermission(Permission.TASK_WRITE)
    @DeleteMapping("/api/tasks/comments/{cid}")
    public Result<Void> delete(@PathVariable("cid") Long cid) {
        taskCommentService.delete(cid, UserContext.requireProjectId());
        return Result.success("评论已删除", null);
    }
}
