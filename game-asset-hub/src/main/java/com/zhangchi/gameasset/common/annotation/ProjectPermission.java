package com.zhangchi.gameasset.common.annotation;

import com.zhangchi.gameasset.common.enums.Permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 项目级权限注解。标注在 Controller 方法上，由
 * {@code ProjectPermissionAspect} 在进入业务逻辑前完成校验。
 *
 * <p>校验两件事：</p>
 * <ol>
 *   <li>当前登录用户是该项目的 <b>ACTIVE</b> 成员（不是 → 403「你不是该项目成员」）；</li>
 *   <li>该成员的项目角色具备 {@link #value()} 权限（不具备 → 403「当前角色无权执行此操作」）。</li>
 * </ol>
 *
 * <p>用法：</p>
 * <pre>
 * &#64;ProjectPermission(Permission.MEMBER_MANAGE)
 * &#64;PostMapping("/projects/{projectId}/members")
 * public Result&lt;ProjectMemberVO&gt; invite(&#64;PathVariable("projectId") Long projectId, ...) { ... }
 * </pre>
 *
 * @author 张忠驰
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProjectPermission {

    /** 执行该方法所需的项目权限点 */
    Permission value();
}
