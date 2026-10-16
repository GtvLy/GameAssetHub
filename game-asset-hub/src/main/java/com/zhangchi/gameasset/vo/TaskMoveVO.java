package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 拖拽落位结果（文档 4.2 {@code PATCH /api/tasks/{id}/move}）。
 *
 * <p>文档明确要求「<b>响应返回服务端计算的最终 sortOrder</b>，前端以之为准
 * （避免多端并发导致排序漂移）」。前端做了乐观更新，本地先按自己的想法摆了位置，
 * 服务端的这个值是唯一的权威结果。</p>
 *
 * <p><b>为什么还要返回 {@code version}</b>：乐观锁的版本号每次写操作都会 +1。
 * 如果响应不给新版本，前端手里的版本立刻过期——同一张卡片连续拖第二次
 * 必然收到 409，而用户看到的却是「我明明只是又挪了一下」。
 * 文档正文没提这一点，但少了它 5.4 的乐观锁在真实交互里是没法用的。</p>
 *
 * @author 张忠驰
 */
@Data
public class TaskMoveVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 落位后的列 */
    private String columnStatus;

    private String columnLabel;

    /** 落位后的列内排序值，前端以它为准修正本地顺序 */
    private Integer sortOrder;

    /** 落位后的版本号，前端必须用它覆盖本地值，否则下一次拖拽会 409 */
    private Integer version;

    /** 完成时间：拖入 DONE 时非空，拖出 DONE 时为 null */
    private LocalDateTime finishedAt;

    /** 本次是否触发了整列重排（相邻间距被磨平时的兜底动作） */
    private Boolean rebalanced;

    /** 整列重排连带更新的卡片数，0 表示只动了一条 */
    private Integer reorderedCount;

    /** 任务标题，前端拼提示语用（「XXX 已移动到进行中」） */
    private String title;
}
