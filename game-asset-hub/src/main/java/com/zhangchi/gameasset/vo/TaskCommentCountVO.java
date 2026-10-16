package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 「任务ID → 评论数」的批量统计结果（看板卡片角标）。
 *
 * <p>单独一个 VO 而不是返回 {@code Map<String,Object>}：后者要在
 * 每个调用点做类型转换与空值判断，一处写错就变成
 * {@code ClassCastException}——而且编译器帮不上任何忙。</p>
 *
 * @author 张忠驰
 */
@Data
public class TaskCommentCountVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long taskId;

    /** 评论数 */
    private Long total;
}
