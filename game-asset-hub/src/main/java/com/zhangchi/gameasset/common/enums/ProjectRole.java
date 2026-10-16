package com.zhangchi.gameasset.common.enums;

/**
 * 项目内角色常量（存于 {@code project_member.role}）。
 *
 * <p>用常量类而非枚举，是因为它要直接写库、并在 MyBatis-Plus 的
 * Lambda 条件里与数据库字符串比较，保持字符串语义最省心。
 * 权限判定统一走 {@link RolePermissionMap}。</p>
 *
 * @author 张忠驰
 */
public final class ProjectRole {

    /** 负责人：项目创建者自动获得，唯一可管理成员与审核资产的角色 */
    public static final String OWNER = "OWNER";

    /** 美术：资产读写 */
    public static final String ARTIST = "ARTIST";

    /** 程序：资产只读、看板读写 */
    public static final String PROGRAMMER = "PROGRAMMER";

    /** 策划：资产只读、看板读写 */
    public static final String DESIGNER = "DESIGNER";

    /** 外部协作者：全部只读 */
    public static final String VIEWER = "VIEWER";

    private ProjectRole() {
    }

    /** 角色中文名，用于日志与前端展示兜底 */
    public static String label(String role) {
        if (role == null) {
            return "未知";
        }
        return switch (role) {
            case OWNER -> "负责人";
            case ARTIST -> "美术";
            case PROGRAMMER -> "程序";
            case DESIGNER -> "策划";
            case VIEWER -> "外部协作者";
            default -> role;
        };
    }
}
