package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 看板卡片（文档 7.2）。
 *
 * <p>看板一次请求返回四列、每列若干卡片，因此这个 VO 的字段是
 * 「卡片上真的要画出来的东西」：优先级色条、类型标签、负责人、截止日期、
 * 评论数、关联资产。多一个字段就乘以上百张卡片，所以：</p>
 *
 * <ul>
 *   <li><b>不含 {@code description}</b>——任务描述是 TEXT，可能几百字。
 *       卡片上只展示标题，需要描述时点开详情取（文档 7.2 的卡片没有描述）。</li>
 *   <li><b>带 {@code version}</b>——前端拖拽时必须回传它，否则并发检测无从谈起。</li>
 * </ul>
 *
 * <p>所有 {@code *Label} 与 {@code *Color} 都由服务端下发：
 * 列名、优先级名、类型名、逾期判定都只有一处定义，前端不再各维护一份。</p>
 *
 * @author 张忠驰
 */
@Data
public class TaskCardVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long projectId;

    private String title;

    /** TODO / DOING / REVIEW / DONE */
    private String columnStatus;

    private String columnLabel;

    /** P0 / P1 / P2 */
    private String priority;

    private String priorityLabel;

    /** 卡片左侧色条颜色（P0 红 / P1 橙 / P2 灰），服务端下发以免两边配色不一致 */
    private String priorityColor;

    /** ART / PROGRAM / DESIGN / BUG / OTHER */
    private String taskType;

    private String taskTypeLabel;

    private String taskTypeColor;

    /** 指派给谁，可空（未认领） */
    private Long assigneeId;

    private String assigneeName;

    private String assigneeAvatar;

    private Long creatorId;

    private String creatorName;

    /** 截止日期，可空 */
    private LocalDate dueDate;

    /** 是否已逾期：有截止日期、早于今天、且未完成 */
    private Boolean overdue;

    /** 是否临近截止：今天到期或明天到期且未完成（卡片变红提醒） */
    private Boolean dueSoon;

    private BigDecimal estimateHours;

    private BigDecimal actualHours;

    private Integer storyPoint;

    /** 列内排序值。前端以服务端返回的值为准，不自己算 */
    private Integer sortOrder;

    /** 乐观锁版本号，拖拽时必须原样回传 */
    private Integer version;

    /** 评论数（看板卡片角标），由一次批量查询得到而不是逐条查 */
    private Integer commentCount;

    /** 关联资产（可空，文档 5.5） */
    private Long assetId;

    private String assetName;

    private String assetNo;

    /** 关联资产的分类标签（卡片上显示「场景道具」这类标签） */
    private String assetCategoryName;

    private String assetCategoryColor;

    /** 关联资产当前状态与中文名：卡片上直接显示「待审核」，不必再点进去 */
    private String assetStatus;

    private String assetStatusLabel;

    private String assetThumbnailUrl;

    /** 完成时间：拖入 DONE 时写入，拖出时清空 */
    private LocalDateTime finishedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
