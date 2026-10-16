package com.zhangchi.gameasset.aspect;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhangchi.gameasset.common.annotation.ProjectPermission;
import com.zhangchi.gameasset.common.enums.Permission;
import com.zhangchi.gameasset.common.enums.ProjectMemberStatus;
import com.zhangchi.gameasset.common.enums.RolePermissionMap;
import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.common.util.ProjectIdResolver;
import com.zhangchi.gameasset.context.UserContext;
import com.zhangchi.gameasset.entity.ProjectMember;
import com.zhangchi.gameasset.mapper.ProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 项目级 RBAC 切面（文档 5.1 第一层：项目归属校验）。
 *
 * <p>进入任何标注了 {@link ProjectPermission} 的业务方法前，先确认
 * 「是该项目 ACTIVE 成员」且「角色具备所需权限」，两处任一不满足都抛 403。</p>
 *
 * <p>校验通过后把 {@code projectId} 与项目角色写进 {@link UserContext}，
 * 后续 Service 层即可直接取用，不必层层传参。</p>
 *
 * <p><b>与第二层的关系</b>：本切面只保证「人是这个项目的」，
 * 不保证「这条数据是这个项目的」——后者由各 Mapper 查询条件强制携带
 * {@code project_id} 完成。两层缺一不可。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ProjectPermissionAspect {

    private final ProjectMemberMapper projectMemberMapper;

    @Around("@annotation(projectPermission)")
    public Object check(ProceedingJoinPoint joinPoint, ProjectPermission projectPermission) throws Throwable {

        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw BizException.unauthorized("未登录或登录已过期");
        }

        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Long projectId = ProjectIdResolver.resolve(method, joinPoint.getArgs(),
                currentRequestUri(), currentProjectIdHeader());

        // 关键查询：project_id + user_id + status 三个条件缺一不可。
        // 只按 user_id 查会拿到用户在其他项目里的角色，造成跨项目越权。
        ProjectMember member = projectMemberMapper.selectOne(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, userId)
                        .eq(ProjectMember::getStatus, ProjectMemberStatus.ACTIVE));

        if (member == null) {
            log.warn("[RBAC] 拒绝访问：userId={} 不是项目 {} 的 ACTIVE 成员", userId, projectId);
            throw BizException.forbidden("你不是该项目成员，无权访问");
        }

        Permission required = projectPermission.value();
        if (!RolePermissionMap.allows(member.getRole(), required)) {
            log.warn("[RBAC] 拒绝操作：userId={} 在项目 {} 的角色 {} 缺少权限 {}",
                    userId, projectId, member.getRole(), required);
            throw BizException.forbidden("当前角色（" + member.getRole() + "）无权执行此操作："
                    + required.getLabel());
        }

        UserContext.setProjectId(projectId);
        UserContext.setProjectRole(member.getRole());
        return joinPoint.proceed();
    }

    /** 取当前请求 URI，供 {@link ProjectIdResolver} 判断裸 id 是否代表项目 */
    private String currentRequestUri() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest().getRequestURI();
    }

    /**
     * 取 {@code X-Project-Id} 请求头，作为项目ID的最后兜底来源。
     *
     * <p>资产维度（{@code /api/assets/{id}}）、上传（{@code /api/assets/upload/*}）
     * 这些路径里没有项目段，请求头是唯一的项目上下文——前端拦截器全局注入。
     * 取头这件事必须在这里做：{@link ProjectIdResolver} 是纯函数，
     * 我们不希望它去读 Spring 的 RequestContextHolder。</p>
     */
    private String currentProjectIdHeader() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null
                ? null
                : attributes.getRequest().getHeader(ProjectIdResolver.HEADER_PROJECT_ID);
    }
}
