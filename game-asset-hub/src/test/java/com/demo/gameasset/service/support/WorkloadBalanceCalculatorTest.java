package com.demo.gameasset.service.support;

import com.demo.gameasset.vo.WorkloadItemVO;
import com.demo.gameasset.vo.WorkloadVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 负载均衡度测试（文档 5.7：{@code 均衡度 = 1 − 标准差 / 平均值}）。
 *
 * <p>这三个用例是「不测就一定错」的典型：</p>
 * <ol>
 *   <li><b>全部相等</b> → 标准差 0 → 均衡度 1.0（主路径）；</li>
 *   <li><b>全部为 0</b> → 平均值 0，直接做除法会得到 {@code NaN} 或 {@code Infinity}，
 *       而 {@code NaN < 0.5} 在 Java 里是 <b>false</b>、{@code Infinity < 0.5} 也是 false，
 *       所以这个 bug 不会让提示多出来，而是让均衡度显示成 {@code NaN}——
 *       一个刚建好、还没排任务的项目打开统计页看到「负载均衡度 NaN%」，
 *       没人会认为那是正常的；</li>
 *   <li><b>分配不均</b> → 触发文档要求的「工作量分配不均，建议调整」提示。</li>
 * </ol>
 *
 * <p>纯逻辑测试：不加载 Spring、不连数据库。</p>
 *
 * @author 管理员
 */
@DisplayName("WorkloadBalanceCalculator 负载均衡度")
class WorkloadBalanceCalculatorTest {

    private final WorkloadBalanceCalculator calculator = new WorkloadBalanceCalculator();

    private static WorkloadItemVO member(long id, String nickname, String role, int taskTotal, int taskDone) {
        WorkloadItemVO vo = new WorkloadItemVO();
        vo.setUserId(id);
        vo.setNickname(nickname);
        vo.setRole(role);
        vo.setTaskTotal(taskTotal);
        vo.setTaskDone(taskDone);
        vo.setActualHours(BigDecimal.valueOf(taskDone * 2L));
        vo.setEstimateHours(BigDecimal.valueOf(taskDone * 3L));
        vo.setPoints(taskTotal);
        vo.setVersionUploaded(taskDone);
        vo.setAssetApproved(0);
        return vo;
    }

    @Test
    @DisplayName("全部相等：标准差 0、均衡度 1.0、不触发提示、没人被标为低于平均")
    void perfectlyBalanced() {
        List<WorkloadItemVO> rows = new ArrayList<>(List.of(
                member(1, "管理员", "OWNER", 4, 2),
                member(2, "李阳", "ARTIST", 4, 1),
                member(4, "陈静", "VIEWER", 4, 0)));

        WorkloadVO vo = calculator.summarize(1L, rows);

        assertThat(vo.getMemberCount()).isEqualTo(3);
        assertThat(vo.getTaskTotalSum()).isEqualTo(12);
        assertThat(vo.getAverageTask()).isEqualTo(4.0);
        assertThat(vo.getStdDevTask()).isZero();
        assertThat(vo.getBalanceIndex()).isEqualTo(1.0);
        assertThat(vo.getBalanceWarning()).isFalse();
        assertThat(vo.getHint()).isEmpty();
        assertThat(rows).allSatisfy(row -> assertThat(row.getBelowAverage()).isFalse());
        assertThat(vo.getBalanceDefinition()).contains("1 − 标准差 / 平均值");
    }

    @Test
    @DisplayName("全部为 0（还没排任务）：不除零，均衡度取 1.0，绝不出 NaN / Infinity")
    void allZeroDoesNotDivideByZero() {
        List<WorkloadItemVO> rows = new ArrayList<>(List.of(
                member(1, "管理员", "OWNER", 0, 0),
                member(2, "李阳", "ARTIST", 0, 0)));

        WorkloadVO vo = calculator.summarize(1L, rows);

        assertThat(vo.getAverageTask()).isZero();
        assertThat(vo.getBalanceIndex()).isEqualTo(1.0);
        assertThat(vo.getBalanceIndex()).isFinite();
        assertThat(vo.getBalanceWarning()).isFalse();
        assertThat(vo.getHint()).isEmpty();
        assertThat(rows).allSatisfy(row -> assertThat(row.getTaskCompletionRate()).isZero());
    }

