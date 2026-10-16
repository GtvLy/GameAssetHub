package com.zhangchi.gameasset.service.support;

import com.zhangchi.gameasset.vo.BurndownPointVO;
import com.zhangchi.gameasset.vo.BurndownVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 燃尽图计算测试（文档 5.6）。
 *
 * <p>这里盯住的是三件「不测就一定错」的事：</p>
 * <ol>
 *   <li><b>理想线的首尾值</b>：起点必须等于总故事点、终点必须归零，
 *       中间线性。首值算错（比如少算一天）整条参考线会整体偏移，
 *       而图还是画得出来的，肉眼很难发现。</li>
 *   <li><b>今天落在计划区间前 / 中 / 后三种情形</b>：项目表里的
 *       {@code start_date} / {@code plan_end_date} 是固定值，而「今天」
 *       每天在走，三种情形迟早都会遇到。区间外的两种最容易崩——
 *       除法出现负数、实际线画出未来的点、进度判定越拖越宽松。</li>
 *   <li><b>实际线只画到今天</b>：未来的日期不该有「实际」数据。</li>
 * </ol>
 *
 * <p>纯逻辑测试：不加载 Spring、不连数据库。</p>
 *
 * @author 张忠驰
 */
@DisplayName("BurndownCalculator 燃尽图")
class BurndownCalculatorTest {

