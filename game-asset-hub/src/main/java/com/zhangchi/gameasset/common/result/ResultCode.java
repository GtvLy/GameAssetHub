package com.zhangchi.gameasset.common.result;

import lombok.Getter;

/**
 * 统一响应状态码。
 *
 * <p>约定：{@code 200} 之外的 code 一律代表业务失败，前端拦截器据此提示。</p>
 *
 * @author 张忠驰
 */
@Getter
public enum ResultCode {

    /** 请求成功 */
    SUCCESS(200, "操作成功"),

    /** 参数校验失败 / 业务规则不满足 */
    BAD_REQUEST(400, "请求参数有误"),

    /** 未登录或登录态已失效 */
    UNAUTHORIZED(401, "未登录或登录已过期"),

    /** 已登录但无权访问该资源（RBAC 拦截） */
    FORBIDDEN(403, "无权访问该资源"),

    /** 资源不存在，或不属于当前项目 */
    NOT_FOUND(404, "资源不存在"),

    /** 并发修改冲突（乐观锁） */
    CONFLICT(409, "数据已被他人修改，请刷新后重试"),

    /** 服务器内部错误 */
    ERROR(500, "服务器内部错误"),

    /** 唯一约束冲突（用户名重复、资产编号重复等） */
    DUPLICATE(1001, "数据已存在，请勿重复提交");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
