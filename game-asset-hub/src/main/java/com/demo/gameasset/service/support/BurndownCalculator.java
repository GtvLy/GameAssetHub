package com.demo.gameasset.service.support;

import com.demo.gameasset.vo.BurndownPointVO;
import com.demo.gameasset.vo.BurndownVO;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 燃尽图的纯计算部分（文档 5.6）。
 *
 * <p><b>为什么从服务里抽出来</b>：燃尽图真正难的不是 SQL，而是
 * 「今天落在计划区间的什么位置」这一组边界判断——项目表的
 * {@code start_date} / {@code plan_end_date} 是固定值，而 {@code today}
 * 会一天天往外走，走到区间前、区间内、区间后是三种完全不同的算法路径。
 * 这些判断放在 Service 里就只能连上 Spring 与数据库才测得到；
 * 抽成不依赖任何 Bean 的纯函数后，三种情形可以逐格断言。</p>
 *
 * <p>本类<b>不依赖 Spring 上下文、不碰数据库</b>，只做日期算术与分档。</p>
 *
 * <h3>三条口径</h3>
 * <ol>
 *   <li>理想线覆盖 {@code [start, planEnd]} <b>全区间</b>，逐日线性递减到 0。
 *       计划就是计划，不因「今天还没到」而截断。</li>
 *   <li>实际线只画到 {@code min(planEnd, today)}——未来还没发生的日子
 *       不该有一条「实际」曲线。今天早于开始日期时实际线为空。</li>
 *   <li>{@code elapsedDays} 夹紧到 {@code [0, totalDays]}：今天在区间前按 0 算、
 *       区间后按 totalDays 算。不夹紧的话，过了计划结束日期之后
 *       「理想剩余」会变成负数，判据会随着拖延自动放宽——越拖反而越「正常」，
 *       这是最典型的统计造假。</li>
 * </ol>
 *
 * @author 管理员
 */
@Component
public class BurndownCalculator {

    /** 进度判定：超前 */
    public static final String AHEAD = "AHEAD";

    /** 进度判定：符合计划 */
    public static final String ON_TRACK = "ON_TRACK";

    /** 进度判定：落后 */
    public static final String BEHIND = "BEHIND";

    /**
     * 判定阈值（文档 5.6）：实际剩余低于理想剩余的 90% 算超前，
     * 高于 110% 算落后，中间算符合计划。
     */
    private static final double AHEAD_FACTOR = 0.9;

    private static final double BEHIND_FACTOR = 1.1;

    /**
     * 燃尽图的时间窗口（区间解析结果）。
     *
     * <p>用 record 而不是往 VO 上挂临时字段：这几个值互相之间有约束
     * （{@code actualEnd} 必然落在 {@code [start, end]} 内），
     * 一次算好、一次传递，避免「先算 ideal 用了 start，再算 actual 时 start 被改了」。</p>
     */
    @Getter
    public static final class Window {

        /** 计划开始日期；计划不完整（未设置开始日期）时为 null */
        private final LocalDate start;

        /** 计划结束日期 */
        private final LocalDate end;

        /** 实际线要画的最后一天 = min(end, today)；早于 start 时为 null */
        private final LocalDate actualEnd;

        /** 计划总天数 */
        private final long totalDays;

        /** 已消耗天数，已夹紧到 [0, totalDays] */
        private final long elapsedDays;

        /** 今天是否已经越过计划结束日期 */
        private final boolean planOverdue;

        private Window(LocalDate start, LocalDate end, LocalDate actualEnd,
                       long totalDays, long elapsedDays, boolean planOverdue) {
            this.start = start;
            this.end = end;
            this.actualEnd = actualEnd;
            this.totalDays = totalDays;
            this.elapsedDays = elapsedDays;
            this.planOverdue = planOverdue;
        }

        /** 计划是否可绘制（开始日期已设置） */
        public boolean plannable() {
            return start != null && end != null;
        }
    }

