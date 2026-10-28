package com.demo.gameasset.controller;

import com.demo.gameasset.common.annotation.ProjectPermission;
import com.demo.gameasset.common.enums.Permission;
import com.demo.gameasset.common.result.Result;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.dto.NotificationQueryDTO;
import com.demo.gameasset.service.NotificationService;
import com.demo.gameasset.vo.NotificationVO;
import com.demo.gameasset.vo.PageResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知接口（模块3，文档 4.2）。
 *
 * <p><b>权限点为什么复用 {@code TASK_READ}</b>：通知没有一个独立的权限点，
 * 而它确实属于「项目内的协作信息」——VIEWER 应该能看到自己收到的通知
 * （别人 @ 了他，他必须收得到），但没有 {@code TASK_WRITE} 就不能发评论、
 * 也就不会触发通知。再加一个 {@code NOTIFY_READ} 权限点只会让权限矩阵
 * 多一列，而这一列在五个角色上是完全一样的。</p>
 *
 * <p><b>四条路径都没有项目段</b>，项目上下文来自 {@code X-Project-Id}：
 * 不带头会得到明确的 400（而不是把通知混在一起返回），
 * 带了不属于自己的项目头会得到 403。</p>
 *
 * @author 管理员
 */
@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 我的通知（分页，可筛未读，按当前项目过滤） */
    @ProjectPermission(Permission.TASK_READ)
    @GetMapping("/api/notifications")
    public Result<PageResultVO<NotificationVO>> page(NotificationQueryDTO query) {
        Long userId = UserContext.requireUserId();
        return Result.success(notificationService.page(
                UserContext.requireProjectId(), userId, query));
    }

    /**
     * 未读数（铃铛角标每 30 秒轮询）。
     *
     * <p>刻意排在 {@code /{id}/read} 之前声明：{@code unread-count} 与
     * {@code {id}} 都占一段，先声明的字面量路径优先匹配，
     * 否则会被当成 {@code id = "unread-count"} 而绑定失败。</p>
     */
    @ProjectPermission(Permission.TASK_READ)
    @GetMapping("/api/notifications/unread-count")
    public Result<Long> unreadCount() {
        Long userId = UserContext.requireUserId();
        return Result.success(notificationService.unreadCount(
                UserContext.requireProjectId(), userId));
    }

    /** 标记单条已读 */
    @ProjectPermission(Permission.TASK_READ)
    @PatchMapping("/api/notifications/{id}/read")
    public Result<NotificationVO> markRead(@PathVariable("id") Long id) {
        Long userId = UserContext.requireUserId();
        return Result.success("已标记为已读",
                notificationService.markRead(UserContext.requireProjectId(), userId, id));
    }

    /** 全部标记已读，返回影响的条数 */
    @ProjectPermission(Permission.TASK_READ)
    @PatchMapping("/api/notifications/read-all")
    public Result<Integer> markAllRead() {
        Long userId = UserContext.requireUserId();
        int affected = notificationService.markAllRead(UserContext.requireProjectId(), userId);
        return Result.success("全部已读", affected);
    }
}
