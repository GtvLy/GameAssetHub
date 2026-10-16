package com.zhangchi.gameasset.dto;

import lombok.Data;

/**
 * 指派任务请求（文档 4.2 {@code PATCH /api/tasks/{id}/assign}）。
 *
 * @author 张忠驰
 */
@Data
public class TaskAssignDTO {

    /**
     * 指派给谁。允许为空，表示<b>取消指派</b>（把任务放回「待认领」）。
     *
     * <p>不做 {@code @NotNull}：取消指派是一个真实且常用的操作
     * （人休假了、任务重新分配），拦掉它反而要另开一个接口。</p>
     */
    private Long assigneeId;
}