    /**
     * 解析时间窗口。
     *
     * <p>容错规则（都写了单测）：</p>
     * <ul>
     *   <li>{@code start} 为空 → 不可绘制（{@link Window#plannable()} 为 false），
     *       不去猜一个开始日期。猜出来的曲线比没有曲线更容易误导人。</li>
     *   <li>{@code end} 为空或早于 {@code start} → 收敛为「单日项目」（end = start）。
     *       脏数据下 {@code DAYS.between} 会是负数，不收敛的话总天数成了负数，
     *       后面的百分比全部反向。</li>
     *   <li>{@code today} 为空 → 按 {@code start} 处理（等价于「还没开始」）。</li>
     * </ul>
     */
    public Window resolveWindow(LocalDate start, LocalDate end, LocalDate today) {
        LocalDate effectiveToday = today == null ? start : today;
        if (start == null) {
            return new Window(null, end, null, 0L, 0L, false);
        }
        LocalDate effectiveEnd = (end == null || end.isBefore(start)) ? start : end;
        long totalDays = ChronoUnit.DAYS.between(start, effectiveEnd);

        long elapsedRaw = ChronoUnit.DAYS.between(start, effectiveToday);
        long elapsed = Math.max(0L, Math.min(totalDays, elapsedRaw));

        // 实际线：只到 min(计划结束, 今天)；今天还没到开始日期就不画
        LocalDate actualEnd = null;
        if (!effectiveToday.isBefore(start)) {
            actualEnd = effectiveToday.isAfter(effectiveEnd) ? effectiveEnd : effectiveToday;
        }
        boolean planOverdue = effectiveToday.isAfter(effectiveEnd);
        return new Window(start, effectiveEnd, actualEnd, totalDays, elapsed, planOverdue);
    }

    /**
     * 理想线：从 {@code totalPoints} 线性递减到 0，逐日一个点，
     * 包含 {@code start} 与 {@code end} 两端（共 {@code totalDays + 1} 个点）。
     *
     * <p>逐日而不是按周取样：实际线是逐日的，两条线要共用同一个类目轴。
     * 一条按周、一条按日，前端就得自己插值对齐，画出来的交点全是假的。</p>
     *
     * <p>{@code totalDays == 0}（单日项目）时返回「起点 = 终点 = 0」两个点——
     * 不返回一个孤零零的点，否则折线图连线段都画不出来。</p>
     */
    public List<BurndownPointVO> buildIdealLine(LocalDate start, LocalDate end, int totalPoints) {
        List<BurndownPointVO> line = new ArrayList<>();
        if (start == null) {
            return line;
        }
        LocalDate effectiveEnd = (end == null || end.isBefore(start)) ? start : end;
        long totalDays = ChronoUnit.DAYS.between(start, effectiveEnd);

        if (totalDays == 0) {
            line.add(new BurndownPointVO(start, 0));
            line.add(new BurndownPointVO(start.plusDays(1), 0));
            return line;
        }
        for (long i = 0; i <= totalDays; i++) {
            double remain = totalPoints * (1 - (double) i / totalDays);
            line.add(new BurndownPointVO(start.plusDays(i), (int) Math.round(remain)));
        }
        return line;
    }

