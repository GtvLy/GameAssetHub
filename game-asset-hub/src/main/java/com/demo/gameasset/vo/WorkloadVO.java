package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 成员工作量统计（文档 4.4 的 {@code /stat/workload} + 5.7）。
 *
 * <h3>负载均衡度口径（文档 5.7 给的公式，这里定死细节）</h3>
 * <pre>
 *   样本     = 各 ACTIVE 成员的「任务数」{@link WorkloadItemVO#taskTotal}
 *   平均值 μ = 总和 / 人数
 *   标准差 σ = 总体标准差（分母取 n，不是 n-1）
 *   均衡度   = 1 − σ / μ
 * </pre>
 *
 * <p><b>三处必须说清楚，否则同一个数会被算出三个值：</b></p>
 * <ol>
 *   <li><b>用总体标准差（÷n）而不是样本标准差（÷n−1）</b>：这里的成员
 *       就是「全部要看的对象」，不是一个从更大总体里抽出来的样本，
 *       没有「用样本估计总体」的需求；而且 n=1 时样本标准差的分母为 0，
 *       单人项目会直接算出 NaN。文档 5.7 只写了「标准差 / 平均值」，
 *       取总体标准差是唯一不会在边界上炸掉的那个。</li>
 *   <li><b>μ = 0 时（所有人任务数都是 0）均衡度返回 {@code 1.0}</b>，不是 0、
 *       更不是 NaN。语义上「大家一样多」——一样都是 0——完全均衡，
 *       所以给 1.0 与「全部相等时 σ=0 → 均衡度 1.0」这条主路径自洽。
 *       若返回 0 或 NaN，前端会把「项目刚建立、还没排任务」显示成
 *       「工作量分配不均，建议调整」，这是一个纯粹的误报。</li>
 *   <li><b>均衡度可以为负</b>：σ/μ > 1 时（例如 3 个人是 10/0/0），
 *       {@code 1 − σ/μ < 0}。这里<b>不截断到 0</b>：截断会把「极度不均」
 *       和「完全平均」以外的中间态压成同一个值，损失了区分度。
 *       前端把负值显示成 0% 即可，取舍留在展示层。</li>
 * </ol>
 *
 * <p>{@link #balanceWarning} 的阈值按文档 5.7：均衡度 &lt; 0.5 时给提示。</p>
 *
 * @author 管理员
 */
@Data
public class WorkloadVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long projectId;

    /** 参与统计的成员数（项目内 ACTIVE 成员） */
    private Integer memberCount;

    /** 各成员的工作量明细，按故事点降序（文档 5.7 的 ORDER BY） */
    private List<WorkloadItemVO> members = new ArrayList<>();

    /** 任务数合计 */
    private Integer taskTotalSum;

    /** 人均任务数 μ，保留两位小数；无成员时为 0.0 */
    private Double averageTask;

    /** 任务数的总体标准差 σ，保留两位小数；无成员时为 0.0 */
    private Double stdDevTask;

    /**
     * 负载均衡度 = 1 − σ / μ，保留两位小数。
     * 全员任务数为 0 时取 1.0（约定见类注释）；无成员时取 1.0 同样合理。
     */
    private Double balanceIndex;

    /** 是否工作量分配不均（均衡度 &lt; 0.5） */
    private Boolean balanceWarning;

    /** 不均衡时给出的中文提示，均衡时为空字符串 */
    private String hint;

    /** 均衡度口径的文字说明，随响应下发，前端可直接展示 */
    private String balanceDefinition;
}
