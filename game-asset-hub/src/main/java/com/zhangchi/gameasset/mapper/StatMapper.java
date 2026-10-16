package com.zhangchi.gameasset.mapper;

import com.zhangchi.gameasset.vo.AssetCategoryStatVO;
import com.zhangchi.gameasset.vo.BurndownPointVO;
import com.zhangchi.gameasset.vo.ReportMemberVO;
import com.zhangchi.gameasset.vo.StatCountVO;
import com.zhangchi.gameasset.vo.TrendPointVO;
import com.zhangchi.gameasset.vo.WorkloadItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 统计与报表的聚合查询（模块4）。
 *
 * <p>全部是手写 SQL，理由与其他模块一致：这些都是<b>跨表聚合</b>，
 * 条件构造器表达不了 {@code LEFT JOIN} + 子查询 + 按状态分组。
 * 同时它们每一条都带 {@code project_id}——包括那些「按 asset_id / task_id
 * 关联过去」的语句，也必须 JOIN 回带 {@code project_id} 的主表来限定作用域，
 * 而不是先查出数据再在 Java 里比项目。</p>
 *
 * <p>{@code MapperSqlContractTest} 会逐条核查本接口里每条 SQL 都含
 * {@code project_id}，并且专门盯住燃尽图那条 SQL 的「未来任务」修正。</p>
 *
 * @author 张忠驰
 */
@Mapper
public interface StatMapper {

    // ==================== 燃尽图（文档 5.6） ====================

    /**
     * 按日回放「截至当日仍未完成的故事点合计」——燃尽图的实际线。
     *
     * <h3>这条 SQL 修掉了文档 5.6 原始版本的一个真实缺陷</h3>
     * <p>文档原文只排除了「已 DONE 的任务」，没有排除
     * <b>在该日期之后才创建的任务</b>。后果是：一个 10 月才建的任务，
     * 在 9 月 1 日的点位上就已经被算成「剩余故事点」，
     * 历史曲线上凭空多出一段本不存在的故事点，整条实际线被抬高。
     * 修正见下面 {@code t.create_time < DATE_ADD(d.stat_date, INTERVAL 1 DAY)} 这一句。</p>
     *
     * <h3>为什么用 {@code < DATE_ADD(d.stat_date, INTERVAL 1 DAY)} 而不是 {@code <= d.stat_date}</h3>
     * <p>{@code create_time} 是 {@code DATETIME}，{@code stat_date} 是 {@code DATE}。
     * 直接写 {@code create_time <= d.stat_date}，MySQL 会把 {@code 2026-09-01 10:00:00}
     * 与 {@code 2026-09-01 00:00:00} 相比，判定为「在这天之后创建」——
     * 于是当天创建的任务在当天的点位上就消失了。加一天再比才等价于
     * 「创建日期 ≤ stat_date」。{@code task_status_log} 那条同理。</p>
     *
     * <h3>回放口径</h3>
     * <p>判定「某个任务在某个日期点位上已完成」的依据是
     * <b>该日期（含）之前存在一条 {@code to_status='DONE'} 的日志</b>，
     * 与任务<b>现在</b>是什么状态无关。所以「先完成、之后又被拖回 TODO」
     * 的任务，其历史点位不会因为这次回退而改变——这正是「用日志回放
     * 而不是看当前状态」的全部意义。</p>
     *
     * <p>日期轴来自 {@code calendar_dates} 维表并 LEFT JOIN 任务：
     * 没有任务进出的日子也必须在曲线上有点，否则折线会在静默的日子里断开。</p>
     *
     * @param projectId 项目ID，越权防护的第二道条件
     * @param start     起始日期（含）
     * @param end       结束日期（含）
     */
    @Select("SELECT d.stat_date AS `date`, COALESCE(SUM(t.story_point), 0) AS points "
            + "FROM calendar_dates d "
            + "LEFT JOIN task t ON t.project_id = #{projectId} "
            + "  AND t.deleted = 0 "
            // 修正点：未来才创建的任务不算进这一天的剩余故事点
            + "  AND t.create_time < DATE_ADD(d.stat_date, INTERVAL 1 DAY) "
            + "  AND NOT EXISTS (SELECT 1 FROM task_status_log l "
            + "                  WHERE l.task_id = t.id AND l.to_status = 'DONE' "
            + "                    AND l.create_time < DATE_ADD(d.stat_date, INTERVAL 1 DAY)) "
            + "WHERE d.stat_date BETWEEN #{start} AND #{end} "
            + "GROUP BY d.stat_date ORDER BY d.stat_date")
    List<BurndownPointVO> selectDailyRemainingPoints(@Param("projectId") Long projectId,
                                                     @Param("start") LocalDate start,
                                                     @Param("end") LocalDate end);

