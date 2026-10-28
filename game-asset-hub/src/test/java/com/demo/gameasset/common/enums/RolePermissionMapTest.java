package com.demo.gameasset.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RBAC 权限矩阵测试（文档 3.3 第 4 点）。
 *
 * <p>这是权限体系的单一事实来源，一旦被误改会导致「越权」或「该能用的用不了」，
 * 因此用<b>逐格比对</b>而不是抽查几个组合：把文档表格手工展开成期望矩阵，
 * 再对 5 个角色 × 10 个权限点共 50 格逐格断言。</p>
 *
 * <p>纯逻辑测试：不加载 Spring 上下文，不连 MySQL / Redis。</p>
 *
 * @author 管理员
 */
@DisplayName("RolePermissionMap 权限矩阵")
class RolePermissionMapTest {

    /** 文档 3.3 权限矩阵的期望展开，作为测试的独立事实来源（不引用生产代码的常量） */
    private static final Map<String, Set<Permission>> EXPECTED = new LinkedHashMap<>();

    static {
        // OWNER 负责人：项目设置读写 / 资产管理读写 / 状态审核 / 任务看板读写 / 统计查看
        EXPECTED.put("OWNER", EnumSet.allOf(Permission.class));

        // ARTIST 美术：项目设置只读 / 资产管理读写 / 无审核 / 看板读写 / 统计查看
        EXPECTED.put("ARTIST", EnumSet.of(
                Permission.PROJECT_READ,
                Permission.ASSET_READ, Permission.ASSET_WRITE, Permission.ASSET_DELETE,
                Permission.TASK_READ, Permission.TASK_WRITE,
                Permission.STAT_READ));

        // PROGRAMMER 程序：项目设置只读 / 资产管理只读 / 无审核 / 看板读写 / 统计查看
        EXPECTED.put("PROGRAMMER", EnumSet.of(
                Permission.PROJECT_READ,
                Permission.ASSET_READ,
                Permission.TASK_READ, Permission.TASK_WRITE,
                Permission.STAT_READ));

        // DESIGNER 策划：与程序同权
        EXPECTED.put("DESIGNER", EnumSet.of(
                Permission.PROJECT_READ,
                Permission.ASSET_READ,
                Permission.TASK_READ, Permission.TASK_WRITE,
                Permission.STAT_READ));

        // VIEWER 外部协作者：全部只读，且不给统计
        EXPECTED.put("VIEWER", EnumSet.of(
                Permission.PROJECT_READ,
                Permission.ASSET_READ,
                Permission.TASK_READ));
    }

    @Test
    @DisplayName("5 个角色 × 10 个权限点共 50 格逐格比对")
    void matrix_matchesSpecForEveryRoleAndPermission() {
        // 先确认角色集合本身没有多也没有少
        assertThat(RolePermissionMap.roles())
                .as("角色集合应与文档一致")
                .containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());

