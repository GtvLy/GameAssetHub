package com.demo.gameasset.service.support;

import com.demo.gameasset.common.enums.ProjectRole;
import com.demo.gameasset.vo.WorkloadItemVO;
import com.demo.gameasset.vo.WorkloadVO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 成员工作量的负载均衡度计算（文档 5.7）。
 *
 * <pre>
 *   平均值 μ = Σ任务数 / 人数
 *   标准差 σ = √( Σ(任务数ᵢ − μ)² / n )        ← 总体标准差，分母是 n
 *   均衡度   = 1 − σ / μ
 * </pre>
 *
 * <p>纯计算，不依赖 Spring 上下文——它要覆盖的正是「除零」「全部相等」
 * 「极度不均」这三种只靠接口很难构造出来的输入。</p>
 *
 * <h3>三个刻意的取舍（都影响数字，所以写在这里）</h3>
 * <ol>
 *   <li><b>总体标准差（÷n），不是样本标准差（÷n−1）</b>：这里的成员就是
 *       「要看的全部对象」，不是从更大总体里抽的样本；而且 n = 1 时
 *       样本标准差的分母为 0，单人项目会直接算出 NaN。</li>
 *   <li><b>μ = 0 时均衡度取 {@code 1.0}</b>（所有人任务数都是 0）。
 *       语义上「大家一样多——一样都是 0」，与「全部相等 → σ=0 → 均衡度 1.0」
 *       这条主路径自洽。返回 0 或 NaN 会把「项目刚建好还没排任务」
 *       显示成「工作量分配不均，建议调整」。</li>
 *   <li><b>均衡度可以为负，不截断</b>：3 个人是 10/0/0 时 σ/μ ≈ 0.47/0.33……
 *       这类极端分布下 {@code 1 − σ/μ} 完全可能为负。截断到 0 会把
 *       「稍微不均」与「极度不均」压成同一个值，丢掉区分度；
 *       要显示成 0% 是展示层的取舍，不该在计算层做。</li>
 * </ol>
 *
 * @author 管理员
 */
@Component
public class WorkloadBalanceCalculator {

    /**
     * 均衡度低于此值即认为分配不均（文档 5.7）。
     */
    public static final double WARNING_THRESHOLD = 0.5;

    /** 不均衡时的提示文案（文档 5.7 的原文 + 可执行建议） */
    public static final String UNBALANCED_HINT =
            "工作量分配不均，建议调整：部分成员任务量明显高于或低于团队平均，"
                    + "可考虑重新分配待办任务，或让任务偏少的成员接手部分待办。";

    /** 均衡度口径说明，随响应下发 */
    public static final String BALANCE_DEFINITION =
            "负载均衡度 = 1 − 标准差 / 平均值（按各成员的任务数计算，总体标准差）。"
                    + "越接近 1 越均衡；全员任务数为 0 时取 1.0。";

    /**
     * 汇总成员工作量。
     *
     * @param projectId 项目ID
     * @param members   明细行（可空、可乱序，本方法会排序并补全派生字段）
     */
    public WorkloadVO summarize(Long projectId, List<WorkloadItemVO> members) {
        List<WorkloadItemVO> rows = members == null ? new ArrayList<>() : new ArrayList<>(members);

        WorkloadVO vo = new WorkloadVO();
        vo.setProjectId(projectId);
        vo.setMemberCount(rows.size());
        vo.setBalanceDefinition(BALANCE_DEFINITION);

        int count = rows.size();
        int sum = 0;
        for (WorkloadItemVO row : rows) {
            int taskTotal = nz(row.getTaskTotal());
            sum += taskTotal;
            row.setRoleLabel(ProjectRole.label(row.getRole()));
            row.setTaskTotal(taskTotal);
            row.setTaskDone(nz(row.getTaskDone()));
            row.setPoints(nz(row.getPoints()));
            row.setVersionUploaded(nz(row.getVersionUploaded()));
            row.setAssetApproved(nz(row.getAssetApproved()));
            row.setTaskCompletionRate(rate(row.getTaskDone(), taskTotal));
        }
        vo.setTaskTotalSum(sum);

        double average = count == 0 ? 0.0 : (double) sum / count;
        double stdDev = 0.0;
        for (WorkloadItemVO row : rows) {
            double diff = row.getTaskTotal() - average;
            stdDev += diff * diff;
        }
        stdDev = count == 0 ? 0.0 : Math.sqrt(stdDev / count);

        vo.setAverageTask(scale2(average));
        vo.setStdDevTask(scale2(stdDev));

        // 平均值为 0（含没有成员）时不能做除法：约定为「完全均衡」
        double balance = average == 0.0 ? 1.0 : 1.0 - stdDev / average;
        vo.setBalanceIndex(scale2(balance));

        boolean warning = vo.getBalanceIndex() < WARNING_THRESHOLD;
        vo.setBalanceWarning(warning);
        vo.setHint(warning ? UNBALANCED_HINT : "");

        // 「低于平均」在每个成员上标出来，前端不必自己跟平均值比
        for (WorkloadItemVO row : rows) {
            row.setBelowAverage(row.getTaskTotal() < average);
        }

        // 排序在服务端定死：前端再排一次就可能与服务端的「故事点降序」不一致。
        // SQL 里已经按故事点降序，这里只做一次稳定兜底，保证手工构造的入参也一致
        rows.sort((a, b) -> {
            int byPoints = Integer.compare(nz(b.getPoints()), nz(a.getPoints()));
            return byPoints != 0 ? byPoints : Long.compare(nz(a.getUserId()), nz(b.getUserId()));
        });
        vo.setMembers(rows);
        return vo;
    }

    /** 完成率（%），保留一位小数；分母为 0 时返回 0.0（不返回 NaN/null） */
    private Double rate(int done, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return scale1(done * 100.0 / total);
    }

    private double scale2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private double scale1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }
}
