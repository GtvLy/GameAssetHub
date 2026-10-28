package com.demo.gameasset.service.support;

import com.demo.gameasset.common.enums.Permission;
import com.demo.gameasset.common.enums.RolePermissionMap;
import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.entity.ProjectMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 资产操作的<b>服务层</b>权限闸门。
 *
 * <p><b>为什么切面之外还要一道</b>：{@code ProjectPermissionAspect} 只在
 * HTTP 请求经过 Controller 时生效。有两类场景它是拦不到的：</p>
 * <ol>
 *   <li><b>同一接口内按参数细分权限</b>。状态流转接口 {@code PATCH /assets/{id}/status}
 *       同时承担「提交审核」（OWNER 与 ARTIST 都可以）与「审核通过/打回」
 *       （仅 OWNER）。注解只能标一个权限点，只能标较低的
 *       {@code ASSET_WRITE}，细粒度判定必须落到服务层。</li>
 *   <li><b>非 HTTP 入口</b>。定时任务、其他 Service 的内部调用都不经过切面。</li>
 * </ol>
 *
 * <p>判断逻辑统一走 {@link RolePermissionMap#allows}，不在业务代码里写
 * {@code "OWNER".equals(role)}——新增角色或调整权限矩阵时只改一处。</p>
 *
 * @author 管理员
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetActionGuard {

    private final ProjectAccessGuard projectAccessGuard;

    /**
     * 要求当前用户具备指定权限，否则 403。
     *
     * @param projectId  项目ID，用于在 {@link UserContext} 没有角色时回查成员表
     * @param permission 需要的权限点
     */
    public void require(Long projectId, Permission permission) {
        String role = resolveRole(projectId);
        if (!RolePermissionMap.allows(role, permission)) {
            log.warn("[RBAC] 拒绝操作：projectId={} role={} 缺少权限 {}", projectId, role, permission);
            throw BizException.forbidden("当前角色（" + (role == null ? "非成员" : role)
                    + "）无权执行此操作：" + permission.getLabel());
        }
    }

    /**
     * 取当前用户在该项目中的角色。
     *
     * <p>优先用切面写进 {@link UserContext} 的角色（省一次查询），
     * 拿不到时回查成员表——这条兜底路径正是「非 HTTP 入口」能用同一个
     * 判断逻辑的原因，也避免了「忘了设 UserContext 就默认放行」的漏洞。</p>
     */
    private String resolveRole(Long projectId) {
        String role = UserContext.getProjectRole();
        if (role != null) {
            return role;
        }
        ProjectMember member = projectAccessGuard.requireActiveMember(projectId, UserContext.requireUserId());
        return member.getRole();
    }
}
