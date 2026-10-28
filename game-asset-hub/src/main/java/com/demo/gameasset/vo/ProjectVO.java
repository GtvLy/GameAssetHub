package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 项目列表项 / 详情。
 *
 * <p>比实体多两个「我的视角」字段：{@link #myRole}（我在该项目中的角色）与
 * {@link #owner}（我是不是负责人）。前端据此决定「新建」「邀请成员」等按钮是否可用。</p>
 *
 * @author 管理员
 */
@Data
public class ProjectVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private String code;

    private String description;

    private String engine;

    private String coverUrl;

    private Long ownerId;

    private LocalDate startDate;

    private LocalDate planEndDate;

    /** ACTIVE / PAUSED / ARCHIVED */
    private String status;

    /** 状态中文名，省得前端再维护一份映射 */
    private String statusLabel;

    /** 成员数（未移除口径，含已邀请未加入） */
    private Integer memberCount;

    /** 我在该项目中的角色 */
    private String myRole;

    /** 我是否具备该项目的管理权限（OWNER） */
    private Boolean canManage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
