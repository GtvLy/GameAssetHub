package com.zhangchi.gameasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 项目报表快照（{@code project_report}，文档 3.2 DDL + 5.10）。
 *
 * <p>周报 / 月报每次生成都会把当时算出来的整份报表写进 {@link #snapshotJson}。
 * 为什么要留快照而不是每次都实时重算：报表是「发给团队看的结论」，
 * 一周后有人问「上周报里写的逾期 3 个是怎么来的」，实时重算出来的数字
 * 已经变了，无法复盘。</p>
 *
 * <p><b>本实体刻意不继承任何逻辑删除字段</b>：快照只追加，不提供删除接口。</p>
 *
 * <p>写入只能走 {@code ReportMapper#upsertSnapshot}（{@code INSERT ... ON DUPLICATE KEY UPDATE}），
 * 不能写成「先查后插」——见该方法的注释。</p>
 *
 * @author 张忠驰
 */
@Data
@TableName("project_report")
public class ProjectReport implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属项目 */
    private Long projectId;

    /** {@code WEEK} 周报 / {@code MONTH} 月报 */
    private String reportType;

    /** 周期起始日（周一的日期 / 当月 1 日） */
    private LocalDate periodStart;

    /** 周期结束日（周一 + 6 天 / 当月最后一天） */
    private LocalDate periodEnd;

    /** 报表快照 JSON。由 {@code ReportSnapshotWriter} 手工拼装，见那里的说明 */
    private String snapshotJson;

    private LocalDateTime createTime;
}
