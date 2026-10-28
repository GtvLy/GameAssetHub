package com.demo.gameasset.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.demo.gameasset.common.constant.RedisKeys;
import com.demo.gameasset.common.enums.AssetStatus;
import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.entity.Project;
import com.demo.gameasset.mapper.ProjectMapper;
import com.demo.gameasset.mapper.StatMapper;
import com.demo.gameasset.service.StatService;
import com.demo.gameasset.service.support.BurndownCalculator;
import com.demo.gameasset.service.support.ProjectAccessGuard;
import com.demo.gameasset.service.support.ProjectStatCache;
import com.demo.gameasset.service.support.StatExcelWriter;
import com.demo.gameasset.service.support.StatExportFile;
import com.demo.gameasset.service.support.WorkloadBalanceCalculator;
import com.demo.gameasset.vo.AssetCategoryStatVO;
import com.demo.gameasset.vo.AssetCompletionVO;
import com.demo.gameasset.vo.BurndownPointVO;
import com.demo.gameasset.vo.BurndownVO;
import com.demo.gameasset.vo.StatCountVO;
import com.demo.gameasset.vo.TaskTrendVO;
import com.demo.gameasset.vo.TrendPointVO;
import com.demo.gameasset.vo.WorkloadItemVO;
import com.demo.gameasset.vo.WorkloadVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计与进度可视化实现（模块4）。
 *
 * <h3>三条贯穿全类的约定</h3>
 * <ol>
 *   <li><b>先过 {@link ProjectAccessGuard}，再查数据</b>：控制器上的
 *       {@code @ProjectPermission} 已经拦了一道，这里再拦一道是为了
 *       非 HTTP 入口（定时任务、其它 Service）调用时也不漏。</li>
 *   <li><b>完成率的定义只有一处</b>：{@code AssetCompletionVO} 的注释与
 *       本类的 {@link #completionRate} 是同一个口径，
 *       项目概览页用的也是它。同一个概念出现两个数字比没有数字更糟。</li>
 *   <li><b>含「今天」的曲线不进缓存</b>：燃尽图与任务趋势每次直查。
 *       缓存它们会让用户第二天看到昨天的曲线形状——那是个
 *       「看着没错、就是不动」的 bug，排查成本极高。</li>
 * </ol>
 *
 * @author 管理员
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatServiceImpl implements StatService {

    /** 趋势窗口的默认天数 */
    public static final int TREND_DEFAULT_DAYS = 30;

    /** 趋势窗口下限：少于一周看不出趋势 */
    public static final int TREND_MIN_DAYS = 7;

    /** 趋势窗口上限：再多曲线就压在几个像素里了 */
    public static final int TREND_MAX_DAYS = 180;

    /** 导出文件名里的日期格式（避免文件名中出现冒号等非法字符） */
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StatMapper statMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAccessGuard accessGuard;
    private final ProjectStatCache statCache;
    private final BurndownCalculator burndownCalculator;
    private final WorkloadBalanceCalculator balanceCalculator;
    private final StatExcelWriter excelWriter;

    // ==================== 1. 资产完成率 ====================

    @Override
    public AssetCompletionVO assetCompletion(Long projectId) {
        requireMember(projectId);

        AssetCompletionVO cached = statCache.get(projectId,
                RedisKeys.STAT_METRIC_ASSET_COMPLETION, AssetCompletionVO.class);
        if (cached != null) {
            return cached;
        }

        List<StatCountVO> raw = statMapper.selectAssetStatusCount(projectId);
        Map<String, Integer> byStatus = new HashMap<>();
        for (StatCountVO item : raw) {
            byStatus.put(item.getKey(), item.getCount() == null ? 0 : item.getCount());
        }

        int total = 0;
        List<StatCountVO> distribution = new ArrayList<>();
        // 按枚举顺序铺满全部状态（含 0 个的状态）：
        // 环形图的图例固定显示五档，缺一档会让颜色在两次刷新之间换位置
        for (AssetStatus status : AssetStatus.values()) {
            int count = byStatus.getOrDefault(status.name(), 0);
            total += count;

            StatCountVO item = new StatCountVO();
            item.setKey(status.name());
            item.setLabel(status.getLabel());
            item.setColor(statusColor(status));
            item.setCount(count);
            distribution.add(item);
        }
        for (StatCountVO item : distribution) {
            item.setRatio(ratio(item.getCount(), total));
        }

        int approved = byStatus.getOrDefault(AssetStatus.APPROVED.name(), 0);

        AssetCompletionVO vo = new AssetCompletionVO();
        vo.setProjectId(projectId);
        vo.setAssetTotal(total);
        vo.setAssetApproved(approved);
        vo.setAssetPending(byStatus.getOrDefault(AssetStatus.PENDING.name(), 0));
        vo.setAssetInProgress(byStatus.getOrDefault(AssetStatus.IN_PROGRESS.name(), 0));
        vo.setAssetRejected(byStatus.getOrDefault(AssetStatus.REJECTED.name(), 0));
        vo.setAssetDeprecated(byStatus.getOrDefault(AssetStatus.DEPRECATED.name(), 0));
        vo.setCompletionRate(completionRate(approved, total));
        vo.setCompletionDefinition("完成率 = 已通过审核的资产数 ÷ 资产总数（"
                + "待审核与已废弃计入分母、不计入分子）");
        vo.setStatusDistribution(distribution);

        statCache.put(projectId, RedisKeys.STAT_METRIC_ASSET_COMPLETION, vo);
        return vo;
    }

    // ==================== 2. 分类分布 ====================

    @Override
    public List<AssetCategoryStatVO> assetByCategory(Long projectId) {
        requireMember(projectId);

        // 集合类型必须用 TypeReference 读缓存，否则拿到的是 List<LinkedHashMap>
        List<AssetCategoryStatVO> cached = statCache.get(projectId,
                RedisKeys.STAT_METRIC_ASSET_CATEGORY,
                new TypeReference<List<AssetCategoryStatVO>>() {
                });
        if (cached != null) {
            return cached;
        }

        List<AssetCategoryStatVO> rows = statMapper.selectCategoryStat(projectId);
        for (AssetCategoryStatVO row : rows) {
            row.setTotal(nz(row.getTotal()));
            row.setApproved(nz(row.getApproved()));
            row.setPending(nz(row.getPending()));
            row.setInProgress(nz(row.getInProgress()));
            row.setCompletionRate(completionRate(row.getApproved(), row.getTotal()));
            if (row.getColor() == null || row.getColor().isBlank()) {
                // 分类没配颜色时给一个中性色，前端不必为空值单独写分支
                row.setColor("#8a9099");
            }
        }
        statCache.put(projectId, RedisKeys.STAT_METRIC_ASSET_CATEGORY, rows);
        return rows;
    }

    // ==================== 3. 燃尽图 ====================

    /**
     * 燃尽图。
     *
     * <p><b>不缓存</b>：横轴含「今天」，缓存一天就会让第二天的曲线还是昨天
     * 画出来的那个形状（见 {@code ProjectStatCache} 的类注释）。</p>
     *
     * <p>实际线的日期范围只取 {@code [start, min(planEnd, today)]}：
     * 未来的日子不该有「实际」数据。今天早于开始日期时范围为空，
     * 实际线是空数组，而 {@code currentPoints} 退化为总故事点
     * （此时理想剩余也是总故事点，判定自然是「符合计划」）。</p>
     */
    @Override
    public BurndownVO burndown(Long projectId) {
        requireMember(projectId);

        Project project = getProject(projectId);
        LocalDate today = LocalDate.now();
        int totalPoints = nz(statMapper.sumStoryPoint(projectId));

        BurndownCalculator.Window window =
                burndownCalculator.resolveWindow(project.getStartDate(), project.getPlanEndDate(), today);

        List<BurndownPointVO> actual = new ArrayList<>();
        if (window.plannable() && window.getActualEnd() != null) {
            actual = statMapper.selectDailyRemainingPoints(
                    projectId, window.getStart(), window.getActualEnd());
        }

        BurndownVO vo = burndownCalculator.assemble(
                projectId, project.getStartDate(), project.getPlanEndDate(), today, totalPoints, actual);
        log.debug("[统计] 燃尽图 projectId={} today={} totalPoints={} 实际线点数={} 判定={}",
                projectId, today, totalPoints, actual.size(), vo.getScheduleStatus());
        return vo;
    }

    // ==================== 4. 成员工作量 ====================

    @Override
    public WorkloadVO workload(Long projectId) {
        requireMember(projectId);

        WorkloadVO cached = statCache.get(projectId,
                RedisKeys.STAT_METRIC_WORKLOAD, WorkloadVO.class);
        if (cached != null) {
            return cached;
        }

        List<WorkloadItemVO> rows = statMapper.selectWorkload(projectId);
        WorkloadVO vo = balanceCalculator.summarize(projectId, rows);
        statCache.put(projectId, RedisKeys.STAT_METRIC_WORKLOAD, vo);
        return vo;
    }

    // ==================== 5. 任务趋势 ====================

    @Override
    public TaskTrendVO taskTrend(Long projectId, Integer days) {
        requireMember(projectId);

        int window = days == null ? TREND_DEFAULT_DAYS : days;
        if (window < TREND_MIN_DAYS || window > TREND_MAX_DAYS) {
            throw BizException.badRequest("days 需在 " + TREND_MIN_DAYS + " ~ " + TREND_MAX_DAYS
                    + " 之间（当前 " + window + "）");
        }

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(window - 1L);

        // 先按日期建好骨架（LinkedHashMap 保证顺序），再把 SQL 查到的值填进去。
        // 这一步就是「空日补 0」：没有任务进出的日子不在 SQL 结果里，
        // 而折线图的类目轴必须逐日铺满，否则两天的活动会被画成相邻的两个点
        Map<LocalDate, TrendPointVO> axis = new LinkedHashMap<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            axis.put(date, new TrendPointVO(date, 0, 0));
        }
        merge(axis, statMapper.selectDailyCreated(projectId, start, end), true);
        merge(axis, statMapper.selectDailyCompleted(projectId, start, end), false);

        List<TrendPointVO> points = new ArrayList<>(axis.values());
        int totalCreated = 0;
        int totalCompleted = 0;
        for (TrendPointVO point : points) {
            totalCreated += nz(point.getCreated());
            totalCompleted += nz(point.getCompleted());
        }

        TaskTrendVO vo = new TaskTrendVO();
        vo.setProjectId(projectId);
        vo.setDays(window);
        vo.setStartDate(start);
        vo.setEndDate(end);
        vo.setPoints(points);
        vo.setTotalCreated(totalCreated);
        vo.setTotalCompleted(totalCompleted);
        vo.setNetGrowth(totalCreated - totalCompleted);
        return vo;
    }

    /** 把一条按日聚合的结果合并进骨架（{@code created} 与 {@code completed} 两条各自调用） */
    private void merge(Map<LocalDate, TrendPointVO> axis, List<TrendPointVO> rows, boolean created) {
        if (rows == null) {
            return;
        }
        for (TrendPointVO row : rows) {
            TrendPointVO target = axis.get(row.getDate());
            if (target == null) {
                // 理论上不会发生：SQL 的 BETWEEN 与服务层的日期循环用的是同一组边界。
                // 真出现了说明日历被改过，跳过比塞一个轴外点更安全
                log.warn("[统计] 趋势查询返回了窗口外的日期 {}，已忽略", row.getDate());
                continue;
            }
            if (created) {
                target.setCreated(nz(row.getCreated()));
            } else {
                target.setCompleted(nz(row.getCompleted()));
            }
        }
    }

    // ==================== 6. 导出 ====================

    @Override
    public StatExportFile export(Long projectId) {
        requireMember(projectId);
        Project project = getProject(projectId);

        AssetCompletionVO completion = assetCompletion(projectId);
        List<AssetCategoryStatVO> categories = assetByCategory(projectId);
        WorkloadVO workload = workload(projectId);
        TaskTrendVO trend = taskTrend(projectId, TREND_DEFAULT_DAYS);
        BurndownVO burndown = burndown(projectId);

        byte[] content = excelWriter.write(project.getName(), completion, categories,
                workload, trend, burndown, LocalDateTime.now());

        String fileName = safeName(project.getName()) + "-统计报表-"
                + LocalDate.now().format(FILE_DATE) + ".xlsx";
        log.info("[统计] 导出报表 projectId={} 文件名={} 字节数={}",
                projectId, fileName, content.length);
        return new StatExportFile(fileName, content);
    }

    // ==================== 内部工具 ====================

    /**
     * 第二道权限校验。控制器上的 {@code @ProjectPermission(STAT_READ)} 是第一道，
     * 这里保证 Service 被非 HTTP 入口调用时同样拦得住——两道缺一不可，
     * 因为「越权访问统计数字」与「越权改数据」在数据泄露上是同一件事。
     */
    private void requireMember(Long projectId) {
        accessGuard.requireActiveMember(projectId, UserContext.requireUserId());
    }

    /** 按主键查项目；查不到（含已归档）即 404 */
    private Project getProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw BizException.notFound("项目不存在或已归档");
        }
        return project;
    }

    /**
     * 完成率（%），保留一位小数；分母为 0 时返回 0.0。
     * 口径与 {@code ProjectServiceImpl#completionRate} 一致——
     * 概览页与统计页上的「资产完成率」必须是同一个数字。
     */
    private Double completionRate(int approved, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(approved * 100.0 / total)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 占比（%），保留一位小数；总数为 0 时返回 0.0 */
    private Double ratio(int count, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(count * 100.0 / total)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * 资产状态在图表里的颜色。
     *
     * <p>与前端 {@code AssetStatusTag} 的配色语义一致，但刻意在服务端下发：
     * 环形图的图例颜色必须与列表页标签颜色一致，两处各写一份配色，
     * 改一处忘一处就会出现「同一个状态在列表里是绿的、在环形图里是蓝的」。</p>
     */
    private String statusColor(AssetStatus status) {
        return switch (status) {
            case IN_PROGRESS -> "#909399";
            case PENDING -> "#e6a23c";
            case APPROVED -> "#67c23a";
            case REJECTED -> "#f56c6c";
            case DEPRECATED -> "#5b8cff";
        };
    }

    /** 文件名里去掉路径分隔符等非法字符，避免某些客户端把文件名当成路径 */
    private String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "项目";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
