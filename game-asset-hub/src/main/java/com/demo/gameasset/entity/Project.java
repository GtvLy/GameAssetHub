package com.demo.gameasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 项目（{@code project}）。
 *
 * @author 管理员
 */
@Data
@TableName("project")
public class Project implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 项目名称 */
    private String name;

    /** 项目代号，用于资产编号前缀（模块2 使用） */
    private String code;

    /** 项目简介 */
    private String description;

    /** 引擎：UE5 / Unity / Godot / Other */
    private String engine;

    /** 封面图地址 */
    private String coverUrl;

    /** 创建者 / 项目负责人 */
    private Long ownerId;

    /** 项目开始日期 */
    private LocalDate startDate;

    /** 计划结束日期，概览的 daysToDeadline 由它算出 */
    private LocalDate planEndDate;

    /** ACTIVE / PAUSED / ARCHIVED，见 ProjectStatus */
    private String status;

    /**
     * 成员数（冗余字段）。
     * 口径：<b>未移除的成员数</b>，含已邀请未加入的（INVITED）。
     * 创建项目时初始为 1（创建者自己），之后由
     * {@code ProjectMemberService#refreshMemberCount} 统一维护，避免多处各自加减。
     */
    private Integer memberCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除，删除项目即归档为不可见 */
    @TableLogic
    private Integer deleted;
}
