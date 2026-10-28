package com.demo.gameasset.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.demo.gameasset.common.enums.NotificationType;
import com.demo.gameasset.common.enums.TaskColumn;
import com.demo.gameasset.entity.Notification;
import com.demo.gameasset.entity.Task;
import com.demo.gameasset.mapper.NotificationMapper;
import com.demo.gameasset.mapper.TaskMapper;
import com.demo.gameasset.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 任务临近截止提醒（模块3，文档 5.8 触发事件表最后一行）。
 *
 * <p>每天 9:05 扫一遍「明天到期且未完成」的任务，提醒负责人。
 * cron 表达式可由配置覆盖（{@code gameasset.task.due-reminder-cron}），
 * 便于在演示环境里调成每分钟跑一次来观察效果。</p>
 *
 * <h3>为什么必须有幂等判断</h3>
 * <p>定时任务是「同一条数据被反复扫到」的典型场景：服务重启、手工触发、
 * 或者将来把 cron 调密，都会让同一个任务被提醒多次。没有幂等判断的话，
 * 负责人会收到一串一模一样的通知，铃铛角标变成噪音。
 * 这里用「今天是否已经为这个任务推送过 TASK_DUE_SOON」去重——
 * 以<b>日期</b>为幂等窗口，而不是「只推一次」，这样任务明天真的还没做完时，
 * 明天会再收到一条（那是对的）。</p>
 *
 * @author 管理员
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskDueReminderTask {

    private final TaskMapper taskMapper;
    private final NotificationMapper notificationMapper;
    private final NotificationService notificationService;

    /** 每天 9:05 执行（文档 5.8） */
    @Scheduled(cron = "${gameasset.task.due-reminder-cron:0 5 9 * * ?}")
    public void remindDueTasks() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        List<Task> tasks = taskMapper.selectList(
                Wrappers.<Task>lambdaQuery()
                        .eq(Task::getDueDate, tomorrow)
                        .ne(Task::getColumnStatus, TaskColumn.FINISHED.name())
                        .isNotNull(Task::getAssigneeId));
        if (tasks.isEmpty()) {
            return;
        }

        int pushed = 0;
        for (Task task : tasks) {
            if (alreadyRemindedToday(task)) {
                continue;
            }
            // 定时任务没有登录用户，senderId 为空 → 通知显示为系统触发；
            // 「不给自己发通知」的判断在 senderId 为空时不生效，正是这里需要的
            notificationService.push(NotificationType.TASK_DUE_SOON, task.getProjectId(),
                    task.getAssigneeId(),
                    "任务即将到期：" + task.getTitle(),
                    "截止日期为明天（" + tomorrow + "）", task.getId());
            pushed++;
        }
        log.info("[定时任务] 到期提醒完成：扫描 {} 个任务，实际推送 {} 条（其余今日已提醒过）",
                tasks.size(), pushed);
    }

    /** 今天是否已为「该任务 + 该负责人」推送过到期提醒 */
    private boolean alreadyRemindedToday(Task task) {
        Long count = notificationMapper.selectCount(
                Wrappers.<Notification>lambdaQuery()
                        .eq(Notification::getUserId, task.getAssigneeId())
                        .eq(Notification::getType, NotificationType.TASK_DUE_SOON.name())
                        .eq(Notification::getBizId, task.getId())
                        .ge(Notification::getCreateTime, LocalDate.now().atStartOfDay()));
        return count != null && count > 0;
    }
}
