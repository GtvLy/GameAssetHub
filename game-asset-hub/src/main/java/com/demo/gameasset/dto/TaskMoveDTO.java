package com.demo.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 拖拽移动请求（文档 4.2 的拖拽请求体 + 5.4 的乐观锁）。
 *
 * <pre>
 * PATCH /api/tasks/305/move
 * { "toColumn": "DOING", "prevTaskId": 302, "nextTaskId": 308, "version": 3 }
 * </pre>
 *
 * <p>{@code prevTaskId} / {@code nextTaskId} 是<b>落位意图</b>，不是命令：
 * 服务端会先确认这两张卡片此刻是否真的在目标列、是否相邻，
 * 认不出来就按当前列内的实际相邻关系给出合理位置，而不是让这次拖拽失败——
 * 文档 5.4 明确要求「若 prev 或 next 已被他人移动，则重新查询相邻卡片并给出合理位置」。</p>
 *
 * <p>{@code version} 是文档 5.4 要求携带的乐观锁版本号，
 * 由前端从卡片上原样回传（首次来自看板接口，之后来自上一次 move 的响应）。</p>
 *
 * @author 管理员
 */
@Data
public class TaskMoveDTO {

    /** 目标列：TODO / DOING / REVIEW / DONE */
    @NotBlank(message = "请指定要移动到哪一列")
    private String toColumn;

    /** 落位后紧邻的前一张卡片ID；为空表示拖到队首（或目标列为空） */
    private Long prevTaskId;

    /** 落位后紧邻的后一张卡片ID；为空表示拖到队尾 */
    private Long nextTaskId;

    /**
     * 客户端持有的乐观锁版本号。
     *
     * <p>必填：允许不传就等于把并发保护变成可选项，而调用方一定会为了
     * 「少传一个字段」而略过它——那样 5.4 的 409 永远不会发生，
     * 覆盖别人的改动会静默成功。</p>
     */
    @NotNull(message = "缺少乐观锁版本号 version，请刷新看板后重试")
    private Integer version;
}
