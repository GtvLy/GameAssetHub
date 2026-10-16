package com.zhangchi.gameasset.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhangchi.gameasset.common.enums.TaskColumn;
import com.zhangchi.gameasset.entity.Task;
import com.zhangchi.gameasset.mapper.TaskMapper;
import com.zhangchi.gameasset.vo.ProjectOverviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目概览里的任务指标与「我的待办」（模块3 对模块1 的交付）。
 *
 * <p>与 {@code AssetOverviewSupport} 的分工完全对称：
 * {@link #fill} 填的是<b>项目维度</b>的公共指标（任务总数 / 已完成 / 已逾期），
 * 由概览缓存在写缓存前调用，因此这些指标可以安心走 Redis 缓存。</p>
 *
 * <h3>为什么 myTasks 不在 fill 里</h3>
 * <p>{@code myTasks} 是<b>用户维度</b>数据。模块1 的 {@code ProjectOverviewVO}
 * 注释里已经点明了这个坑：整份 VO 按项目维度缓存，若把 A 的待办放了进去，
 * B 打开概览就会看到 A 的任务清单。所以它由 {@link #withMyTasks} 在
 * <b>缓存之外</b>单独查、单独挂——每次请求都查，不进缓存。</p>
 *
 * <p>会话上下文里没有登录用户时（例如定时任务或内部调用）直接原样返回 base，
 * 不报错：概览的公共部分本来就与登录用户无关。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskOverviewSupport {

    /** 概览页「我的待办」条数（文档 5.9 取 5） */
    public static final int MY_TASK_LIMIT = 5;

    private final TaskMapper taskMapper;
    private final TaskViewSupport viewSupport;

    /**
     * 把项目维度的任务指标填进概览 VO（原地修改，可安全进缓存）。
     *
     * @param overview  模块1 已经填好项目基础信息的 VO
     * @param projectId 项目ID
     */
    public void fill(ProjectOverviewVO overview, Long projectId) {
        if (overview == null || projectId == null) {
            return;
        }
        overview.setTaskTotal(countTasks(projectId));
        overview.setTaskDone(countByColumn(projectId, TaskColumn.FINISHED));
        overview.setTaskOverdue(countOverdue(projectId));
    }

    /**
     * 在概览上挂「我的待办」。
     *
     * <p>返回<b>副本</b>而不是原地修改：base 可能就是 Redis 里那一份对象
     * （{@code ProjectOverviewCache#get} 直接返回缓存反序列化后的实例），
     * 原地改等于把当前用户的待办写进了共享缓存。</p>
     *
     * <p>{@code userId} 为空时原样返回 base：没有登录用户就没有「我的待办」，
     * 这也让「概览不依赖登录上下文」这条既有性质（模块1 的测试守着它）继续成立。</p>
     */
    public ProjectOverviewVO withMyTasks(ProjectOverviewVO base, Long projectId, Long userId) {
        if (base == null || projectId == null || userId == null) {
            return base;
        }
        ProjectOverviewVO result = new ProjectOverviewVO();
        BeanUtils.copyProperties(base, result);
        result.setMyTasks(new ArrayList<>(myOpenTasks(projectId, userId)));
        return result;
    }

    /**
     * 我的未完成任务（最多 5 条），急的排在前面。
     *
     * <p>排序：截止日期早的在前，没填截止日期的排最后（{@code due_date IS NULL}
     * 在 MySQL 里升序为 0，正好把有日期的排前面），同一天的按列内顺序。
     * 概览是「今天该干什么」的入口，把没日期的排前面就失去了意义。</p>
     */
    public List<Object> myOpenTasks(Long projectId, Long userId) {
        List<Task> tasks = taskMapper.selectList(
                Wrappers.<Task>lambdaQuery()
                        .eq(Task::getProjectId, projectId)
                        .eq(Task::getAssigneeId, userId)
                        .ne(Task::getColumnStatus, TaskColumn.FINISHED.name())
                        .last("ORDER BY (due_date IS NULL) ASC, due_date ASC, sort_order ASC, id ASC"
                                + " LIMIT " + MY_TASK_LIMIT));
        return new ArrayList<>(viewSupport.toCards(projectId, tasks));
    }

    /** 项目内任务总数 */
    public int countTasks(Long projectId) {
        Long count = taskMapper.selectCount(
                Wrappers.<Task>lambdaQuery().eq(Task::getProjectId, projectId));
        return count == null ? 0 : count.intValue();
    }

    /** 项目内某一列的任务数 */
    public int countByColumn(Long projectId, TaskColumn column) {
        Long count = taskMapper.selectCount(
                Wrappers.<Task>lambdaQuery()
                        .eq(Task::getProjectId, projectId)
                        .eq(Task::getColumnStatus, column.name()));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 已逾期任务数：有截止日期、早于今天、且未完成。
     *
     * <p>「未完成」这个条件不能少。少了它，一个上个月就交付、
     * 但截止日期填的是前天的任务会永远挂在「逾期 1」上，
     * 看板上却找不到任何红色卡片——用户只会认为概览的数字是错的。</p>
     */
    public int countOverdue(Long projectId) {
        Long count = taskMapper.selectCount(
                Wrappers.<Task>lambdaQuery()
                        .eq(Task::getProjectId, projectId)
                        .lt(Task::getDueDate, LocalDate.now())
                        .ne(Task::getColumnStatus, TaskColumn.FINISHED.name()));
        return count == null ? 0 : count.intValue();
    }
}
