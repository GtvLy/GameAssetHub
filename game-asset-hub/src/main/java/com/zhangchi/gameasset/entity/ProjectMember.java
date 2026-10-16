package com.zhangchi.gameasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 项目成员与角色（{@code project_member}）—— RBAC 的核心载体。
 *
 * <p><b>这张表没有 {@code deleted} 列</b>（见文档 DDL），成员移除走
 * {@code status = REMOVED} 状态位软删。因为存在
 * {@code uk_project_user(project_id, user_id)} 唯一约束，逻辑删除反而会让
 * 「移除后重新邀请」撞唯一键，状态位则可以直接把同一行改回 INVITED。</p>
 *
 * <p>也正因如此，本实体<b>不能</b>加 {@code @TableLogic}：MyBatis-Plus 的
 * 全局 {@code logic-delete-field: deleted} 只对存在该属性的实体生效，
 * 这里刻意不声明该字段，查询即为物理查询，不会被注入 {@code deleted = 0}。</p>
 *
 * @author 张忠驰
 */
@Data
@TableName("project_member")
public class ProjectMember implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属项目 */
    private Long projectId;

    /** 成员用户ID */
    private Long userId;

    /** 项目内角色：OWNER / ARTIST / PROGRAMMER / DESIGNER / VIEWER */
    private String role;

    /** 成员状态：INVITED / ACTIVE / REMOVED */
    private String status;

    /**
     * 邀请码。
     * 邀请时生成并落库，凭码加入成功后<b>清空</b>——这样「已使用」不用额外加字段，
     * 清空即为已消费。未加入（INVITED）时该列有值，ACTIVE / REMOVED 时为 null。
     */
    private String inviteCode;

    /** 加入时间，凭邀请码加入时写入 */
    private LocalDateTime joinTime;

    private LocalDateTime createTime;
}