    /** 与演示数据一致的计划区间：2026-09-01 ~ 2026-12-31 */
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 12, 31);

    private final BurndownCalculator calculator = new BurndownCalculator();

    // ==================== 理想线 ====================

    @Test
    @DisplayName("理想线首值 = 总故事点、尾值 = 0、点数 = 总天数 + 1，且逐日递减")
    void idealLineStartsAtTotalAndEndsAtZero() {
        List<BurndownPointVO> line = calculator.buildIdealLine(START, END, 260);

        assertThat(line).hasSize((int) (END.toEpochDay() - START.toEpochDay()) + 1);
        assertThat(line.get(0).getDate()).isEqualTo(START);
        assertThat(line.get(0).getPoints()).isEqualTo(260);
        assertThat(line.get(line.size() - 1).getDate()).isEqualTo(END);
        assertThat(line.get(line.size() - 1).getPoints()).isZero();

        for (int i = 1; i < line.size(); i++) {
            assertThat(line.get(i).getPoints())
                    .as("第 %d 个点应不高于前一个点", i)
                    .isLessThanOrEqualTo(line.get(i - 1).getPoints());
            assertThat(line.get(i).getDate()).isEqualTo(line.get(i - 1).getDate().plusDays(1));
        }
    }

    @Test
    @DisplayName("总故事点为 0 时理想线仍然铺满整段区间（曲线是平的，不是空数组）")
    void idealLineWithZeroPointsStillSpansTheWindow() {
        List<BurndownPointVO> line = calculator.buildIdealLine(START, END, 0);

        assertThat(line).isNotEmpty();
        assertThat(line).allSatisfy(point -> assertThat(point.getPoints()).isZero());
        assertThat(line.get(0).getDate()).isEqualTo(START);
        assertThat(line.get(line.size() - 1).getDate()).isEqualTo(END);
    }

    @Test
    @DisplayName("开始日期与结束日期同一天时也给两个点，前端才画得出线段")
    void idealLineForSingleDayProject() {
        List<BurndownPointVO> line = calculator.buildIdealLine(START, START, 80);

        assertThat(line).hasSize(2);
        assertThat(line.get(0).getPoints()).isZero();
        assertThat(line.get(1).getPoints()).isZero();
    }

    // ==================== 三种「今天」 ====================

    @Test
    @DisplayName("今天在区间【前】：实际线为空、剩余 = 总故事点、判定为符合计划（不报错、不倒退）")
    void todayBeforeWindow() {
        LocalDate today = START.minusDays(10);
        BurndownVO vo = calculator.assemble(1L, START, END, today, 100, new ArrayList<>());

        assertThat(vo.getActualLine()).isEmpty();
        assertThat(vo.getActualEndDate()).isNull();
        assertThat(vo.getCurrentPoints()).isEqualTo(100);
        assertThat(vo.getElapsedDays()).isZero();
        assertThat(vo.getScheduleStatus()).isEqualTo(BurndownCalculator.ON_TRACK);
        assertThat(vo.getIdealPointsToday()).isEqualTo(100);
        assertThat(vo.getIdealLine()).isNotEmpty();
        assertThat(vo.getOverduePlan()).isFalse();
    }

    @Test
    @DisplayName("今天在区间【中】：实际线只到今天、理想线仍覆盖到计划结束")
    void todayInsideWindow() {
        LocalDate today = LocalDate.of(2026, 9, 30);   // 第 29 天
        List<BurndownPointVO> actual = List.of(
                new BurndownPointVO(START, 100),
                new BurndownPointVO(today, 60));
        BurndownVO vo = calculator.assemble(1L, START, END, today, 100, actual);

        assertThat(vo.getActualEndDate()).isEqualTo(today);
        assertThat(vo.getActualLine()).hasSize(2);
        assertThat(vo.getCurrentPoints()).isEqualTo(60);
        // 理想线仍然画到计划结束日
        List<BurndownPointVO> ideal = vo.getIdealLine();
        assertThat(ideal.get(ideal.size() - 1).getDate()).isEqualTo(END);
        assertThat(vo.getElapsedDays()).isEqualTo(29L);
        assertThat(vo.getTotalDays()).isEqualTo(121L);
        assertThat(vo.getOverduePlan()).isFalse();
        assertThat(vo.getScheduleStatus()).isIn(
                BurndownCalculator.AHEAD, BurndownCalculator.ON_TRACK, BurndownCalculator.BEHIND);
    }

    @Test
    @DisplayName("今天在区间【后】：实际线画到计划结束日为止，`已消耗天数` 夹紧为总天数而不是继续增长")
    void todayAfterWindow() {
        LocalDate today = LocalDate.of(2027, 3, 1);
        List<BurndownPointVO> actual = List.of(
                new BurndownPointVO(START, 100),
                new BurndownPointVO(END, 40));
        BurndownVO vo = calculator.assemble(1L, START, END, today, 100, actual);

        assertThat(vo.getActualEndDate()).isEqualTo(END);
        assertThat(vo.getCurrentPoints()).isEqualTo(40);
        assertThat(vo.getElapsedDays()).isEqualTo(vo.getTotalDays());
        assertThat(vo.getScheduleStatus()).isEqualTo(BurndownCalculator.BEHIND);
        assertThat(vo.getScheduleStatusLabel()).isEqualTo("进度落后");
        assertThat(vo.getOverduePlan()).isTrue();
    }

    @Test
    @DisplayName("过了计划结束日但故事点已烧完 → AHEAD，不是 BEHIND")
    void finishedAfterPlanEndIsAhead() {
        LocalDate today = LocalDate.of(2027, 3, 1);
        List<BurndownPointVO> actual = List.of(new BurndownPointVO(END, 0));

        BurndownVO vo = calculator.assemble(1L, START, END, today, 100, actual);

        assertThat(vo.getCurrentPoints()).isZero();
        assertThat(vo.getScheduleStatus()).isEqualTo(BurndownCalculator.AHEAD);
    }

    // ==================== 判定三档 ====================

    @Test
    @DisplayName("判定三档：剩余显著低于理想 → AHEAD；相当 → ON_TRACK；显著高于 → BEHIND")
    void scheduleStatusThreeLevels() {
        // 总 100 点、121 天；第 60 天时理想剩余 ≈ 100 × (1 − 60/121) ≈ 50.4
        String ahead = calculator.judge(100, 121L, 60L, 40);
        String onTrack = calculator.judge(100, 121L, 60L, 50);
        String behind = calculator.judge(100, 121L, 60L, 70);

        assertThat(ahead).isEqualTo(BurndownCalculator.AHEAD);
        assertThat(onTrack).isEqualTo(BurndownCalculator.ON_TRACK);
        assertThat(behind).isEqualTo(BurndownCalculator.BEHIND);
        assertThat(calculator.statusLabel(ahead)).isEqualTo("进度超前");
        assertThat(calculator.statusLabel(onTrack)).isEqualTo("符合计划");
        assertThat(calculator.statusLabel(behind)).isEqualTo("进度落后");
    }

    @Test
    @DisplayName("总故事点或总天数为 0 时不判落后（没有可比的东西，误报比不报更糟）")
    void noJudgementWithoutComparableData() {
        assertThat(calculator.judge(0, 121L, 60L, 0)).isEqualTo(BurndownCalculator.ON_TRACK);
        assertThat(calculator.judge(100, 0L, 0L, 100)).isEqualTo(BurndownCalculator.ON_TRACK);
    }

    // ==================== 脏数据容错 ====================

    @Test
    @DisplayName("未设置开始日期 → 不画线但接口不报错，剩余按总故事点返回")
    void missingStartDateDegradesGracefully() {
        BurndownVO vo = calculator.assemble(1L, null, END, LocalDate.of(2026, 9, 30), 50, null);

        assertThat(vo.getIdealLine()).isEmpty();
        assertThat(vo.getActualLine()).isEmpty();
        assertThat(vo.getCurrentPoints()).isEqualTo(50);
        assertThat(vo.getScheduleStatus()).isEqualTo(BurndownCalculator.ON_TRACK);
    }

    @Test
    @DisplayName("结束日期早于开始日期（脏数据）→ 收敛为单日项目，不产生负天数")
    void endBeforeStartIsCollapsed() {
        BurndownCalculator.Window window = calculator.resolveWindow(
                LocalDate.of(2026, 12, 31), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));

        assertThat(window.plannable()).isTrue();
        assertThat(window.getTotalDays()).isZero();
        assertThat(window.getEnd()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(window.getElapsedDays()).isZero();
    }

    @Test
    @DisplayName("实际线为空时 currentPoints 退化为总故事点（文档 5.6 的取法）")
    void currentPointsFallsBackToTotal() {
        BurndownVO vo = calculator.assemble(1L, START, END, LocalDate.of(2026, 9, 5), 77, null);

        assertThat(vo.getCurrentPoints()).isEqualTo(77);
    }
}
