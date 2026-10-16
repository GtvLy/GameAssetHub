package com.zhangchi.gameasset.common.util;

import com.zhangchi.gameasset.common.annotation.ProjectId;
import com.zhangchi.gameasset.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Set;

/**
 * 从 Controller 方法签名与请求路径中解析出 projectId。
 *
 * <p>做成纯静态方法（不依赖 Spring 上下文、不依赖反射获取参数名之外的任何运行时状态），
 * 是为了让「projectId 解析」这段最容易出错的逻辑可以被单元测试直接覆盖——
 * 解析错了会直接导致越权校验查错项目，是最该被测试盯住的地方。</p>
 *
 * <p>解析优先级：</p>
 * <ol>
 *   <li>{@link ProjectId} 显式标注的参数；</li>
 *   <li>{@code @PathVariable("projectId")} 或 {@code @PathVariable("pid")}；</li>
 *   <li>{@code @PathVariable("id")} —— <b>仅当</b>请求路径里出现 {@code projects} 段时
 *       才认，否则 {@code /api/assets/{id}} 的资产ID会被误当成项目ID；</li>
 *   <li>请求头 {@code X-Project-Id}（前端全局注入的项目上下文）；</li>
 *   <li>都不满足 → 400，直接拒绝而不是猜。</li>
 * </ol>
 *
 * <p><b>请求头的值是参数传进来的，不在这里读环境</b>：本类保持「纯函数」——
 * 只做反射读注解与取值，不碰 Spring 上下文，这样上面这条优先级链才能被
 * 单元测试逐格覆盖。谁负责把请求头取出来？Web 层的
 * {@code ProjectPermissionAspect}（它本来就要取当前请求 URI）。</p>
 *
 * <p><b>为什么不能靠 Controller 声明 {@code HttpServletRequest} 参数来取头</b>：
 * 那是本类早期版本的实现，但项目里没有一个控制器这么写。结果是
 * 「文档说资产维度接口用 {@code X-Project-Id} 定位项目」与
 * 「解析器只有拿到请求参数才看得到头」自相矛盾——{@code /api/assets/**}
 * 十几个接口全部 400。接口的真实契约必须能在解析器里表达出来，
 * 而不是依赖每个控制器记得多写一个参数。</p>
 *
 * @author 张忠驰
 */
public final class ProjectIdResolver {

    /** 前端注入当前项目上下文的请求头 */
    public static final String HEADER_PROJECT_ID = "X-Project-Id";

    /** 直接表达「项目ID」的路径变量名 */
    private static final Set<String> DIRECT_NAMES = Set.of("projectId", "pid");

    /** 路径中代表「项目」的段名，用于判断裸 id 是不是项目ID */
    private static final Set<String> PROJECT_SEGMENTS = Set.of("projects", "project");

    private ProjectIdResolver() {
    }

    /**
     * 解析 projectId。
     *
     * @param method           Controller 方法（用于读取参数注解）
     * @param args             方法实参（与 {@code method.getParameters()} 顺序一致）
     * @param requestUri       当前请求 URI，可为 null（非 Web 上下文调用时）
     * @param headerProjectId  请求头 {@code X-Project-Id} 的原始值，可为 null；
     *                         由 Web 层取出后传入，本类只负责解析
     * @return 解析出的项目ID，非空
     * @throws BizException 400 无法解析时抛出
     */
    public static Long resolve(Method method, Object[] args, String requestUri, String headerProjectId) {
        if (method == null) {
            throw BizException.badRequest("无法解析项目ID：方法签名缺失");
        }
        Parameter[] parameters = method.getParameters();
        Object[] safeArgs = args == null ? new Object[0] : args;

        // ---- 1. @ProjectId 显式标注，优先级最高 ----
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(ProjectId.class)) {
                Long value = toLong(valueAt(safeArgs, i));
                if (value != null) {
                    return value;
                }
            }
        }

        // ---- 2/3. 路径变量 ----
        boolean projectScopedPath = isProjectScopedPath(requestUri);
        for (int i = 0; i < parameters.length; i++) {
            PathVariable pathVariable = parameters[i].getAnnotation(PathVariable.class);
            if (pathVariable == null) {
                continue;
            }
            String name = resolvePathVariableName(pathVariable, parameters[i]);
            boolean isDirect = DIRECT_NAMES.contains(name);
            boolean isProjectScopedId = "id".equals(name) && projectScopedPath;
            if (isDirect || isProjectScopedId) {
                Long value = toLong(valueAt(safeArgs, i));
                if (value != null) {
                    return value;
                }
            }
        }

        // ---- 4. 请求头兜底 ----
        // 4a. Controller 自己声明了 HttpServletRequest 的写法（保留兼容，项目里目前没有）
        for (Object arg : safeArgs) {
            if (arg instanceof HttpServletRequest request) {
                Long value = toLong(request.getHeader(HEADER_PROJECT_ID));
                if (value != null) {
                    return value;
                }
            }
        }
        // 4b. 生产路径：Web 层把请求头值传进来。资产维度的接口
        //     （/api/assets/{id} 等）没有项目段，全靠这一条定位项目
        Long fromHeader = toLong(headerProjectId);
        if (fromHeader != null) {
            return fromHeader;
        }

        throw BizException.badRequest(
                "无法解析项目ID：请在路径中携带项目ID，或通过 " + HEADER_PROJECT_ID + " 请求头指定");
    }

    /**
     * 取 {@code @PathVariable} 的名字。
     * 项目里所有 {@code @PathVariable} 都会显式写名字，此处仍保留
     * {@code Parameter#getName()} 兜底（依赖编译期 {@code -parameters}）。
     */
    private static String resolvePathVariableName(PathVariable annotation, Parameter parameter) {
        if (annotation.value() != null && !annotation.value().isEmpty()) {
            return annotation.value();
        }
        if (annotation.name() != null && !annotation.name().isEmpty()) {
            return annotation.name();
        }
        return parameter.getName();
    }

    /**
     * 请求路径是否属于「项目维度」。
     * 例：{@code /api/projects/7} → true；{@code /api/assets/9} → false。
     */
    private static boolean isProjectScopedPath(String requestUri) {
        if (requestUri == null || requestUri.isEmpty()) {
            return false;
        }
        String path = requestUri;
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        for (String segment : path.split("/")) {
            if (PROJECT_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static Object valueAt(Object[] args, int index) {
        return index < args.length ? args[index] : null;
    }

    /** 宽松转 Long：支持 Long / Integer / String，非法值返回 null 交给上层继续尝试 */
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
