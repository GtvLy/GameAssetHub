package com.zhangchi.gameasset.common.enums;

/**
 * 项目成员状态（{@code project_member.status}）。
 *
 * <p>{@code project_member} 表按文档 DDL 没有 {@code deleted} 列，
 * 成员移除是<b>状态位软删</b>：{@link #REMOVED}。这样
 * {@code uk_project_user(project_id, user_id)} 唯一约束仍能拦住重复邀请，
 * 重新邀请时把同一行改回 {@link #INVITED} 即可，不会产生孤儿数据。</p>
 *
 * @author 张忠驰
 */
public final class ProjectMemberStatus {

    /** 已邀请，尚未凭邀请码加入 */
    public static final String INVITED = "INVITED";

    /** 已加入，是 RBAC 校验唯一认可的状态 */
    public static final String ACTIVE = "ACTIVE";

    /** 已移除（软删），不出现在成员列表中 */
    public static final String REMOVED = "REMOVED";

    private ProjectMemberStatus() {
    }
}
