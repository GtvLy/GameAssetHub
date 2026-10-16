package com.zhangchi.gameasset.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 更新任务请求（文档 4.2 {@code PUT /api/tasks/{id}}）。
 *
 * <p><b>刻意不含 {@code columnStatus}</b>：改列只有拖拽
 * （{@code PATCH /api/tasks/{id}/move}）一条路径。两条路径的表现会分叉——
 * 走 move 会算列内排序、写状态日志、维护 {@code finished_at}、推状态变更通知；
 * 走 update 则要再实现一遍这些，早晚会漏掉其中一项
 * （最典型的是漏写 {@code finished_at}，而看板上完全看不出来）。
 * 需要「用下拉菜单改状态」时，前端调 move 并只传 {@code toColumn} 即可。</p>
 *
 * <p>同理也不含 {@code version}：这里改的是任务内容，不是排序位置的并发抢占。
 * 并发冲突由服务层用乐观锁兜（对不上返回 409），不需要客户端参与。</p>
 *
 * @author 张忠驰
 */
@Data
public class TaskUpdateDTO {

    @NotBlank(message = "任务标题不能为空")
    @Size(max = 200, message = "任务标题不能超过 200 个字符")
    private String title;

    @Size(max = 2000, message = "任务描述不能超过 2000 个字符")
    private String description;

    /** P0 / P1 / P2 */
    private String priority;

    /** ART / PROGRAM / DESIGN / BUG / OTHER */
    private String taskType;

    /**
     * 负责人。传 null 表示取消指派。
     *
     * <p>与 {@link TaskAssignDTO} 的区别：这里是「编辑任务时顺手改负责人」，
     * 单点指派走 assign 接口（它会单独推一次 TASK_ASSIGNED 通知）。
     * 两个入口最终都调用同一段「改负责人 + 推通知」的逻辑，不会有两份实现。</p>
     */
    private Long assigneeId;

    private LocalDate dueDate;

    @DecimalMin(value = "0.0", message = "预估工时不能为负数")
    private BigDecimal estimateHours;

    @DecimalMin(value = "0.0", message = "实际工时不能为负数")
    private BigDecimal actualHours;

    @Min(value = 0, message = "故事点不能为负数")
    private Integer storyPoint;

    /** 关联资产；传 null 表示解除关联 */
    private Long assetId;
}
