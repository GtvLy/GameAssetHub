package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 周报 / 月报里的「成员产出」行（文档 5.10 的第 3 段）。
 *
 * <p>与 {@link WorkloadItemVO} 的区别在于<b>统计窗口</b>：
 * 工作量统计看的是「到目前为止一共」，报表看的是「本周期内做了多少」。
 * 两者字段名相似但语义不同，共用一个类早晚会被误用，
 * 因此刻意分开定义——多一个类，少一类「这个数到底是累计还是本周」的争论。</p>
 *
 * @author 管理员
 */
@Data
public class ReportMemberVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;

    private String nickname;

    private String role;

    private String roleLabel;

    /** 本周期内新建并指派给他的任务数 */
    private Integer taskCreated;

    /** 本周期内他完成的任务数（该周期内有 to_status='DONE' 的日志） */
    private Integer taskCompleted;

    /** 当前名下未完成任务数（截至生成时刻的存量） */
    private Integer taskOpen;

    /** 本周期内他在该项目上传的资产版本数 */
    private Integer versionUploaded;

    /** 本周期内他作为负责人的资产被通过的数量 */
    private Integer assetApproved;

    /** 本周期内完成任务的故事点合计 */
    private Integer points;

    /** 本周期内填报的实际工时合计 */
    private BigDecimal actualHours;
}