    /**
     * 项目总故事点（燃尽图的起点）。
     *
     * <p>{@code COALESCE} 不能省：SUM 在没有行时返回 NULL，
     * 而一个刚建立、还没有任务的项目完全可能出现 0 行，
     * 让接口返回 {@code totalPoints: null} 会直接让前端整条理想线的计算变成 NaN。</p>
     */
    @Select("SELECT COALESCE(SUM(story_point), 0) FROM task "
            + "WHERE project_id = #{projectId} AND deleted = 0")
    Integer sumStoryPoint(@Param("projectId") Long projectId);

    // ==================== 资产完成率 / 分类分布 ====================

    /** 资产状态分布（环形图）。{@code key} 由服务层补中文名与配色 */
    @Select("SELECT status AS `key`, COUNT(*) AS count FROM asset "
            + "WHERE project_id = #{projectId} AND deleted = 0 GROUP BY status")
    List<StatCountVO> selectAssetStatusCount(@Param("projectId") Long projectId);

    /**
     * 各分类的资产数量与完成情况（柱状图）。
     *
     * <p>用 {@code LEFT JOIN}：刚建好、还没有资产的分类也要出现在柱状图上
     * （高度 0）。若用 INNER JOIN，新建分类后会「看不到这个分类」，
     * 用户会以为分类没建成功。</p>
     *
     * <p>{@code GROUP BY} 里带上了 {@code c.sort_order}：
     * MySQL 8 默认开启 {@code ONLY_FULL_GROUP_BY}，
     * 排序用的列不出现在 GROUP BY 或聚合函数里会被直接拒绝。</p>
     */
    @Select("SELECT c.id AS categoryId, c.name AS categoryName, c.code AS categoryCode, "
            + "       c.parent_id AS parentId, c.color AS color, "
            + "       COUNT(a.id) AS total, "
            + "       COALESCE(SUM(CASE WHEN a.status = 'APPROVED' THEN 1 ELSE 0 END), 0) AS approved, "
            + "       COALESCE(SUM(CASE WHEN a.status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending, "
            + "       COALESCE(SUM(CASE WHEN a.status = 'IN_PROGRESS' THEN 1 ELSE 0 END), 0) AS inProgress "
            + "FROM asset_category c "
            + "LEFT JOIN asset a ON a.category_id = c.id "
            + "  AND a.project_id = #{projectId} AND a.deleted = 0 "
            + "WHERE c.project_id = #{projectId} "
            + "GROUP BY c.id, c.name, c.code, c.parent_id, c.color, c.sort_order "
            + "ORDER BY c.sort_order ASC, c.id ASC")
    List<AssetCategoryStatVO> selectCategoryStat(@Param("projectId") Long projectId);

    // ==================== 成员工作量（文档 5.7） ====================

    /**
     * 成员工作量明细。
     *
     * <p>从 {@code project_member} 出发而不是从 {@code task} 出发：
     * 一个这周什么都没做的成员，也必须出现在表里（全 0）。
     * 从任务表出发的话，没任务的人根本不会出现在结果中，
     * 「谁闲着」这个报表最该回答的问题反而答不出来。</p>
     *
     * <p>只算 {@code ACTIVE} 成员：被邀请但没加入的人不可能有产出，
     * 把他列进去会把人均任务量摊薄，掩盖真正的不均衡。</p>
     */
    @Select("SELECT u.id AS userId, u.nickname AS nickname, pm.role AS role, "
            + "       COUNT(DISTINCT t.id) AS taskTotal, "
            + "       COALESCE(SUM(CASE WHEN t.column_status = 'DONE' THEN 1 ELSE 0 END), 0) AS taskDone, "
            + "       COALESCE(SUM(t.estimate_hours), 0) AS estimateHours, "
            + "       COALESCE(SUM(t.actual_hours), 0) AS actualHours, "
            + "       COALESCE(SUM(t.story_point), 0) AS points, "
            + "       (SELECT COUNT(*) FROM asset_version av "
            + "         WHERE av.uploader_id = u.id AND av.project_id = #{projectId}) AS versionUploaded, "
            + "       (SELECT COUNT(*) FROM asset a "
            + "         WHERE a.owner_id = u.id AND a.project_id = #{projectId} "
            + "           AND a.status = 'APPROVED' AND a.deleted = 0) AS assetApproved "
            + "FROM project_member pm "
            + "JOIN sys_user u ON u.id = pm.user_id AND u.deleted = 0 "
            + "LEFT JOIN task t ON t.assignee_id = u.id "
            + "  AND t.project_id = #{projectId} AND t.deleted = 0 "
            + "WHERE pm.project_id = #{projectId} AND pm.status = 'ACTIVE' "
            + "GROUP BY u.id, u.nickname, pm.role "
            + "ORDER BY points DESC, u.id ASC")
    List<WorkloadItemVO> selectWorkload(@Param("projectId") Long projectId);