    /**
     * 进度判定（文档 5.6 的三档 + 边界兜底）。
     *
     * <pre>
     *   理想剩余 = 总故事点 × (1 − 已消耗天数 / 总天数)     已消耗天数已夹紧
     *   实际剩余 &lt; 理想剩余 × 0.9  → AHEAD
     *   实际剩余 &gt; 理想剩余 × 1.1  → BEHIND
     *   其余                        → ON_TRACK
     * </pre>
     *
     * <p>边界处理（每一条都有单测）：</p>
     * <ul>
     *   <li>总故事点为 0（还没排任务）或总天数为 0 → {@code ON_TRACK}。
     *       没有可比的东西时报「落后」是纯粹的误报。</li>
     *   <li>实际剩余为 0 → 直接 {@code AHEAD}。计划还没结束就烧完，
     *       或者早于开始日期却已经没有剩余（数据异常），都不该判成落后。</li>
     * </ul>
     */
    public String judge(int totalPoints, long totalDays, long elapsedDays, int currentPoints) {
        if (totalPoints <= 0 || totalDays <= 0) {
            return ON_TRACK;
        }
        if (currentPoints <= 0) {
            return AHEAD;
        }
        long elapsed = Math.max(0L, Math.min(totalDays, elapsedDays));
        double idealRemain = totalPoints * (1 - (double) elapsed / totalDays);
        if (currentPoints < idealRemain * AHEAD_FACTOR) {
            return AHEAD;
        }
        if (currentPoints > idealRemain * BEHIND_FACTOR) {
            return BEHIND;
        }
        return ON_TRACK;
    }

    /** 判定结果的中文名，前端直接显示，不必再维护一份映射 */
    public String statusLabel(String status) {
        if (AHEAD.equals(status)) {
            return "进度超前";
        }
        if (BEHIND.equals(status)) {
            return "进度落后";
        }
        return "符合计划";
    }

    /**
     * 组装完整的燃尽图 VO。
     *
     * @param projectId    项目ID
     * @param start        计划开始日期（可为 null）
     * @param end          计划结束日期（可为 null）
     * @param today        服务端今天
     * @param totalPoints  总故事点
     * @param actualLine   由 SQL 按日回放出来的实际线（调用方负责只查
     *                     {@code [start, actualEnd]} 这一段；为空表示还没开始）
     */
    public BurndownVO assemble(Long projectId, LocalDate start, LocalDate end, LocalDate today,
                               int totalPoints, List<BurndownPointVO> actualLine) {
        Window window = resolveWindow(start, end, today);
        BurndownVO vo = new BurndownVO();
        vo.setProjectId(projectId);
        vo.setTotalPoints(totalPoints);
        vo.setToday(today);
        vo.setStartDate(window.getStart());
        vo.setEndDate(window.getEnd());
        vo.setActualEndDate(window.getActualEnd());
        vo.setTotalDays(window.getTotalDays());
        vo.setElapsedDays(window.getElapsedDays());
        vo.setOverduePlan(window.isPlanOverdue());

        if (!window.plannable()) {
            // 计划不完整：不画线，但把当前剩余如实报出去，接口不报错
            vo.setIdealLine(new ArrayList<>());
            vo.setActualLine(new ArrayList<>());
            vo.setCurrentPoints(totalPoints);
            vo.setIdealPointsToday(totalPoints);
            vo.setScheduleStatus(ON_TRACK);
            vo.setScheduleStatusLabel(statusLabel(ON_TRACK));
            return vo;
        }

        vo.setIdealLine(buildIdealLine(window.getStart(), window.getEnd(), totalPoints));

        List<BurndownPointVO> actual = actualLine == null ? new ArrayList<>() : actualLine;
        vo.setActualLine(actual);

        // 实际线为空（还没到开始日期，或维表里没有这一段日期）时退化为总故事点，
        // 与文档 5.6 的 currentRemain 取法一致
        int currentPoints = actual.isEmpty()
                ? totalPoints
                : (actual.get(actual.size() - 1).getPoints() == null
                        ? totalPoints : actual.get(actual.size() - 1).getPoints());
        vo.setCurrentPoints(currentPoints);

        double idealToday = totalPoints * (1 - (double) window.getElapsedDays() / window.getTotalDays());
        vo.setIdealPointsToday(scale0(idealToday));

        String status = judge(totalPoints, window.getTotalDays(), window.getElapsedDays(), currentPoints);
        vo.setScheduleStatus(status);
        vo.setScheduleStatusLabel(statusLabel(status));
        return vo;
    }

    private Integer scale0(double value) {
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
