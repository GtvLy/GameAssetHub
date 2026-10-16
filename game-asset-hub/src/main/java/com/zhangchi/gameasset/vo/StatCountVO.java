package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 「某一类有多少个」的通用计数项（状态分布、分类分布等图表共用）。
 *
 * <p>{@link #label} 与 {@link #color} 都由服务端下发：环形图的图例文字与配色
 * 应当与看板列头、资产状态标签完全一致，两边各维护一份配色，
 * 改一处忘一处就会出现「同一个状态在列表里是绿的、在环形图里是蓝的」。</p>
 *
 * @author 张忠驰
 */
@Data
public class StatCountVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 机器可读的键（状态码 / 分类编码），前端做筛选或联动时用它 */
    private String key;

    /** 中文展示名 */
    private String label;

    /** 图例颜色（十六进制），前端直接用作 ECharts 的 itemStyle.color */
    private String color;

    private Integer count;

    /**
     * 占比（%），保留一位小数。
     * 注意是「占全部分类的比例」，不是完成率——完成率在
     * {@code AssetCompletionVO#completionRate} 上，两者分母不同。
     */
    private Double ratio;
}