    // ==================== 任务趋势 ====================

    /** 每日新增任务数（只返回有数据的日子，空日由服务层补 0） */
    @Select("SELECT DATE(create_time) AS `date`, COUNT(*) AS created, 0 AS completed FROM task "
            + "WHERE project_id = #{projectId} AND deleted = 0 "
            + "  AND create_time >= #{start} AND create_time < DATE_ADD(#{end}, INTERVAL 1 DAY) "
            + "GROUP BY DATE(create_time) ORDER BY `date`")
    List<TrendPointVO> selectDailyCreated(@Param("projectId") Long projectId,
                                          @Param("start") LocalDate start,
                                          @Param("end") LocalDate end);

    /**
     * 每日完成任务数。
     *
     * <p>取 {@code task_status_log} 里 {@code to_status='DONE'} 的日志按天计数，
     * 而不是取 {@code task.finished_at}：{@code finished_at} 会被拖出 DONE 时清空，
     * 用它统计会让「上周确实完成过 3 个」这段历史随一次拖拽消失。</p>
     */
    @Select("SELECT DATE(l.create_time) AS `date`, 0 AS created, COUNT(DISTINCT l.task_id) AS completed "
            + "FROM task_status_log l "
            + "JOIN task t ON t.id = l.task_id AND t.project_id = #{projectId} AND t.deleted = 0 "
            + "WHERE l.project_id = #{projectId} AND l.to_status = 'DONE' "
            + "  AND l.create_time >= #{start} AND l.create_time < DATE_ADD(#{end}, INTERVAL 1 DAY) "
            + "GROUP BY DATE(l.create_time) ORDER BY `date`")
    List<TrendPointVO> selectDailyCompleted(@Param("projectId") Long projectId,
                                            @Param("start") LocalDate start,
                                            @Param("end") LocalDate end);

    // ==================== 周报 / 月报（文档 5.10） ====================

    /** 周期内新建任务数 */
    @Select("SELECT COUNT(*) FROM task WHERE project_id = #{projectId} AND deleted = 0 "
            + "  AND create_time >= #{start} AND create_time < DATE_ADD(#{end}, INTERVAL 1 DAY)")
    Integer countTaskCreatedBetween(@Param("projectId") Long projectId,
                                    @Param("start") LocalDate start,
                                    @Param("end") LocalDate end);

    /**
     * 周期内完成的任务数（按日志去重）。
     *
     * <p>口径与趋势图的「每日完成」完全一致——同一件事在两个页面必须是同一个数。
     * 依据是「周期内发生过一次 →已完成」，因此<b>后来被拖回 TODO 的任务仍计入</b>：
     * 它在那周确实被完成过，周报记的是当时发生的事。</p>
     */
    @Select("SELECT COUNT(DISTINCT l.task_id) FROM task_status_log l "
            + "JOIN task t ON t.id = l.task_id AND t.project_id = #{projectId} AND t.deleted = 0 "
            + "WHERE l.project_id = #{projectId} AND l.to_status = 'DONE' "
            + "  AND l.create_time >= #{start} AND l.create_time < DATE_ADD(#{end}, INTERVAL 1 DAY)")
    Integer countTaskCompletedBetween(@Param("projectId") Long projectId,
                                      @Param("start") LocalDate start,
                                      @Param("end") LocalDate end);

    /** 周期内新建资产数 */
    @Select("SELECT COUNT(*) FROM asset WHERE project_id = #{projectId} AND deleted = 0 "
            + "  AND create_time >= #{start} AND create_time < DATE_ADD(#{end}, INTERVAL 1 DAY)")
    Integer countAssetCreatedBetween(@Param("projectId") Long projectId,
                                     @Param("start") LocalDate start,
                                     @Param("end") LocalDate end);

    /**
     * 周期内通过审核的资产数。
     *
     * <p>{@code asset_status_log} 没有 {@code project_id} 列（照文档 DDL），
     * 所以必须 JOIN 回 {@code asset} 来限定项目——这也是本接口里
     * 「SQL 必须带 project_id」那条契约的体现：作用域由数据库保证，
     * 不靠调用方记得先查一次归属。</p>
     */
    @Select("SELECT COUNT(DISTINCT l.asset_id) FROM asset_status_log l "
            + "JOIN asset a ON a.id = l.asset_id AND a.project_id = #{projectId} AND a.deleted = 0 "
            + "WHERE l.to_status = 'APPROVED' "
            + "  AND l.create_time >= #{start} AND l.create_time < DATE_ADD(#{end}, INTERVAL 1 DAY)")
    Integer countAssetApprovedBetween(@Param("projectId") Long projectId,
                                      @Param("start") LocalDate start,
                                      @Param("end") LocalDate end);

