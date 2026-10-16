package com.zhangchi.gameasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 任务表（{@code task}，文档 3.2 DDL + 5.4 并发一致性）。
 *
 * <p>任务通过 {@link #assetId} 与模块2 的资产关联（文档 5.5）：
 * 从任务侧看是「这件事对应哪个资源」，从资产侧看是「这个资源还有哪些待办」。
 * 关联可空——策划排期、搭框架这类任务本来就不对应具体资产。</p>
 *
 * <p><b>{@link #sortOrder} 与 {@link #version} 分工</b>：
 * {@code sortOrder} 决定列内位置，{@code version} 决定「这次拖拽是否基于最新数据」。
 * 前者解决排序，后者解决并发——用 sortOrder 兼职做并发检测是行不通的，
 * 因为两个人把同一张卡片拖到同一位置时算出的 sortOrder 本来就可能相同。</p>
 *
 * <p><b>{@link #finishedAt} 必须与列状态同步维护</b>：
 * 拖入 DONE 时写入、拖出时必须清空。只写不清的表现是
 * 「任务已回到进行中，燃尽图却仍把它算作已完成」——
 * 模块4 的完成率与燃尽图都会因此错，而看板上看不出任何异常。</p>
 *
 * @author 张忠驰
 */
@Data
@TableName("task")
public class Task implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属项目，所有单资源查询都必须带这个条件 */
    private Long projectId;

    /** 关联资产（可空，文档 5.5） */
    private Long assetId;

    /** 任务标题 */
    private String title;

    /** 任务描述 */
    private String description;

    /** 看板列：TODO / DOING / REVIEW / DONE，见 TaskColumn */
    private String columnStatus;

    /** 优先级：P0 / P1 / P2，见 TaskPriority */
    private String priority;

    /** 任务类型：ART / PROGRAM / DESIGN / BUG / OTHER，见 TaskType */
    private String taskType;

    /** 指派给谁（可空，表示还没认领） */
    private Long assigneeId;

    /** 创建人。任务状态变更时通知的就是他（文档 5.8） */
    private Long creatorId;

    /** 截止日期（可空）。逾期判定 = 有截止日期 && 早于今天 && 未完成 */
    private LocalDate dueDate;

    /** 预估工时（可空） */
    private BigDecimal estimateHours;

    /** 实际工时（可空） */
    private BigDecimal actualHours;

    /** 故事点（可空）。模块4 燃尽图的分子 */
    private Integer storyPoint;

    /** 列内排序，大间隔整数（每次 +1000），拖拽时取中间值 */
    private Integer sortOrder;

    /** 完成时间：拖入 DONE 写入，拖出清空 */
    private LocalDateTime finishedAt;

    /**
     * 乐观锁版本号（文档 5.4）。
     *
     * <p>由服务端维护，客户端只回传它拿到的值。看板拖拽时若两人同时改同一张卡片，
     * 后提交者会在这里对不上，接口返回 409 让前端刷新，而不是静默覆盖别人的改动。</p>
     */
    @Version
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：删任务只置 1，评论与状态日志保留以便复盘 */
    @TableLogic
    private Integer deleted;
}