        int checked = 0;
        for (Map.Entry<String, Set<Permission>> entry : EXPECTED.entrySet()) {
            String role = entry.getKey();
            for (Permission permission : Permission.values()) {
                boolean shouldAllow = entry.getValue().contains(permission);
                assertThat(RolePermissionMap.allows(role, permission))
                        .as("角色 %s 对权限 %s 的判定", role, permission)
                        .isEqualTo(shouldAllow);
                checked++;
            }
        }
        assertThat(checked).as("应覆盖 5 × 10 = 50 格").isEqualTo(50);
    }

    @Test
    @DisplayName("permissionsOf 返回的集合与矩阵判定完全一致")
    void permissionsOf_agreesWithAllows() {
        for (String role : EXPECTED.keySet()) {
            Set<Permission> actual = RolePermissionMap.permissionsOf(role);
            assertThat(actual).as("角色 %s 的权限集合", role).isEqualTo(EXPECTED.get(role));
            for (Permission permission : Permission.values()) {
                assertThat(actual.contains(permission))
                        .as("permissionsOf 与 allows 对 %s / %s 必须一致", role, permission)
                        .isEqualTo(RolePermissionMap.allows(role, permission));
            }
        }
    }

    @Test
    @DisplayName("只有 OWNER 能管成员、改项目、审资产")
    void onlyOwnerHasManageWriteAndReviewPermissions() {
        for (String role : EXPECTED.keySet()) {
            boolean isOwner = "OWNER".equals(role);
            assertThat(RolePermissionMap.allows(role, Permission.MEMBER_MANAGE))
                    .as("%s 的成员管理权限", role).isEqualTo(isOwner);
            assertThat(RolePermissionMap.allows(role, Permission.PROJECT_WRITE))
                    .as("%s 的项目修改权限", role).isEqualTo(isOwner);
            assertThat(RolePermissionMap.allows(role, Permission.ASSET_REVIEW))
                    .as("%s 的资产审核权限", role).isEqualTo(isOwner);
        }
    }

    @Test
    @DisplayName("VIEWER 不具备任何写权限与统计权限")
    void viewerHasNoWriteOrStatPermission() {
        for (Permission permission : Permission.values()) {
            boolean isWriteOrStat = switch (permission) {
                case PROJECT_WRITE, MEMBER_MANAGE, ASSET_WRITE, ASSET_REVIEW, ASSET_DELETE,
                     TASK_WRITE, STAT_READ -> true;
                default -> false;
            };
            if (isWriteOrStat) {
                assertThat(RolePermissionMap.allows("VIEWER", permission))
                        .as("VIEWER 不应具备 %s", permission)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("ARTIST 能写资产但不能审资产")
    void artistCanWriteAssetButCannotReview() {
        assertThat(RolePermissionMap.allows("ARTIST", Permission.ASSET_WRITE)).isTrue();
        assertThat(RolePermissionMap.allows("ARTIST", Permission.ASSET_DELETE)).isTrue();
        assertThat(RolePermissionMap.allows("ARTIST", Permission.ASSET_REVIEW)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Permission.class)
    @DisplayName("未知角色对任何权限都判否（默认拒绝）")
    void unknownRoleIsDeniedForEveryPermission(Permission permission) {
        assertThat(RolePermissionMap.allows("GUEST", permission)).isFalse();
        assertThat(RolePermissionMap.allows("owner", permission))
                .as("角色字符串大小写敏感，小写 owner 不是合法角色")
                .isFalse();
        assertThat(RolePermissionMap.allows("ADMIN", permission))
                .as("ADMIN 是系统角色，不是项目角色，不该有项目内权限")
                .isFalse();
    }

    @Test
    @DisplayName("空值入参安全：不抛异常，一律判否")
    void nullArgumentsAreDeniedSafely() {
        assertThat(RolePermissionMap.allows(null, Permission.PROJECT_READ)).isFalse();
        assertThat(RolePermissionMap.allows("OWNER", null)).isFalse();
        assertThat(RolePermissionMap.allows(null, null)).isFalse();
        assertThat(RolePermissionMap.permissionsOf(null)).isEmpty();
        assertThat(RolePermissionMap.permissionsOf("NOT_A_ROLE")).isEmpty();
        assertThat(RolePermissionMap.isValidRole(null)).isFalse();
        assertThat(RolePermissionMap.isValidRole("OWNER")).isTrue();
        assertThat(RolePermissionMap.isValidRole("owner")).isFalse();
    }

    @Test
    @DisplayName("对外暴露的集合均不可变，防止被调用方就地篡改")
    void exposedCollectionsAreImmutable() {
        assertThat(RolePermissionMap.roles()).isUnmodifiable();
        assertThat(RolePermissionMap.permissionsOf("OWNER")).isUnmodifiable();
        assertThat(RolePermissionMap.matrixSnapshot()).isUnmodifiable();
    }

    @Test
    @DisplayName("matrixSnapshot 与 allows 判定一致，可供管理端展示权限说明")
    void matrixSnapshot_isConsistentWithAllows() {
        Map<String, Map<Permission, Boolean>> snapshot = RolePermissionMap.matrixSnapshot();
        assertThat(snapshot.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
        snapshot.forEach((role, row) -> {
            assertThat(row.keySet()).containsExactlyInAnyOrder(Permission.values());
            row.forEach((permission, allowed) ->
                    assertThat(allowed)
                            .as("快照中 %s / %s", role, permission)
                            .isEqualTo(EXPECTED.get(role).contains(permission)));
        });
    }
}
