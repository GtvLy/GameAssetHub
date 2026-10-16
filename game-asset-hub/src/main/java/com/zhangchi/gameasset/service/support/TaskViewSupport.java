package com.zhangchi.gameasset.service.support;

import com.zhangchi.gameasset.common.enums.AssetStatus;
import com.zhangchi.gameasset.common.enums.TaskColumn;
import com.zhangchi.gameasset.common.enums.TaskPriority;
import com.zhangchi.gameasset.common.enums.TaskType;
import com.zhangchi.gameasset.entity.Asset;
import com.zhangchi.gameasset.entity.AssetCategory;
import com.zhangchi.gameasset.entity.AssetVersion;
import com.zhangchi.gameasset.entity.SysUser;
import com.zhangchi.gameasset.entity.Task;
import com.zhangchi.gameasset.mapper.AssetCategoryMapper;
import com.zhangchi.gameasset.mapper.AssetMapper;
import com.zhangchi.gameasset.mapper.AssetVersionMapper;
import com.zhangchi.gameasset.mapper.SysUserMapper;
import com.zhangchi.gameasset.mapper.TaskCommentMapper;
import com.zhangchi.gameasset.vo.TaskCardVO;
import com.zhangchi.gameasset.vo.TaskCommentCountVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务卡片装配（模块3）。
 *
 * <p><b>为什么单独一个组件</b>：看板一次要画几十上百张卡片，卡片上要显示
 * 负责人昵称、关联资产名、资产分类标签、资产状态、缩略图、评论数——
 * 每一项都要查库。逐张卡片去查就是六七个 N+1，看板会从「秒开」变成「转圈几秒」，
 * 而这正是文档 6 节「看板拖拽/列表筛选 ≤ 500ms」要守住的地方。</p>
 *
 * <p>这里把所有依赖项<b>批量</b>取齐（负责人、创建人、资产、分类、当前版本、评论数
 * 一共六次查询，与卡片数量无关），再在内存里装配。装配逻辑只有这一处，
 * 因此「卡片上的 P0 是什么颜色」「逾期怎么算」在全站是同一个答案。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskViewSupport {

    private final SysUserMapper sysUserMapper;
    private final AssetMapper assetMapper;
    private final AssetCategoryMapper assetCategoryMapper;
    private final AssetVersionMapper assetVersionMapper;
    private final TaskCommentMapper taskCommentMapper;

    /**
     * 批量装配任务卡片。
     *
     * @param projectId 项目ID，用于评论数统计（越权防护的第二道条件）
     * @param tasks     任务列表，可为空
     */
    public List<TaskCardVO> toCards(Long projectId, List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return new ArrayList<>();
        }

        Map<Long, SysUser> users = batchUsers(tasks);
        Map<Long, Asset> assets = batchAssets(tasks);
        Map<Long, AssetCategory> categories = batchCategories(assets.values());
        Map<Long, AssetVersion> currentVersions = batchCurrentVersions(assets.values());
        Map<Long, Integer> commentCounts = batchCommentCounts(projectId, tasks);

        LocalDate today = LocalDate.now();
        List<TaskCardVO> cards = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            cards.add(toCard(task, users, assets, categories, currentVersions, commentCounts, today));
        }
        return cards;
    }

    /** 装配单张卡片（新建/更新/指派后返回用），内部走同一套批量逻辑 */
    public TaskCardVO toCard(Long projectId, Task task) {
        List<TaskCardVO> cards = toCards(projectId, task == null ? List.of() : List.of(task));
        return cards.isEmpty() ? null : cards.get(0);
    }

    /**
     * 逾期判定：有截止日期、早于今天、且未完成。
     *
     * <p>「已完成的任务不算逾期」是这条规则的关键：一个上周交付、
     * 但截止日期是前天补录的任务，不该在看板上永远标红。</p>
     */
    public static boolean isOverdue(Task task, LocalDate today) {
        return task.getDueDate() != null
                && task.getDueDate().isBefore(today)
                && !TaskColumn.isFinished(task.getColumnStatus());
    }

    /** 临近截止：今天或明天到期且未完成（卡片变红提醒） */
    public static boolean isDueSoon(Task task, LocalDate today) {
        if (task.getDueDate() == null || TaskColumn.isFinished(task.getColumnStatus())) {
            return false;
        }
        LocalDate due = task.getDueDate();
        return !due.isBefore(today) && due.isBefore(today.plusDays(2));
    }

    // ==================== 装配 ====================

    private TaskCardVO toCard(Task task,
                              Map<Long, SysUser> users,
                              Map<Long, Asset> assets,
                              Map<Long, AssetCategory> categories,
                              Map<Long, AssetVersion> currentVersions,
                              Map<Long, Integer> commentCounts,
                              LocalDate today) {
        TaskCardVO vo = new TaskCardVO();
        vo.setId(task.getId());
        vo.setProjectId(task.getProjectId());
        vo.setTitle(task.getTitle());
        vo.setColumnStatus(task.getColumnStatus());
        vo.setColumnLabel(TaskColumn.label(task.getColumnStatus()));
        vo.setPriority(task.getPriority());
        vo.setPriorityLabel(TaskPriority.label(task.getPriority()));
        vo.setPriorityColor(TaskPriority.color(task.getPriority()));
        vo.setTaskType(task.getTaskType());
        vo.setTaskTypeLabel(TaskType.label(task.getTaskType()));
        vo.setTaskTypeColor(taskTypeColor(task.getTaskType()));
        vo.setAssigneeId(task.getAssigneeId());
        vo.setAssigneeName(displayName(users.get(task.getAssigneeId())));
        vo.setAssigneeAvatar(avatar(users.get(task.getAssigneeId())));
        vo.setCreatorId(task.getCreatorId());
        vo.setCreatorName(displayName(users.get(task.getCreatorId())));
        vo.setDueDate(task.getDueDate());
        vo.setOverdue(isOverdue(task, today));
        vo.setDueSoon(isDueSoon(task, today));
        vo.setEstimateHours(task.getEstimateHours());
        vo.setActualHours(task.getActualHours());
        vo.setStoryPoint(task.getStoryPoint());
        vo.setSortOrder(task.getSortOrder());
        vo.setVersion(task.getVersion());
        vo.setCommentCount(commentCounts.getOrDefault(task.getId(), 0));
        vo.setFinishedAt(task.getFinishedAt());
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateTime(task.getUpdateTime());

        Asset asset = assets.get(task.getAssetId());
        if (asset != null) {
            vo.setAssetId(asset.getId());
            vo.setAssetName(asset.getName());
            vo.setAssetNo(asset.getAssetNo());
            vo.setAssetStatus(asset.getStatus());
            vo.setAssetStatusLabel(AssetStatus.label(asset.getStatus()));
            AssetCategory category = categories.get(asset.getCategoryId());
            if (category != null) {
                vo.setAssetCategoryName(category.getName());
                vo.setAssetCategoryColor(category.getColor());
            }
            AssetVersion version = currentVersions.get(asset.getCurrentVersionId());
            if (version != null) {
                vo.setAssetThumbnailUrl(version.getThumbnailUrl());
            }
        }
        return vo;
    }

    /** 任务类型的展示色；未知类型退化成灰色，脏数据不该让看板渲染不出来 */
    private String taskTypeColor(String taskType) {
        TaskType type = TaskType.of(taskType);
        return type == null ? "#909399" : type.getColor();
    }

    // ==================== 批量取数 ====================

    private Map<Long, SysUser> batchUsers(List<Task> tasks) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Task task : tasks) {
            if (task.getAssigneeId() != null) {
                ids.add(task.getAssigneeId());
            }
            if (task.getCreatorId() != null) {
                ids.add(task.getCreatorId());
            }
        }
        return batchUsersByIds(ids);
    }

    private Map<Long, SysUser> batchUsersByIds(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return sysUserMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, Asset> batchAssets(List<Task> tasks) {
        Set<Long> ids = tasks.stream().map(Task::getAssetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        // 单资源场景下 selectBatchIds 会自动补 deleted = 0（@TableLogic），
        // 已删除的资产不会出现在卡片上，与资产详情页的行为一致
        return assetMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, AssetCategory> batchCategories(java.util.Collection<Asset> assets) {
        Set<Long> ids = assets.stream().map(Asset::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return assetCategoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(AssetCategory::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, AssetVersion> batchCurrentVersions(java.util.Collection<Asset> assets) {
        Set<Long> ids = assets.stream().map(Asset::getCurrentVersionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return assetVersionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(AssetVersion::getId, Function.identity(), (a, b) -> a));
    }

    /**
     * 批量取评论数。
     *
     * <p>没有任何一条评论是常态（新看板），此时直接跳过查询——
     * 空 {@code IN ()} 会让 SQL 语法错误，而在 Java 里提前返回既不查库也不出错。</p>
     */
    private Map<Long, Integer> batchCommentCounts(Long projectId, List<Task> tasks) {
        Set<Long> ids = tasks.stream().map(Task::getId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty() || projectId == null) {
            return Collections.emptyMap();
        }
        List<TaskCommentCountVO> counts = taskCommentMapper.countByTaskIds(projectId, ids);
        Map<Long, Integer> result = new java.util.HashMap<>();
        for (TaskCommentCountVO count : counts) {
            if (count.getTaskId() == null) {
                continue;
            }
            result.put(count.getTaskId(), count.getTotal() == null ? 0 : count.getTotal().intValue());
        }
        return result;
    }

    /** 昵称优先，为空回落到登录名；用户不存在返回 null */
    private String displayName(SysUser user) {
        if (user == null) {
            return null;
        }
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }

    private String avatar(SysUser user) {
        return user == null ? null : user.getAvatar();
    }
}
