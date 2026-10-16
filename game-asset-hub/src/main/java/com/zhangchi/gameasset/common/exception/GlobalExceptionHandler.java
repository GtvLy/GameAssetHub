package com.zhangchi.gameasset.common.exception;

import com.zhangchi.gameasset.common.result.Result;
import com.zhangchi.gameasset.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>响应体始终是统一的 {@link Result}；HTTP 状态码与业务码保持一致，
 * 这样「非项目成员访问 → 403」既能在 body 里看到，也能在 HTTP 状态行看到。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：HTTP 状态码取业务码（400/401/403/404/409/500），
     * 非标准 HTTP 状态（如 1001）退回 400，避免写出非法状态行。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException e) {
        log.warn("[业务异常] code={} message={}", e.getCode(), e.getMessage());
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null || !status.isError()) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status).body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 参数校验失败：把字段错误拼成一句人话，前端直接 toast 出来。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidException(MethodArgumentNotValidException e) {
        String message = joinFieldErrors(e.getBindingResult().getFieldErrors());
        log.warn("[参数校验失败] {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ResultCode.BAD_REQUEST.getCode(), message));
    }

    /** 表单/查询参数绑定失败，同样归到 400 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e) {
        String message = joinFieldErrors(e.getBindingResult().getFieldErrors());
        log.warn("[参数绑定失败] {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ResultCode.BAD_REQUEST.getCode(), message));
    }

    /** 必填请求参数缺失 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        String message = "缺少必填参数：" + e.getParameterName();
        log.warn("[参数缺失] {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ResultCode.BAD_REQUEST.getCode(), message));
    }

    /**
     * 请求体格式不支持（{@code Content-Type} 不是 {@code application/json}）。
     *
     * <p><b>不处理会掉进兜底分支返回 500</b>，那是错的错误映射：客户端把
     * {@code Content-Type} 写成了 {@code application/x-www-form-urlencoded}、
     * 或者用 curl 时压根没带，都属于「请求本身有问题」，应当是 400 而不是
     * 「服务器内部错误」。500 会让调用方去查服务端日志，而真正的原因在请求里。</p>
     *
     * <p>注意这个处理器<b>不影响 multipart 上传</b>：上传接口消费的是
     * {@code multipart/form-data}，由 {@code @RequestPart} 正常匹配，
     * 不会抛本异常。</p>
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        String message = "请求格式不支持（" + e.getContentType()
                + "），请使用 application/json 提交请求体";
        log.warn("[请求格式不支持] {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ResultCode.BAD_REQUEST.getCode(), message));
    }

    /**
     * 请求体读不出来：JSON 语法错误、字段类型不匹配、必填字段缺失等。
     *
     * <p>同样是 400 而不是 500——报文是客户端发的，读不出来是报文的问题。
     * 提示里带上原始原因的前半段（截断到合理长度），前端照着改就行；
     * 不把 complete 的堆栈暴露出去，避免泄漏内部类名。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        String message = "请求体格式有误，无法解析为 JSON：" + briefReason(e);
        log.warn("[请求体解析失败] {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ResultCode.BAD_REQUEST.getCode(), message));
    }

    /**
     * HTTP 方法不被该接口支持（用 GET 调只支持 POST 的接口、用 DELETE 调只读接口等）。
     *
     * <p>与上面两条同一类问题：这是**客户端用错了方法**，应当是 405，
     * 而不是掉进兜底分支变成 500「服务器内部错误」——那会让排查方向完全跑偏
     * （去查服务端日志，而真正的原因在请求本身。这条同样是真机验证时发现的：
     * 用 GET 打一个 POST 接口，返回的是 500）。</p>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        String supported = e.getSupportedHttpMethods() == null
                ? "其它方法" : e.getSupportedHttpMethods().toString();
        String message = "该接口不支持 " + e.getMethod() + " 请求，请改用 " + supported;
        log.warn("[请求方法不支持] {}", message);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.error(HttpStatus.METHOD_NOT_ALLOWED.value(), message));
    }

    /**
     * 查询参数类型转换失败（{@code ?days=abc}、{@code ?weekStart=2026/09/21}、
     * {@code ?page=第一页} 等）。
     *
     * <p>与上面两条同一类问题：报文是客户端发的，读不出来是报文的问题，
     * 应当是 400。少了这个处理器，{@code MethodArgumentTypeMismatchException}
     * 会掉进兜底的 {@code Exception} 分支变成 <b>500「系统繁忙」</b>——
     * 调用方会去查服务端日志，而真正的原因是他把数字写成了字母。</p>
     *
     * <p>提示里带上参数名与原始值，前端照着改即可；不暴露目标类型，
     * 免得把内部的 DTO 结构透出去。</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "参数「" + e.getName() + "」的取值不合法："
                + e.getValue() + "，请检查格式后重试";
        log.warn("[参数类型转换失败] {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ResultCode.BAD_REQUEST.getCode(), message));
    }

    /** 取解析失败原因的首句（Jackson 的原因通常很长且带位置说明，截断到 120 字符） */    private String briefReason(HttpMessageNotReadableException e) {
        String reason = e.getMostSpecificCause() == null
                ? e.getMessage() : e.getMostSpecificCause().getMessage();
        if (reason == null) {
            return "无法解析";
        }
        String firstLine = reason.split("\n")[0].trim();
        return firstLine.length() <= 120 ? firstLine : firstLine.substring(0, 120) + "…";
    }

    /**
     * 唯一约束冲突 → 1001。
     *
     * <p>典型场景：注册时用户名重复（uk_username）、并发创建同名资产编号。
     * 这里统一转成 1001 而不是 500，前端可提示「请勿重复提交」而非「系统异常」。</p>
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("[唯一约束冲突] {}", e.getMessage());
        return ResponseEntity.ok(Result.error(ResultCode.DUPLICATE.getCode(),
                "数据已存在，请勿重复提交"));
    }

    /**
     * 请求了一个不存在的路径（路径写错、或路径参数不是期望的形状）。
     *
     * <p><b>为什么要显式处理</b>：Spring 6.1 起这类请求抛的是
     * {@link NoResourceFoundException}，不显式接住就会掉进下面的兜底分支返回
     * <b>500「服务器内部错误」</b>——可路径写错明明是客户端问题，正确语义是 404。
     * 这与模块1 起就修过的「非 JSON 请求体被兜成 500」是同一类错误映射。
     * 实测发现：{@code GET /api/projects/1/stat/typo} 返回的是 500（见 PROGRESS）。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("[路径不存在] {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error(ResultCode.NOT_FOUND.getCode(),
                        "请求的接口不存在：" + e.getResourcePath()));
    }

    /**
     * 兜底：非预期异常打 error 日志（保留堆栈便于排查），对外只暴露 500。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("[系统异常] 未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(ResultCode.ERROR.getCode(), ResultCode.ERROR.getMessage()));
    }

    /** 把字段错误拼成「字段名: 提示；字段名: 提示」 */
    private String joinFieldErrors(List<FieldError> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return ResultCode.BAD_REQUEST.getMessage();
        }
        return fieldErrors.stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("；"));
    }
}
