package com.demo.gameasset.common.enums;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 角色 → 权限集合的<b>唯一</b>映射表（文档 3.3 第 4 点 RBAC 权限矩阵）。
 *
 * <p>权限矩阵：</p>
 * <pre>
 * 角色          项目设置   资产管理   状态审核   任务看板   统计查看
 * OWNER 负责人   读写      读写       ✅        读写       ✅
 * ARTIST 美术    只读      读写       ❌        读写       ✅
 * PROGRAMMER 程序 只读     只读       ❌        读写       ✅
 * DESIGNER 策划   只读      只读       ❌        读写       ✅
 * VIEWER 协作者   ❌        只读       ❌        只读       ❌
 * </pre>
 *
 * <p><b>映射的落地取法</b>：以文档 5.1 给出的枚举清单为准
 * （{@code ROLE_PERMS}），它与 3.3 表格逐格对应——表格里的「项目设置只读」
 * 展开为 {@code PROJECT_READ}，「资产管理读写」展开为
 * {@code ASSET_READ + ASSET_WRITE + ASSET_DELETE}。
 * VIEWER 的「项目设置 ❌」在 5.1 中保留了 {@code PROJECT_READ}，
 * 因为 VIEWER 必须先能读到项目才谈得上「资产只读」；此处沿用 5.1。</p>
 *
 * <p>实现上刻意不写成静态初始化块里的裸 {@code Map.of}：权限集合不可变，
 * 且暴露 {@link #roles()} / {@link #permissionsOf(String)} 供单元测试逐格比对。</p>
 *
 * @author 管理员
 */
public final class RolePermissionMap {

    /** 项目负责人：项目内全权限 */
    private static final Set<Permission> OWNER_PERMS = Collections.unmodifiableSet(
            EnumSet.allOf(Permission.class));

    /**
     * 美术：项目设置只读、资产管理读写（含删除）、无审核权、看板读写、可看统计。
     * 注意 ARTIST 有 {@code ASSET_DELETE} 但没有 {@code ASSET_REVIEW}——
     * 制作者能删自己的草稿，但不能自己审自己。
     */
    private static final Set<Permission> ARTIST_PERMS = Collections.unmodifiableSet(
            EnumSet.of(Permission.PROJECT_READ,
                    Permission.ASSET_READ, Permission.ASSET_WRITE, Permission.ASSET_DELETE,
                    Permission.TASK_READ, Permission.TASK_WRITE,
                    Permission.STAT_READ));

    /** 程序：资产管理只读，看板读写，可看统计 */
    private static final Set<Permission> PROGRAMMER_PERMS = Collections.unmodifiableSet(
            EnumSet.of(Permission.PROJECT_READ,
                    Permission.ASSET_READ,
                    Permission.TASK_READ, Permission.TASK_WRITE,
                    Permission.STAT_READ));

    /** 策划：与程序同权（资产管理只读、看板读写） */
    private static final Set<Permission> DESIGNER_PERMS = Collections.unmodifiableSet(
            EnumSet.of(Permission.PROJECT_READ,
                    Permission.ASSET_READ,
                    Permission.TASK_READ, Permission.TASK_WRITE,
                    Permission.STAT_READ));

    /**
     * 外部协作者：项目只读、资产只读、看板只读。
     * 无 STAT_READ（不给外部人员看进度统计），无任何写权限。
     */
    private static final Set<Permission> VIEWER_PERMS = Collections.unmodifiableSet(
            EnumSet.of(Permission.PROJECT_READ,
                    Permission.ASSET_READ,
                    Permission.TASK_READ));

    /** 角色 → 权限集合。保持插入顺序，便于前端/日志展示 */
    private static final Map<String, Set<Permission>> ROLE_PERMS;

    static {
        Map<String, Set<Permission>> map = new LinkedHashMap<>();
        map.put(ProjectRole.OWNER, OWNER_PERMS);
        map.put(ProjectRole.ARTIST, ARTIST_PERMS);
        map.put(ProjectRole.PROGRAMMER, PROGRAMMER_PERMS);
        map.put(ProjectRole.DESIGNER, DESIGNER_PERMS);
        map.put(ProjectRole.VIEWER, VIEWER_PERMS);
        ROLE_PERMS = Collections.unmodifiableMap(map);
    }

    private RolePermissionMap() {
    }

    /**
     * 判断某个项目角色是否具备指定权限。
     *
     * @param role       项目角色，见 {@link ProjectRole}
     * @param permission 需要的权限点
     * @return 具备返回 true；角色为空、非法或权限不足均返回 false（<b>默认拒绝</b>）
     */
    public static boolean allows(String role, Permission permission) {
        if (role == null || permission == null) {
            return false;
        }
        Set<Permission> perms = ROLE_PERMS.get(role);
        return perms != null && perms.contains(permission);
    }

    /**
     * 取某个角色的全部权限（只读视图）。未知角色返回空集合。
     */
    public static Set<Permission> permissionsOf(String role) {
        if (role == null) {
            return Collections.emptySet();
        }
        return ROLE_PERMS.getOrDefault(role, Collections.emptySet());
    }

    /**
     * 全部合法角色（保持定义顺序：OWNER / ARTIST / PROGRAMMER / DESIGNER / VIEWER）。
     */
    public static Set<String> roles() {
        return ROLE_PERMS.keySet();
    }

    /** 角色是否合法（用于 DTO 校验，避免把任意字符串写进 project_member.role） */
    public static boolean isValidRole(String role) {
        return role != null && ROLE_PERMS.containsKey(role);
    }

    /** 面向管理端的权限矩阵快照：角色 → 权限 → 是否具备，仅供只读使用 */
    public static Map<String, Map<Permission, Boolean>> matrixSnapshot() {
        Map<String, Map<Permission, Boolean>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Permission>> entry : ROLE_PERMS.entrySet()) {
            Map<Permission, Boolean> row = new EnumMap<>(Permission.class);
            for (Permission p : Permission.values()) {
                row.put(p, entry.getValue().contains(p));
            }
            snapshot.put(entry.getKey(), Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableMap(snapshot);
    }
}
