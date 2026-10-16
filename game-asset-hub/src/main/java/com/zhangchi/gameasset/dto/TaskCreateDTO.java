package com.zhangchi.gameasset.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新建任务请求（文档 4.2 {@code POST /api/projects/{pid}/tasks}）。
 *
 * <p>除标题外全部可选：真实使用里最常见的操作是「先记一条待办」，
 * 负责人、工时、故事点往往是排期时才补的。强制必填只会让人先在别处记下来，
 * 再找时间录入——那看板就没人用了。</p>
 *
 * @author 张忠驰
 */
@Data
public class TaskCreateDTO {

    @NotBlank(message = "任务标题不能为空")
    @Size(max = 200, message = "任务标题不能超过 200 个字符")
    private String title;

    @Size(max = 2000, message = "任务描述不能超过 2000 个字符")
    private String description;

    /**
     * 初始看板列：TODO / DOING / REVIEW / DONE。
     * 不传按 DDL 默认进 TODO；传 DONE 时服务端会同时写 finished_at，
     * 不会出现「已完成但没有完成时间」的任务卡在燃尽图里。
     */
    private String columnStatus;

    /** P0 / P1 / P2，不传按 DDL 默认 P2 */
    private String priority;

    /** ART / PROGRAM / DESIGN / BUG / OTHER，不传按 DDL 默认 ART */
    private String taskType;

    /** 指派给谁。服务端会校验对方是该项目的 ACTIVE 成员 */
    private Long assigneeId;

    private LocalDate dueDate;

    @DecimalMin(value = "0.0", message = "预估工时不能为负数")
    private BigDecimal estimateHours;

    @Min(value = 0, message = "故事点不能为负数")
    private Integer storyPoint;

    /** 关联资产（可空，文档 5.5）。服务端会校验该资产属于本项目 */
    private Long assetId;
}
