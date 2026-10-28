package com.demo.gameasset.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.gameasset.common.enums.NotificationType;
import com.demo.gameasset.common.enums.ProjectMemberStatus;
import com.demo.gameasset.common.enums.TaskColumn;
import com.demo.gameasset.common.enums.TaskPriority;
import com.demo.gameasset.common.enums.TaskType;
import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.common.result.ResultCode;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.dto.TaskAssignDTO;
import com.demo.gameasset.dto.TaskCreateDTO;
import com.demo.gameasset.dto.TaskMoveDTO;
import com.demo.gameasset.dto.TaskQueryDTO;
import com.demo.gameasset.dto.TaskUpdateDTO;
import com.demo.gameasset.entity.Asset;
import com.demo.gameasset.entity.ProjectMember;
import com.demo.gameasset.entity.SysUser;
import com.demo.gameasset.entity.Task;
import com.demo.gameasset.entity.TaskStatusLog;
import com.demo.gameasset.mapper.AssetMapper;
import com.demo.gameasset.mapper.ProjectMemberMapper;
import com.demo.gameasset.mapper.SysUserMapper;
import com.demo.gameasset.mapper.TaskMapper;
import com.demo.gameasset.mapper.TaskStatusLogMapper;
import com.demo.gameasset.service.NotificationService;
import com.demo.gameasset.service.TaskCommentService;
import com.demo.gameasset.service.TaskService;
import com.demo.gameasset.service.support.ProjectOverviewCache;
import com.demo.gameasset.service.support.ProjectStatCache;
import com.demo.gameasset.service.support.TaskSortOrderCalculator;
import com.demo.gameasset.service.support.TaskViewSupport;
import com.demo.gameasset.vo.BoardColumnVO;
import com.demo.gameasset.vo.BoardVO;
import com.demo.gameasset.vo.PageResultVO;
import com.demo.gameasset.vo.TaskCardVO;
import com.demo.gameasset.vo.TaskMoveVO;
import com.demo.gameasset.vo.TaskStatusLogVO;
import com.demo.gameasset.vo.TaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务看板与协作实现（模块3 的核心）。
 *
 * <h3>三条贯穿全类的约定</h3>
 * <ol>
 *   <li><b>每条查询都带 {@code project_id}</b>——包括「按主键查一条任务」。
 *       模块1 建立的第二层防护在这里同样是硬要求：
 *       只按 task_id 查再在 Java 里比项目，一旦漏比就是跨项目读写别人的任务。</li>
 *   <li><b>改列只有 {@link #move} 一条路径</b>——排序值、{@code finished_at}、
 *       状态日志、状态变更通知四件事必须一起发生。多一条路径就多一处会漏。</li>
 *   <li><b>写操作一律走乐观锁并区分 409</b>——返回 0 行意味着「你手里的版本已经过期」，
 *       要明确告诉前端刷新，而不是当作成功。</li>
 * </ol>
 *
 * @author 管理员
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    private final TaskMapper taskMapper;
    private final TaskStatusLogMapper taskStatusLogMapper;
    private final TaskCommentService taskCommentService;
    private final TaskViewSupport viewSupport;
    private final ProjectMemberMapper projectMemberMapper;
    private final SysUserMapper sysUserMapper;
    private final AssetMapper assetMapper;
    private final NotificationService notificationService;
    private final ProjectOverviewCache overviewCache;

    /**
     * 统计图表缓存（模块4）。这里单独注入它是因为
     * {@code assign} 是唯一「改了成员维度数据、却没改概览公共指标」的写操作：
     * 改指派人不会让任务总数 / 已完成数变，所以 {@code overviewCache.evict}
     * 那条通道覆盖不到它，而成员工作量图会因此变旧。
     * 其余写路径都通过 {@code ProjectOverviewCache#evict} 连带失效。
     */
    private final ProjectStatCache statCache;

    // ==================== 看板 ====================

    @Override
    public BoardVO board(Long projectId) {
        // 一次查询取全项目任务，按「列内顺序」排好；分组在内存里做，
        // 不写四条 SQL 分别查四列——那样四列的排序基准可能来自不同时刻
        List<Task> tasks = taskMapper.selectList(
                Wrappers.<Task>lambdaQuery()
                        .eq(Task::getProjectId, projectId)
                        .orderByAsc(Task::getSortOrder)
                        .orderByAsc(Task::getId));

        List<TaskCardVO> cards = viewSupport.toCards(projectId, tasks);

        Map<String, List<TaskCardVO>> grouped = new LinkedHashMap<>();
        for (TaskColumn column : TaskColumn.ordered()) {
            grouped.put(column.name(), new ArrayList<>());
        }
        for (TaskCardVO card : cards) {
            List<TaskCardVO> bucket = grouped.get(card.getColumnStatus());
            if (bucket == null) {
                // 列名不在枚举内（脏数据 / 手工改库）：归到「待办」而不是丢弃。
                // 丢弃的表现是这张卡片在界面上彻底消失，用户只能靠改库找回来
                log.warn("[看板] 任务 {} 的列名 {} 非法，已归入待办列", card.getId(), card.getColumnStatus());
                bucket = grouped.get(TaskColumn.DEFAULT.name());
            }
            bucket.add(card);
        }

        List<BoardColumnVO> columns = new ArrayList<>(TaskColumn.ordered().size());
        int total = 0;
        for (TaskColumn column : TaskColumn.ordered()) {
            List<TaskCardVO> bucket = grouped.get(column.name());
            BoardColumnVO vo = new BoardColumnVO();
            vo.setStatus(column.name());
            vo.setTitle(column.getLabel());
            vo.setColor(column.getColor());
            vo.setTasks(bucket);
            vo.setTaskCount(bucket.size());
            vo.setStoryPointTotal(bucket.stream()
                    .mapToInt(card -> card.getStoryPoint() == null ? 0 : card.getStoryPoint())
                    .sum());
            total += bucket.size();
            columns.add(vo);
        }

        BoardVO board = new BoardVO();
        board.setProjectId(projectId);
        board.setColumns(columns);
        board.setTotalTasks(total);
        return board;
    }

    // ==================== 列表 ====================

    @Override
    public PageResultVO<TaskCardVO> page(Long projectId, TaskQueryDTO query) {
        TaskQueryDTO criteria = query == null ? new TaskQueryDTO() : query;
        int pageNo = criteria.normalizedPage();
        int pageSize = criteria.normalizedSize();

        LambdaQueryWrapper<Task> wrapper = Wrappers.<Task>lambdaQuery()
                .eq(Task::getProjectId, projectId)
                .eq(StringUtils.hasText(criteria.getColumnStatus()), Task::getColumnStatus,
                        upper(criteria.getColumnStatus()))
                .eq(criteria.getAssigneeId() != null, Task::getAssigneeId, criteria.getAssigneeId())
                .eq(criteria.getCreatorId() != null, Task::getCreatorId, criteria.getCreatorId())
                .eq(criteria.getAssetId() != null, Task::getAssetId, criteria.getAssetId())
                .eq(StringUtils.hasText(criteria.getPriority()), Task::getPriority, upper(criteria.getPriority()))
                .eq(StringUtils.hasText(criteria.getTaskType()), Task::getTaskType, upper(criteria.getTaskType()))
                // 只看未完成：燃尽图与「我的待办」的口径都建立在 DONE 即完成之上
                .ne(criteria.onlyUnfinished(), Task::getColumnStatus, TaskColumn.FINISHED.name())
                .like(StringUtils.hasText(criteria.getKeyword()), Task::getTitle,
                        criteria.getKeyword() == null ? null : criteria.getKeyword().trim());
        if (criteria.onlyOverdue()) {
            wrapper.lt(Task::getDueDate, LocalDate.now())
                    .ne(Task::getColumnStatus, TaskColumn.FINISHED.name());
        }
        applyOrder(wrapper, criteria.getOrderBy());

        IPage<Task> result = taskMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return PageResultVO.of(viewSupport.toCards(projectId, result.getRecords()),
                result.getTotal(), pageNo, pageSize);
    }

    /**
     * 排序方式。
     *
     * <p>默认与看板列内一致（{@code sort_order ASC, id ASC}）：任务列表页
     * 常常是按列筛选后再看，此时它与看板上看到的先后顺序完全相同，
     * 而「按列分组的逻辑顺序」交给筛选参数表达，不靠 SQL 里的
     * {@code FIELD()} 硬编码——那种写法把枚举顺序复制进了 SQL，
     * 枚举一改就两边不一致。</p>
     */
    private void applyOrder(LambdaQueryWrapper<Task> wrapper, String orderBy) {
        if ("dueDate".equalsIgnoreCase(orderBy)) {
            wrapper.orderByAsc(Task::getDueDate).orderByAsc(Task::getId);
        } else if ("updateTime".equalsIgnoreCase(orderBy)) {
            wrapper.orderByDesc(Task::getUpdateTime).orderByDesc(Task::getId);
        } else {
            wrapper.orderByAsc(Task::getSortOrder).orderByAsc(Task::getId);
        }
    }

    // ==================== 创建 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskVO create(Long projectId, TaskCreateDTO dto) {
        Long userId = UserContext.requireUserId();

        TaskColumn column = parseColumn(dto.getColumnStatus() == null
                ? TaskColumn.DEFAULT.name() : dto.getColumnStatus());
        String priority = dto.getPriority() == null ? TaskPriority.DEFAULT.name()
                : requirePriority(dto.getPriority());
        String taskType = dto.getTaskType() == null ? TaskType.DEFAULT.name()
                : requireTaskType(dto.getTaskType());
        requireAssignee(projectId, dto.getAssigneeId());
        requireAsset(projectId, dto.getAssetId());

        Task task = new Task();
        task.setProjectId(projectId);
        task.setAssetId(dto.getAssetId());
        task.setTitle(dto.getTitle().trim());
        task.setDescription(dto.getDescription());
        task.setColumnStatus(column.name());
        task.setPriority(priority);
        task.setTaskType(taskType);
        task.setAssigneeId(dto.getAssigneeId());
        task.setCreatorId(userId);
        task.setDueDate(dto.getDueDate());
        task.setEstimateHours(dto.getEstimateHours());
        task.setStoryPoint(dto.getStoryPoint());
        task.setSortOrder(nextSortOrder(projectId, column));
        // 直接建到「已完成」时补上完成时间：否则燃尽图会把它算作未完成，
        // 而看板上它明明躺在 DONE 列里
        if (column == TaskColumn.FINISHED) {
            task.setFinishedAt(LocalDateTime.now());
        }
        taskMapper.insert(task);

        // 新建即落一条状态日志（from 为空），模块4 回放历史时才有起点
        writeStatusLog(projectId, task.getId(), null, column.name(), userId);

        notificationService.push(NotificationType.TASK_ASSIGNED, projectId, task.getAssigneeId(),
                "有新任务指派给你：" + task.getTitle(),
                deadlineText(priority, dto.getDueDate()), task.getId());

        overviewCache.evict(projectId);
        log.info("[任务] 创建成功 taskId={} projectId={} column={} assignee={} operator={}",
                task.getId(), projectId, column, dto.getAssigneeId(), userId);
        return detail(task.getId(), projectId);
    }

    // ==================== 详情 ====================

    @Override
    public TaskVO detail(Long taskId, Long projectId) {
        Task task = getTaskInProject(taskId, projectId);
        TaskVO vo = new TaskVO();
        TaskCardVO card = viewSupport.toCard(projectId, task);
        if (card != null) {
            // 卡片与详情共用的字段一次拷过来：以后卡片加字段，详情自动跟上
            BeanUtils.copyProperties(card, vo);
        }
        vo.setDescription(task.getDescription());
        vo.setComments(taskCommentService.list(taskId, projectId));
        vo.setStatusLogs(statusLogs(taskId, projectId));
        return vo;
    }

    /** 状态变更日志（最近的在前） */
    private List<TaskStatusLogVO> statusLogs(Long taskId, Long projectId) {
        List<TaskStatusLog> logs = taskStatusLogMapper.selectList(
                Wrappers.<TaskStatusLog>lambdaQuery()
                        .eq(TaskStatusLog::getProjectId, projectId)
                        .eq(TaskStatusLog::getTaskId, taskId)
                        .orderByDesc(TaskStatusLog::getCreateTime)
                        .orderByDesc(TaskStatusLog::getId));
        if (logs.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> operatorIds = logs.stream().map(TaskStatusLog::getOperatorId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SysUser> operators = operatorIds.isEmpty() ? Collections.emptyMap()
                : sysUserMapper.selectBatchIds(operatorIds).stream()
                        .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));

        List<TaskStatusLogVO> result = new ArrayList<>(logs.size());
        for (TaskStatusLog entry : logs) {
            TaskStatusLogVO vo = new TaskStatusLogVO();
            vo.setId(entry.getId());
            vo.setTaskId(entry.getTaskId());
            vo.setFromStatus(entry.getFromStatus());
            vo.setFromLabel(entry.getFromStatus() == null ? null : TaskColumn.label(entry.getFromStatus()));
            vo.setToStatus(entry.getToStatus());
            vo.setToLabel(TaskColumn.label(entry.getToStatus()));
            vo.setOperatorId(entry.getOperatorId());
            vo.setOperatorName(displayName(operators.get(entry.getOperatorId())));
            vo.setCreateTime(entry.getCreateTime());
            result.add(vo);
        }
        return result;
    }

    // ==================== 更新 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskVO update(Long taskId, Long projectId, TaskUpdateDTO dto) {
        Task task = getTaskInProject(taskId, projectId);

        String priority = StringUtils.hasText(dto.getPriority())
                ? requirePriority(dto.getPriority()) : task.getPriority();
        String taskType = StringUtils.hasText(dto.getTaskType())
                ? requireTaskType(dto.getTaskType()) : task.getTaskType();
        requireAssignee(projectId, dto.getAssigneeId());
        requireAsset(projectId, dto.getAssetId());

        boolean assigneeChanged = !Objects.equals(task.getAssigneeId(), dto.getAssigneeId());

        LambdaUpdateWrapper<Task> update = Wrappers.<Task>lambdaUpdate()
                .set(Task::getTitle, dto.getTitle().trim())
                .set(Task::getDescription, dto.getDescription())
                .set(Task::getPriority, priority)
                .set(Task::getTaskType, taskType)
                .set(Task::getAssigneeId, dto.getAssigneeId())
                .set(Task::getDueDate, dto.getDueDate())
                .set(Task::getEstimateHours, dto.getEstimateHours())
                .set(Task::getActualHours, dto.getActualHours())
                .set(Task::getStoryPoint, dto.getStoryPoint())
                .set(Task::getAssetId, dto.getAssetId());
        applyGuardedUpdate(taskId, projectId, task.getVersion(), update);

        if (assigneeChanged) {
            notificationService.push(NotificationType.TASK_ASSIGNED, projectId, dto.getAssigneeId(),
                    "任务已转派给你：" + dto.getTitle().trim(),
                    deadlineText(priority, dto.getDueDate()), taskId);
        }

        overviewCache.evict(projectId);
        log.info("[任务] 更新成功 taskId={} projectId={} 负责人变更={} operator={}",
                taskId, projectId, assigneeChanged, UserContext.getUserId());
        return detail(taskId, projectId);
    }

    // ==================== 拖拽 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskMoveVO move(Long taskId, Long projectId, TaskMoveDTO dto) {
        Long userId = UserContext.requireUserId();
        Task task = getTaskInProject(taskId, projectId);
        TaskColumn to = parseColumn(dto.getToColumn());

        List<Task> columnTasks = taskMapper.selectList(
                Wrappers.<Task>lambdaQuery()
                        .eq(Task::getProjectId, projectId)
                        .eq(Task::getColumnStatus, to.name())
                        .orderByAsc(Task::getSortOrder)
                        .orderByAsc(Task::getId));

        // 落位计算：prev/next 认不出来时会按列内实际相邻关系给出合理位置，
        // 而不是让这次拖拽失败（文档 5.4）
        TaskSortOrderCalculator.Placement placement = TaskSortOrderCalculator.place(
                columnTasks, taskId, dto.getPrevTaskId(), dto.getNextTaskId());

        // 整列重排：先摆正其他卡片，再动自己。
        // 两步在同一个事务里，落位失败（409）时重排一起回滚，不会留下半列错位
        placement.getReorders().forEach((id, order) -> taskMapper.updateSortOrder(id, projectId, order));

        boolean columnChanged = !to.name().equals(task.getColumnStatus());
        LocalDateTime finishedAt = null;
        if (to == TaskColumn.FINISHED) {
            // 在 DONE 列内部重排不该把完成时间改成现在，否则完成时间会漂移
            finishedAt = task.getFinishedAt() == null ? LocalDateTime.now() : task.getFinishedAt();
        }
        // 拖出 DONE 时 finishedAt 显式为 null：事务要把它清空，
        // 否则燃尽图会把已回到进行中的任务继续算作完成

        LambdaUpdateWrapper<Task> update = Wrappers.<Task>lambdaUpdate()
                .set(Task::getColumnStatus, to.name())
                .set(Task::getSortOrder, placement.getSortOrder())
                .set(Task::getFinishedAt, finishedAt);
        applyGuardedUpdate(taskId, projectId, dto.getVersion(), update);

        if (columnChanged) {
            writeStatusLog(projectId, taskId, task.getColumnStatus(), to.name(), userId);
            notificationService.push(NotificationType.TASK_STATUS, projectId, task.getCreatorId(),
                    "任务状态变更：" + task.getTitle(),
                    TaskColumn.label(task.getColumnStatus()) + " → " + to.getLabel(), taskId);
        }

        overviewCache.evict(projectId);
        log.info("[任务] 拖拽落位 taskId={} {} → {} sortOrder={} 重排 {} 条 operator={}",
                taskId, task.getColumnStatus(), to, placement.getSortOrder(),
                placement.getReorders().size(), userId);

        TaskMoveVO vo = new TaskMoveVO();
        vo.setId(taskId);
        vo.setTitle(task.getTitle());
        vo.setColumnStatus(to.name());
        vo.setColumnLabel(to.getLabel());
        vo.setSortOrder(placement.getSortOrder());
        // 版本号必须回传：乐观锁每次写操作 +1，前端不回填的话
        // 同一张卡片连续拖第二次必然 409
        vo.setVersion(normalizedVersion(dto.getVersion()) + 1);
        vo.setFinishedAt(finishedAt);
        vo.setRebalanced(placement.isRebalanced());
        vo.setReorderedCount(placement.getReorders().size());
        return vo;
    }

    // ==================== 指派 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskVO assign(Long taskId, Long projectId, TaskAssignDTO dto) {
        Task task = getTaskInProject(taskId, projectId);
        requireAssignee(projectId, dto.getAssigneeId());

        if (!Objects.equals(task.getAssigneeId(), dto.getAssigneeId())) {
            LambdaUpdateWrapper<Task> update = Wrappers.<Task>lambdaUpdate()
                    .set(Task::getAssigneeId, dto.getAssigneeId());
            applyGuardedUpdate(taskId, projectId, task.getVersion(), update);

            notificationService.push(NotificationType.TASK_ASSIGNED, projectId, dto.getAssigneeId(),
                    "任务已指派给你：" + task.getTitle(),
                    deadlineText(task.getPriority(), task.getDueDate()), taskId);

            // 指派只改变「谁在做」，不影响概览上的任务公共指标（总数/已完成/逾期），
            // 所以这里走不了 overviewCache.evict 那条通道；但模块4 的成员工作量图
            // 是按指派人聚合的，必须单独失效一次，否则换了负责人之后统计图还是旧的分工
            statCache.evict(projectId);

            log.info("[任务] 指派 taskId={} {} → {} operator={}",
                    taskId, task.getAssigneeId(), dto.getAssigneeId(), UserContext.getUserId());
        }
        return detail(taskId, projectId);
    }

    // ==================== 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long taskId, Long projectId) {
        // 先按 task_id + project_id 确认归属，再按主键删除。
        // deleteById 只认主键，少了上面这一步就是「任何项目的任务都能被删掉」
        Task task = getTaskInProject(taskId, projectId);
        taskMapper.deleteById(taskId);

        // 评论与状态日志刻意不删：任务被删了，但「当时谁推进到哪一步」是复盘材料。
        // 看板查不到它（@TableLogic 自动补 deleted = 0），历史仍然完整
        overviewCache.evict(projectId);
        log.info("[任务] 已删除（逻辑删除）taskId={} projectId={} 标题={} operator={}",
                taskId, projectId, task.getTitle(), UserContext.getUserId());
    }

    // ==================== 资产关联 ====================

    @Override
    public List<TaskCardVO> relatedTasks(Long projectId, Long assetId) {
        if (assetId == null) {
            return new ArrayList<>();
        }
        List<Task> tasks = taskMapper.selectList(
                Wrappers.<Task>lambdaQuery()
                        .eq(Task::getProjectId, projectId)
                        .eq(Task::getAssetId, assetId)
                        .orderByAsc(Task::getSortOrder)
                        .orderByAsc(Task::getId));
        return viewSupport.toCards(projectId, tasks);
    }

    // ==================== 内部工具 ====================

    /**
     * 带乐观锁的条件更新：版本对不上返回 0 行，转成 409。
     *
     * <p><b>为什么显式写 {@code version} 条件，而不是依赖 {@code @Version} 插件</b>：
     * 这里需要「失败 = 版本过期」这个精确语义，好转换成 409 让前端刷新。
     * 走插件时更新失败与「条件没命中」无法区分，只能一律当成功——
     * 那正是「静默覆盖别人的改动」。
     * （实体上的 {@code @Version} 仍然保留：任何走 {@code updateById} 的将来代码
     * 都会自动获得同样的保护。）</p>
     */
    private void applyGuardedUpdate(Long taskId, Long projectId, Integer expectedVersion,
                                    LambdaUpdateWrapper<Task> update) {
        int version = normalizedVersion(expectedVersion);
        update.eq(Task::getId, taskId)
                .eq(Task::getProjectId, projectId)
                .eq(Task::getVersion, version)
                .set(Task::getVersion, version + 1);
        // 传 null 实体：让 @Version 插件不介入，条件完全由上面这一句决定
        int rows = taskMapper.update(null, update);
        if (rows == 0) {
            throw new BizException(ResultCode.CONFLICT, "任务已被他人修改，请刷新看板后重试");
        }
    }

    /** 版本号兜底：DDL 是 NOT NULL DEFAULT 0，这里只是防御历史脏数据 */
    private int normalizedVersion(Integer version) {
        return version == null ? 0 : version;
    }

    /** 取本项目内的任务，查不到 404。查询带 project_id，跨项目在这里就断了 */
    private Task getTaskInProject(Long taskId, Long projectId) {
        if (taskId == null) {
            throw BizException.badRequest("请指定任务");
        }
        Task task = taskMapper.selectOne(
                Wrappers.<Task>lambdaQuery()
                        .eq(Task::getId, taskId)
                        .eq(Task::getProjectId, projectId));
        if (task == null) {
            throw BizException.notFound("任务不存在或不属于当前项目");
        }
        return task;
    }

    private void writeStatusLog(Long projectId, Long taskId, String from, String to, Long operatorId) {
        TaskStatusLog entry = new TaskStatusLog();
        entry.setTaskId(taskId);
        entry.setProjectId(projectId);
        entry.setFromStatus(from);
        entry.setToStatus(to);
        entry.setOperatorId(operatorId);
        taskStatusLogMapper.insert(entry);
    }

    private TaskColumn parseColumn(String value) {
        TaskColumn column = TaskColumn.of(value);
        if (column == null) {
            throw BizException.badRequest("非法的看板列：" + value + "，可选值：" + TaskColumn.all());
        }
        return column;
    }

    private String requirePriority(String value) {
        if (!TaskPriority.isValid(value)) {
            throw BizException.badRequest("非法的优先级：" + value + "，可选值：" + TaskPriority.all());
        }
        return upper(value);
    }

    private String requireTaskType(String value) {
        if (!TaskType.isValid(value)) {
            throw BizException.badRequest("非法的任务类型：" + value + "，可选值：" + TaskType.all());
        }
        return upper(value);
    }

    /**
     * 指派对象必须是该项目的 ACTIVE 成员。
     *
     * <p>不校验的后果不是「显示个陌生名字」这么轻：被指派的人打不开这个项目，
     * 任务会烂在一个人根本看不到的看板上。空值表示取消指派，直接放行。</p>
     */
    private void requireAssignee(Long projectId, Long assigneeId) {
        if (assigneeId == null) {
            return;
        }
        Long count = projectMemberMapper.selectCount(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, assigneeId)
                        .eq(ProjectMember::getStatus, ProjectMemberStatus.ACTIVE));
        if (count == null || count == 0L) {
            throw BizException.badRequest("指派对象不是该项目成员，无法指派");
        }
    }

    /** 关联资产必须属于本项目（文档 5.5）。空值表示不关联，直接放行 */
    private void requireAsset(Long projectId, Long assetId) {
        if (assetId == null) {
            return;
        }
        Asset asset = assetMapper.selectOne(
                Wrappers.<Asset>lambdaQuery()
                        .eq(Asset::getId, assetId)
                        .eq(Asset::getProjectId, projectId));
        if (asset == null) {
            throw BizException.badRequest("关联的资产不存在或不属于当前项目");
        }
    }

    /** 追加到列尾：当前列最大排序值 + 一个步长；空列从 1000 起 */
    private int nextSortOrder(Long projectId, TaskColumn column) {
        Task last = taskMapper.selectOne(
                Wrappers.<Task>lambdaQuery()
                        .eq(Task::getProjectId, projectId)
                        .eq(Task::getColumnStatus, column.name())
                        .orderByDesc(Task::getSortOrder)
                        .orderByDesc(Task::getId)
                        .last("LIMIT 1"));
        if (last == null || last.getSortOrder() == null) {
            return TaskSortOrderCalculator.STEP;
        }
        return last.getSortOrder() + TaskSortOrderCalculator.STEP;
    }

    /** 指派类通知的正文：优先级 + 截止日期，接手的人一眼看到最要紧的两件事 */
    private String deadlineText(String priority, LocalDate dueDate) {
        StringBuilder text = new StringBuilder("优先级 ").append(TaskPriority.label(priority));
        if (dueDate != null) {
            text.append("，截止 ").append(dueDate);
        }
        return text.toString();
    }

    private String displayName(SysUser user) {
        if (user == null) {
            return null;
        }
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }
}
