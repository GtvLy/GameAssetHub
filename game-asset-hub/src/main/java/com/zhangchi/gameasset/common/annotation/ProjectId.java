package com.zhangchi.gameasset.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式标记「这个参数就是项目ID」，供 {@code ProjectIdResolver} 解析。
 *
 * <p>绝大多数接口的项目ID就是路径变量 {@code projectId} / {@code pid}，
 * 或者 {@code /api/projects/{id}} 里的 {@code id}，不需要这个注解。
 * 只有路径里同时存在多个 id（例如将来的
 * {@code /api/projects/{projectId}/assets/{assetId}/versions/{vid}}）
 * 且命名无法自动识别时，才用它消除歧义。</p>
 *
 * @author 张忠驰
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProjectId {
}
