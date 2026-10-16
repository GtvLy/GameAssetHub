package com.zhangchi.gameasset.service.support;

import com.zhangchi.gameasset.vo.ReportVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 周报 / 月报的「自动风险提示 + 结论文本」生成器（文档 5.10 第 4 段）。
 *
 * <p>文档的原话是「把过程数据转成可决策的结论」。只把一堆数字排进表格
 * 不叫结论——看的人还得自己判断「所以这周到底有没有问题」。
 * 本类的职责就是替他把这个判断做掉，并且在<b>触发条件</b>上保持克制：
 * 宁可少报，不可误报。一条动不动就出现的「风险提示」等于没有提示。</p>
 *
 * <h3>触发条件（每条都有单测钉住阈值两侧）</h3>
 * <table border="1">
 *   <tr><th>条件</th><th>提示</th></tr>
 *   <tr><td>{@code 逾期任务数 > 0}</td><td>有 N 个任务已逾期，请及时跟进</td></tr>
 *   <tr><td>{@code 待审核资产 > 5}</td><td>待审核资产积压 N 个，建议尽快安排审核</td></tr>
 *   <tr><td>{@code 燃尽图判定 = BEHIND}</td><td>项目进度落后于燃尽图理想线…</td></tr>
 *   <tr><td>{@code 新增 − 完成 ≥ 5 且 新增 > 完成}</td><td>待办在堆积…</td></tr>
 * </table>
 *
 * <p>阈值取「&gt; 5」而不是「&ge; 5」是照文档 5.10 的原文写的；
 * 「≥ 5」那条也照抄文档给的常数 5。改阈值要同时改这里的单测，
 * 不允许出现「代码改了、测试没改」——那种静默漂移正是风险提示失效的方式。</p>
 *
 * @author 张忠驰
 */
@Component
public class ReportRiskAdvisor {

    /** 待审核资产积压的风险阈值（文档 5.10） */
    public static final int PENDING_REVIEW_THRESHOLD = 5;

    /** 待办堆积的风险阈值：周期内净增任务达到此数才提示 */
    public static final int BACKLOG_GROWTH_THRESHOLD = 5;

    /**
     * 生成风险提示与结论文本，并<b>原地写回</b> {@code report}。
     *
     * @param report 已填好任务 / 资产 / 成员三段数据的报表（读其中的
     *               taskOverdue、assetPendingReview、burndownStatus、
     *               taskCreated、taskCompleted）
     */
    public void advise(ReportVO report) {
        if (report == null) {
            return;
        }
        List<String> risks = new ArrayList<>();

        int overdue = nz(report.getTaskOverdue());
        if (overdue > 0) {
            risks.add("有 " + overdue + " 个任务已逾期，请及时跟进");
        }

        int pending = nz(report.getAssetPendingReview());
        if (pending > PENDING_REVIEW_THRESHOLD) {
            risks.add("待审核资产积压 " + pending + " 个，建议尽快安排审核");
        }

        if (BurndownCalculator.BEHIND.equals(report.getBurndownStatus())) {
            risks.add("项目进度落后于燃尽图理想线，建议评估范围或调整排期");
        }

        int created = nz(report.getTaskCreated());
        int completed = nz(report.getTaskCompleted());
        if (created - completed >= BACKLOG_GROWTH_THRESHOLD && created > completed) {
            risks.add("本周期新增任务 " + created + " 个、仅完成 " + completed
                    + " 个，待办在堆积，建议确认排期是否仍然可行");
        }

        report.setRisks(risks);
        report.setSummary(summarize(report, risks));
    }

    /**
     * 拼结论文本。有风险时把「最该先处理的那一条」直接写进结论里，
     * 而不是只说一句「存在风险，详见下表」——后者等于把判断又推回给读者。
     */
    private String summarize(ReportVO report, List<String> risks) {
        StringBuilder text = new StringBuilder();
        text.append("本周期（").append(nzText(report.getPeriod())).append("）")
                .append("新增任务 ").append(nz(report.getTaskCreated()))
                .append(" 个、完成 ").append(nz(report.getTaskCompleted())).append(" 个；");
        text.append("资产新增 ").append(nz(report.getAssetAdded()))
                .append(" 个、通过 ").append(nz(report.getAssetApproved())).append(" 个；");
        text.append("燃尽图判定为「")
                .append(nzText(report.getBurndownStatusLabel(), "符合计划")).append("」。");

        if (risks.isEmpty()) {
            text.append("本周期未发现明显风险，节奏正常。");
        } else {
            text.append("共需关注 ").append(risks.size()).append(" 项风险，建议优先处理：")
                    .append(risks.get(0));
            if (risks.size() > 1) {
                text.append("（其余见风险清单）");
            }
        }
        return text.toString();
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private String nzText(String value) {
        return nzText(value, "—");
    }

    private String nzText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
