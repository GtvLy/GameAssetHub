package com.demo.gameasset.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应包装。
 *
 * <pre>
 * { "code": 200, "message": "操作成功", "data": {}, "timestamp": 1790000000000 }
 * </pre>
 *
 * @param <T> 业务数据类型
 * @author 管理员
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务状态码，见 {@link ResultCode} */
    private int code;

    /** 提示信息，失败时前端直接展示 */
    private String message;

    /** 业务数据，失败时为 null */
    private T data;

    /** 服务端时间戳（毫秒），便于排查时序问题 */
    private long timestamp;

    // ==================== 成功 ====================

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(),
                data, System.currentTimeMillis());
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data, System.currentTimeMillis());
    }

    // ==================== 失败 ====================

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> error(ResultCode resultCode) {
        return error(resultCode.getCode(), resultCode.getMessage());
    }

    public static <T> Result<T> error(ResultCode resultCode, String message) {
        return error(resultCode.getCode(), message);
    }
}
