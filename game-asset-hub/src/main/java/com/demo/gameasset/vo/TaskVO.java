package com.demo.gameasset.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务详情（文档 4.2 {@code GET /api/tasks/{id}}：含评论与状态日志）。
 *
 * <p>继承 {@link TaskCardVO} 而不是重抄一遍字段：详情页与卡片展示的是
 * 同一个任务，只有「多出来的部分」不同（描述、评论、状态日志）。
 * 抄一份字段的代价不是多打几十行，而是<b>以后加字段时只会改一处</b>——
 * 卡片上有了、详情页没有，是这类复制粘贴最常见的退化。</p>
 *
 * <p>因此卡片不含的 {@code description} 在这里补上；
 * 卡片上的关联资产信息（缩略图/状态/编号）在详情页同样需要，直接复用。</p>
 *
 * @author 管理员
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TaskVO extends TaskCardVO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 任务描述。卡片不带它（体积考虑），详情页必须带 */
    private String description;

    /** 评论列表，按时间正序（先发生的先读，符合讨论的阅读顺序） */
    private List<TaskCommentVO> comments = new ArrayList<>();

    /** 状态变更日志，按时间倒序（最近的一次变化最先看到） */
    private List<TaskStatusLogVO> statusLogs = new ArrayList<>();
}
