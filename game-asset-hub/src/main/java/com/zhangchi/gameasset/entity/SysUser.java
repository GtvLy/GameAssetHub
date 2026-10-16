package com.zhangchi.gameasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统用户（{@code sys_user}）。
 *
 * <p>{@code create_time / update_time} 交给数据库默认值（DEFAULT CURRENT_TIMESTAMP /
 * ON UPDATE CURRENT_TIMESTAMP）：MyBatis-Plus 默认的 NOT_NULL 插入策略会把 null 字段
 * 排除在 INSERT 语句之外，正好让 DDL 里的默认值生效，不必再写填充器。</p>
 *
 * @author 张忠驰
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 系统角色：管理员 */
    public static final String SYS_ROLE_ADMIN = "ADMIN";

    /** 系统角色：普通用户 */
    public static final String SYS_ROLE_MEMBER = "MEMBER";

    /** 账号状态：禁用 */
    public static final int STATUS_DISABLED = 0;

    /** 账号状态：正常 */
    public static final int STATUS_ENABLED = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名（唯一） */
    private String username;

    /** BCrypt 加密后的密码，绝不返回给前端 */
    private String password;

    /** 昵称 / 显示名 */
    private String nickname;

    private String email;

    private String avatar;

    /** 系统角色：ADMIN / MEMBER */
    private String role;

    /** 0 禁用 1 正常 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除标记，由 MyBatis-Plus 全局配置接管 */
    @TableLogic
    private Integer deleted;
}