    @Test
    @DisplayName("没有成员（空项目）：同样不除零，均衡度 1.0")
    void noMembers() {
        WorkloadVO vo = calculator.summarize(1L, new ArrayList<>());

        assertThat(vo.getMemberCount()).isZero();
        assertThat(vo.getAverageTask()).isZero();
        assertThat(vo.getBalanceIndex()).isEqualTo(1.0);
        assertThat(vo.getBalanceWarning()).isFalse();
        assertThat(vo.getMembers()).isEmpty();
    }

    @Test
    @DisplayName("分配不均：均衡度 < 0.5，给出「工作量分配不均，建议调整」提示并标出低于平均的人")
    void unbalancedTriggersHint() {
        // 10 / 0：μ = 5，σ = 5，均衡度 = 0 < 0.5
        List<WorkloadItemVO> rows = new ArrayList<>(List.of(
                member(1, "管理员", "OWNER", 10, 5),
                member(2, "李阳", "ARTIST", 0, 0)));

        WorkloadVO vo = calculator.summarize(1L, rows);

        assertThat(vo.getAverageTask()).isEqualTo(5.0);
        assertThat(vo.getStdDevTask()).isEqualTo(5.0);
        assertThat(vo.getBalanceIndex()).isZero();
        assertThat(vo.getBalanceWarning()).isTrue();
        assertThat(vo.getHint()).contains("工作量分配不均，建议调整");

        WorkloadItemVO idle = rows.stream().filter(r -> r.getUserId() == 2L).findFirst().orElseThrow();
        WorkloadItemVO busy = rows.stream().filter(r -> r.getUserId() == 1L).findFirst().orElseThrow();
        assertThat(idle.getBelowAverage()).isTrue();
        assertThat(busy.getBelowAverage()).isFalse();
    }

    @Test
    @DisplayName("极度不均时均衡度可以为负（不截断到 0，保住区分度）")
    void extremeImbalanceGoesNegative() {
        // 10 / 0 / 0：μ = 10/3 ≈ 3.33，σ ≈ 4.71，1 − σ/μ ≈ −0.414
        List<WorkloadItemVO> rows = new ArrayList<>(List.of(
                member(1, "管理员", "OWNER", 10, 0),
                member(2, "李阳", "ARTIST", 0, 0),
                member(4, "陈静", "VIEWER", 0, 0)));

        WorkloadVO vo = calculator.summarize(1L, rows);

        assertThat(vo.getBalanceIndex()).isLessThan(0.0);
        assertThat(vo.getBalanceWarning()).isTrue();
    }

    @Test
    @DisplayName("阈值边界：均衡度恰好 0.5 不算不均（文档写的是「< 0.5」）")
    void thresholdIsStrictlyLessThan() {
        // 2 / 1 / 1：μ = 4/3，σ = √(2/9) ≈ 0.4714，均衡度 ≈ 0.6464 —— 用能算准的一组
        // 2 / 0：μ = 1，σ = 1，均衡度 = 0 → 触发；3 / 1：μ = 2，σ = 1，均衡度 = 0.5 → 不触发
        List<WorkloadItemVO> exactly = new ArrayList<>(List.of(
                member(1, "管理员", "OWNER", 3, 0),
                member(2, "李阳", "ARTIST", 1, 0)));
        WorkloadVO half = calculator.summarize(1L, exactly);

        assertThat(half.getBalanceIndex()).isEqualTo(0.5);
        assertThat(half.getBalanceWarning()).isFalse();
    }

    @Test
    @DisplayName("角色中文名、完成率与排序由服务端算好（前端不再自己推）")
    void derivesLabelsRatesAndOrder() {
        List<WorkloadItemVO> rows = new ArrayList<>(List.of(
                member(2, "李阳", "ARTIST", 4, 3),
                member(1, "管理员", "OWNER", 2, 0)));

        WorkloadVO vo = calculator.summarize(1L, rows);

        assertThat(vo.getMembers()).extracting(WorkloadItemVO::getNickname)
                .containsExactly("李阳", "管理员");
        WorkloadItemVO first = vo.getMembers().get(0);
        assertThat(first.getRoleLabel()).isEqualTo("美术");
        assertThat(first.getTaskCompletionRate()).isEqualTo(75.0);
        assertThat(vo.getMembers().get(1).getTaskCompletionRate()).isZero();
    }
}
