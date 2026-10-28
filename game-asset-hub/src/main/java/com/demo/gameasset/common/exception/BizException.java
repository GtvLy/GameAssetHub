package com.demo.gameasset.common.exception;

import com.demo.gameasset.common.result.ResultCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常。所有可预期的失败都用它抛出，由 {@link GlobalExceptionHandler} 统一转成响应。
 *
 * <p>约定：<b>越权一律用 403</b>，不要用 404 掩盖，否则接口测试无法区分
 * 「资源不存在」与「无权限访问」。</p>
 *
 * @author 管理员
 */
@Getter
public class BizException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务状态码 */
    private final int code;

    public BizException(String message) {
        this(ResultCode.BAD_REQUEST.getCode(), message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    public BizException(ResultCode resultCode, String message) {
        this(resultCode.getCode(), message);
    }

    // ==================== 语义化快捷方法 ====================

    /** 400 参数有误 */
    public static BizException badRequest(String message) {
        return new BizException(ResultCode.BAD_REQUEST.getCode(), message);
    }

    /** 401 未登录 */
    public static BizException unauthorized(String message) {
        return new BizException(ResultCode.UNAUTHORIZED.getCode(), message);
    }

    /** 403 越权——数据隔离的关键返回码 */
    public static BizException forbidden(String message) {
        return new BizException(ResultCode.FORBIDDEN.getCode(), message);
    }

    /** 404 资源不存在或不属于当前项目 */
    public static BizException notFound(String message) {
        return new BizException(ResultCode.NOT_FOUND.getCode(), message);
    }
}