    /** 某状态的资产存量（周报的「待审核积压」用它取） */
    @Select("SELECT COUNT(*) FROM asset WHERE project_id = #{projectId} AND deleted = 0 "
            + "  AND status = #{status}")
    Integer countAssetByStatus(@Param("projectId") Long projectId,
                               @Param("status") String status);

    /**
     * 周期内的成员产出。
     *
     * <p>每个成员一行，即使本周期什么都没做（全 0）——周报的价值有一半在于
     * 「看出谁这周没动静」，过滤掉零行就没有这个信息了。</p>
     *
     * <p>工时与故事点按 {@code task.finished_at} 归集，而「完成任务数」按日志归集。
     * 两者口径不同是刻意的：完成数是「发生过的事」，而工时/故事点是「留在
     * 已完成里的产出」——一个被拖回 TODO 的任务，那周完成过（计入完成数），
     * 但它当前并不在已完成列里，把它的工时算作本周产出会与看板对不上。</p>
     */
    @Select("SELECT u.id AS userId, u.nickname AS nickname, pm.role AS role, "
            + "  (SELECT COUNT(*) FROM task t1 WHERE t1.project_id = #{projectId} AND t1.deleted = 0 "
            + "     AND t1.assignee_id = u.id "
            + "     AND t1.create_time >= #{start} AND t1.create_time < DATE_ADD(#{end}, INTERVAL 1 DAY)) AS taskCreated, "
            + "  (SELECT COUNT(DISTINCT l1.task_id) FROM task_status_log l1 "
            + "     JOIN task t2 ON t2.id = l1.task_id AND t2.project_id = #{projectId} AND t2.deleted = 0 "
            + "     WHERE l1.project_id = #{projectId} AND l1.to_status = 'DONE' AND t2.assignee_id = u.id "
            + "       AND l1.create_time >= #{start} AND l1.create_time < DATE_ADD(#{end}, INTERVAL 1 DAY)) AS taskCompleted, "
            + "  (SELECT COUNT(*) FROM task t3 WHERE t3.project_id = #{projectId} AND t3.deleted = 0 "
            + "     AND t3.assignee_id = u.id AND t3.column_status <> 'DONE') AS taskOpen, "
            + "  (SELECT COUNT(*) FROM asset_version av WHERE av.project_id = #{projectId} "
            + "     AND av.uploader_id = u.id "
            + "     AND av.create_time >= #{start} AND av.create_time < DATE_ADD(#{end}, INTERVAL 1 DAY)) AS versionUploaded, "
            + "  (SELECT COUNT(DISTINCT l2.asset_id) FROM asset_status_log l2 "
            + "     JOIN asset a ON a.id = l2.asset_id AND a.project_id = #{projectId} AND a.deleted = 0 "
            + "     WHERE l2.to_status = 'APPROVED' AND a.owner_id = u.id "
            + "       AND l2.create_time >= #{start} AND l2.create_time < DATE_ADD(#{end}, INTERVAL 1 DAY)) AS assetApproved, "
            + "  (SELECT COALESCE(SUM(t4.story_point), 0) FROM task t4 "
            + "     WHERE t4.project_id = #{projectId} AND t4.deleted = 0 AND t4.assignee_id = u.id "
            + "       AND t4.finished_at >= #{start} AND t4.finished_at < DATE_ADD(#{end}, INTERVAL 1 DAY)) AS points, "
            + "  (SELECT COALESCE(SUM(t5.actual_hours), 0) FROM task t5 "
            + "     WHERE t5.project_id = #{projectId} AND t5.deleted = 0 AND t5.assignee_id = u.id "
            + "       AND t5.finished_at >= #{start} AND t5.finished_at < DATE_ADD(#{end}, INTERVAL 1 DAY)) AS actualHours "
            + "FROM project_member pm "
            + "JOIN sys_user u ON u.id = pm.user_id AND u.deleted = 0 "
            + "WHERE pm.project_id = #{projectId} AND pm.status = 'ACTIVE' "
            + "ORDER BY taskCompleted DESC, taskCreated DESC, u.id ASC")
    List<ReportMemberVO> selectPeriodWorkload(@Param("projectId") Long projectId,
                                             @Param("start") LocalDate start,
                                             @Param("end") LocalDate end);
}
