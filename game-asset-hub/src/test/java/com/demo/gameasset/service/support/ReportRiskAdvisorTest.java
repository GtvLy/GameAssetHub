package com.demo.gameasset.service.support;

import com.demo.gameasset.vo.ReportVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 周报风险提示与结论文本测试（文档 5.10 第 4 段）。
 *
 * <p>每条触发条件都<b>钉住阈值的两侧</b>：只测「超过阈值会提示」是不够的，
 * 一个「永远为真」的条件同样能过那种测试，而它的后果是周报上永远挂着
 * 一堆没人当真的风险提示——等于没有提示。所以每条规则都补一个
 * 「刚好没到阈值时不提示」的反例。</p>
 *
 * <p>纯逻辑测试：不加载 Spring、不连数据库。</p>
 *
 * @author 管理员
 */
@DisplayName("ReportRiskAdvisor 风险提示与结论")
class ReportRiskAdvisorTest {

    private final ReportRiskAdvisor advisor = new ReportRiskAdvisor();

    /** 一份「一切正常」的报表骨架，各用例只改自己关心的字段 */
    private static ReportVO healthyReport() {
        ReportVO report = new ReportVO();
        report.setPeriod("2026-09-21 ~ 2026-09-27");
        report.setTaskCreated(3);
        report.setTaskCompleted(3);
        report.setTaskOverdue(0);
        report.setAssetAdded(2);
        report.setAssetApproved(2);
        report.setAssetPendingReview(1);
        report.setBurndownStatus(BurndownCalculator.ON_TRACK);
        report.setBurndownStatusLabel("符合计划");
        return report;
    }

    // ==================== 四条触发条件 ====================

    @Test
    @DisplayName("逾期 > 0 触发；逾期 = 0 不触发")
    void overdueRule() {
        ReportVO none = healthyReport();
        advisor.advise(none);
        assertThat(none.getRisks()).noneMatch(risk -> risk.contains("逾期"));

        ReportVO some = healthyReport();
        some.setTaskOverdue(3);
        advisor.advise(some);
        assertThat(some.getRisks()).anyMatch(risk -> risk.equals("有 3 个任务已逾期，请及时跟进"));
    }

    @Test
    @DisplayName("待审核 > 5 触发（文档写的是严格大于）；恰好 5 不触发")
    void pendingReviewRule() {
        ReportVO five = healthyReport();
        five.setAssetPendingReview(5);
        advisor.advise(five);
        assertThat(five.getRisks()).noneMatch(risk -> risk.contains("待审核资产积压"));

        ReportVO six = healthyReport();
        six.setAssetPendingReview(6);
        advisor.advise(six);
        assertThat(six.getRisks())
                .anyMatch(risk -> risk.equals("待审核资产积压 6 个，建议尽快安排审核"));
    }

    @Test
    @DisplayName("燃尽图判定 BEHIND 触发；ON_TRACK / AHEAD 都不触发")
    void behindRule() {
        ReportVO onTrack = healthyReport();
        advisor.advise(onTrack);
        assertThat(onTrack.getRisks()).noneMatch(risk -> risk.contains("落后"));

        ReportVO ahead = healthyReport();
        ahead.setBurndownStatus(BurndownCalculator.AHEAD);
        ahead.setBurndownStatusLabel("进度超前");
        advisor.advise(ahead);
        assertThat(ahead.getRisks()).noneMatch(risk -> risk.contains("落后"));

        ReportVO behind = healthyReport();
        behind.setBurndownStatus(BurndownCalculator.BEHIND);
        behind.setBurndownStatusLabel("进度落后");
        advisor.advise(behind);
        assertThat(behind.getRisks())
                .anyMatch(risk -> risk.contains("项目进度落后于燃尽图理想线"));
    }

    @Test
    @DisplayName("净增任务 ≥ 5 且确实在净增时才提示「待办在堆积」")
    void backlogGrowthRule() {
        ReportVO balanced = healthyReport();
        balanced.setTaskCreated(5);
        balanced.setTaskCompleted(0);
        advisor.advise(balanced);
        assertThat(balanced.getRisks()).anyMatch(risk -> risk.contains("待办在堆积"));

        // 净增只有 4 → 不到阈值
        ReportVO slight = healthyReport();
        slight.setTaskCreated(4);
        slight.setTaskCompleted(0);
        advisor.advise(slight);
        assertThat(slight.getRisks()).noneMatch(risk -> risk.contains("待办在堆积"));

        // 净增 5，但新增少于完成（本期在还债）→ 也不算堆积
        ReportVO payingDebt = healthyReport();
        payingDebt.setTaskCreated(6);
        payingDebt.setTaskCompleted(20);
        advisor.advise(payingDebt);
        assertThat(payingDebt.getRisks()).noneMatch(risk -> risk.contains("待办在堆积"));
    }

    // ==================== 结论文本 ====================

    @Test
    @DisplayName("无风险时结论说「未发现明显风险」，且带上周期与各项数字")
    void summaryWithoutRisks() {
        ReportVO report = healthyReport();
        advisor.advise(report);

        assertThat(report.getRisks()).isEmpty();
        assertThat(report.getSummary())
                .contains("2026-09-21 ~ 2026-09-27")
                .contains("新增任务 3 个、完成 3 个")
                .contains("资产新增 2 个、通过 2 个")
                .contains("符合计划")
                .contains("未发现明显风险");
    }

    @Test
    @DisplayName("有风险时结论里直接写出「最该先处理的那一条」，并给出风险条数")
    void summaryWithRisks() {
        ReportVO report = healthyReport();
        report.setTaskOverdue(2);
        report.setAssetPendingReview(9);
        advisor.advise(report);

        assertThat(report.getRisks()).hasSize(2);
        assertThat(report.getSummary())
                .contains("共需关注 2 项风险")
                .contains("有 2 个任务已逾期，请及时跟进")
                .contains("其余见风险清单");
    }

    @Test
    @DisplayName("字段为空（脏数据）时不抛异常，结论里用占位符而不是 null")
    void nullFieldsAreTolerated() {
        ReportVO report = new ReportVO();
        advisor.advise(report);

        assertThat(report.getRisks()).isEmpty();
        assertThat(report.getSummary()).doesNotContain("null").contains("符合计划");
    }

    @Test
    @DisplayName("report 为 null 时静默返回（防御性，不抛 NPE）")
    void nullReport() {
        advisor.advise(null);
    }
}
