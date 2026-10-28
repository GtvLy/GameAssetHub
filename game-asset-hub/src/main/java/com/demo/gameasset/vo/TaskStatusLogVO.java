package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务状态变更记录（文档 4.2 任务详情里的「状态变更日志」）。
 *
 * @author 管理员
 */
@Data
public class TaskStatusLogVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long taskId;

    /** 变更前的列，新建任务首次落位时为 null */
    private String fromStatus;

    /** 变更前的中文列名，为 null 时前端显示「新建」 */
    private String fromLabel;

    private String toStatus;

    private String toLabel;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime createTime;
}
