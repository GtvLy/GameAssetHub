package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 资产完成率与状态分布（文档 4.4 的 {@code /stat/asset-completion}，环形图数据源）。
 *
 * <h3>完成率口径（必须写清楚，否则同一个数字能被解读成两种意思）</h3>
 * <pre>
 *   完成率 = 已通过审核的资产数 / 资产总数 × 100%
 *          = assetApproved / assetTotal
 * </pre>
 * <p>分子<b>只算 APPROVED</b>：待审核（PENDING）只是「提交了」，还没通过审核，
 * 把它算作完成会让完成率虚高，而且会在审核被打回时突然回落——
 * 用户看到的是「我什么都没做，进度掉了」。废弃（DEPRECATED）也<b>不计入分子</b>，
 * 但仍计入分母：废弃资产本来是项目范围内的工作，把它从分母里摘掉
 * 等于让完成率自己涨上去。</p>
 *
 * <p>分母为 0（项目还没有资产）时完成率返回 {@code 0.0}，不返回 null，
 * 前端不必为「没有资产」单独写一个分支。这个口径与项目概览页
 * {@code ProjectOverviewVO#assetCompletionRate} 完全一致——
 * 同一个概念在两个页面上必须是同一个数字。</p>
 *
 * @author 管理员
 */
@Data
public class AssetCompletionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long projectId;

    /** 资产总数（含全部状态，不含已逻辑删除） */
    private Integer assetTotal;

    /** 已通过审核（APPROVED），完成率的分子 */
    private Integer assetApproved;

    /** 待审核（PENDING） */
    private Integer assetPending;

    /** 制作中（IN_PROGRESS） */
    private Integer assetInProgress;

    /** 已打回（REJECTED） */
    private Integer assetRejected;

    /** 已废弃（DEPRECATED） */
    private Integer assetDeprecated;

    /** 完成率（%），保留一位小数；分母为 0 时为 0.0 */
    private Double completionRate;

    /** 完成率口径的文字说明，随响应下发，前端在图表下方直接显示 */
    private String completionDefinition;

    /** 状态分布，按资产状态枚举顺序排列，环形图按它依次上色 */
    private List<StatCountVO> statusDistribution = new ArrayList<>();
}
