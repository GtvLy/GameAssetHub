package com.demo.gameasset.controller;

import com.demo.gameasset.common.annotation.ProjectPermission;
import com.demo.gameasset.common.enums.Permission;
import com.demo.gameasset.common.result.Result;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.dto.TaskAssignDTO;
import com.demo.gameasset.dto.TaskCreateDTO;
import com.demo.gameasset.dto.TaskMoveDTO;
import com.demo.gameasset.dto.TaskQueryDTO;
import com.demo.gameasset.dto.TaskUpdateDTO;
import com.demo.gameasset.service.TaskService;
import com.demo.gameasset.vo.BoardVO;
import com.demo.gameasset.vo.PageResultVO;
import com.demo.gameasset.vo.TaskCardVO;
import com.demo.gameasset.vo.TaskMoveVO;
import com.demo.gameasset.vo.TaskVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务看板接口（模块3，文档 4.2）。
 *
 * <p><b>两条路径形态</b>（与 {@code AssetController} 完全对称）：</p>
 * <ul>
 *   <li>{@code /api/projects/{projectId}/board|tasks}——项目维度，项目ID 来自路径；</li>
 *   <li>{@code /api/tasks/{id}/...}——任务维度，路径里<b>没有</b>项目段，
 *       项目ID 由切面从 {@code X-Project-Id} 请求头取出后交给
 *       {@code ProjectIdResolver}（前端拦截器全局注入）。</li>
 * </ul>
 *
 * <p><b>权限</b>：看板读用 {@code TASK_READ}、写用 {@code TASK_WRITE}，
 * 两者都由 {@code RolePermissionMap} 集中定义。VIEWER 只有 {@code TASK_READ}，
 * 因此创建 / 更新 / 拖拽 / 指派 / 删除一律 403——这是文档 3.3 权限矩阵里
 * 「任务看板：VIEWER 只读」那格的落点，不需要在控制器里再写 if。</p>
 *
 * @author 管理员
 */
@RestController
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    // ==================== 项目维度：看板与列表 ====================

    /**
     * 获取整个看板。
     *
     * <p>一次请求返回四列（含每列的数量与故事点合计），不是四列四次请求——
     * 文档 6 节「看板拖拽/列表筛选 ≤ 500ms」的前提。</p>
     */
    @ProjectPermission(Permission.TASK_READ)
    @GetMapping("/api/projects/{projectId}/board")
    public Result<BoardVO> board(@PathVariable("projectId") Long projectId) {
        return Result.success(taskService.board(projectId));
    }

    /** 任务列表（分页 + 列/负责人/优先级/类型/关键字/逾期筛选） */
    @ProjectPermission(Permission.TASK_READ)
    @GetMapping("/api/projects/{projectId}/tasks")
    public Result<PageResultVO<TaskCardVO>> page(@PathVariable("projectId") Long projectId,
                                                 TaskQueryDTO query) {
        return Result.success(taskService.page(projectId, query));
    }

    /** 创建任务（可关联资产、可指派） */
    @ProjectPermission(Permission.TASK_WRITE)
    @PostMapping("/api/projects/{projectId}/tasks")
    public Result<TaskVO> create(@PathVariable("projectId") Long projectId,
                                 @Valid @RequestBody TaskCreateDTO dto) {
        return Result.success("任务已创建", taskService.create(projectId, dto));
    }

    // ==================== 任务维度 ====================

    /** 任务详情（含评论与状态变更日志） */
    @ProjectPermission(Permission.TASK_READ)
    @GetMapping("/api/tasks/{id}")
    public Result<TaskVO> detail(@PathVariable("id") Long id) {
        return Result.success(taskService.detail(id, UserContext.requireProjectId()));
    }

    /** 更新任务内容（不含看板列——改列只走 move） */
    @ProjectPermission(Permission.TASK_WRITE)
    @PutMapping("/api/tasks/{id}")
    public Result<TaskVO> update(@PathVariable("id") Long id,
                                 @Valid @RequestBody TaskUpdateDTO dto) {
        return Result.success("任务已更新",
                taskService.update(id, UserContext.requireProjectId(), dto));
    }

    /**
     * 拖拽移动（列 + 列内位置）。
     *
     * <p>响应返回服务端最终计算的 {@code sortOrder} 与新的 {@code version}，
     * 前端以之为准修正本地乐观更新的结果。版本冲突返回 409。</p>
     */
    @ProjectPermission(Permission.TASK_WRITE)
    @PatchMapping("/api/tasks/{id}/move")
    public Result<TaskMoveVO> move(@PathVariable("id") Long id,
                                   @Valid @RequestBody TaskMoveDTO dto) {
        return Result.success("已移动", taskService.move(id, UserContext.requireProjectId(), dto));
    }

    /** 指派任务（{@code assigneeId} 为空表示取消指派） */
    @ProjectPermission(Permission.TASK_WRITE)
    @PatchMapping("/api/tasks/{id}/assign")
    public Result<TaskVO> assign(@PathVariable("id") Long id,
                                 @Valid @RequestBody TaskAssignDTO dto) {
        return Result.success("指派已更新",
                taskService.assign(id, UserContext.requireProjectId(), dto));
    }

    /**
     * 删除任务（逻辑删除）。
     * 评论与状态日志保留，看板不再显示。
     */
    @ProjectPermission(Permission.TASK_WRITE)
    @DeleteMapping("/api/tasks/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        taskService.delete(id, UserContext.requireProjectId());
        return Result.success("任务已删除", null);
    }
}
