package com.demo.gameasset.common.exception;

import com.demo.gameasset.common.result.Result;
import com.demo.gameasset.common.result.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理的<b>错误映射</b>测试。
 *
 * <p>这里盯住的是一类很容易被忽略的退化：请求本身有问题（Content-Type 不对、
 * JSON 语法错），却掉进兜底分支返回 <b>500「服务器内部错误」</b>。
 * 对调用方来说这是误导——500 意味着「去查服务端日志」，而真正的原因在请求里。
 * 项目01 真机验证时就是这么发现的（见 PROGRESS 的交接记录）。</p>
 *
 * <p>纯逻辑测试：直接构造异常对象调用处理器方法，不加载 Spring、不起 HTTP 服务。</p>
 *
 * @author 管理员
 */
@DisplayName("GlobalExceptionHandler 异常到状态码的映射")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("Content-Type 不是 application/json → 400（而不是掉进兜底变成 500）")
    void unsupportedMediaTypeIsBadRequest() {
        HttpMediaTypeNotSupportedException exception = new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_FORM_URLENCODED, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Result<Void>> response = handler.handleMediaTypeNotSupported(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
        assertThat(response.getBody().getMessage())
                .as("提示要能直接告诉调用方该怎么办")
                .contains("application/json");
    }

    @Test
    @DisplayName("JSON 语法错 / 字段类型不匹配 → 400，且提示里带上原始原因")
    void unreadableBodyIsBadRequest() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "JSON parse error", new IllegalArgumentException("Unexpected character ('}' (code 125))"));

        ResponseEntity<Result<Void>> response = handler.handleMessageNotReadable(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
        assertThat(response.getBody().getMessage()).contains("Unexpected character");
    }

    @Test
    @DisplayName("解析失败原因很长时截断，不把整段 Jackson 报文糊到提示里")
    void longReasonIsTruncated() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "JSON parse error",
                new IllegalArgumentException("首行原因\n第二行\n" + "x".repeat(300)));

        String message = handler.handleMessageNotReadable(exception).getBody().getMessage();

        assertThat(message).contains("首行原因");
        assertThat(message).doesNotContain("第二行");
        assertThat(message.length()).isLessThan(200);
    }

    @Test
    @DisplayName("HTTP 方法不被支持 → 405（不是掉进兜底变成 500）")
    void methodNotSupportedIs405() {
        HttpRequestMethodNotSupportedException exception = new HttpRequestMethodNotSupportedException(
                "GET", List.of("POST"));

        ResponseEntity<Result<Void>> response = handler.handleMethodNotSupported(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(405);
        assertThat(response.getBody().getMessage())
                .as("提示要能直接告诉调用方该用什么方法")
                .contains("GET").contains("POST");
    }

    @Test
    @DisplayName("业务异常仍按业务码返回 HTTP 状态：403 就是 403（本次改动不能碰坏它）")
    void bizExceptionKeepsItsOwnStatus() {
        ResponseEntity<Result<Void>> response =
                handler.handleBizException(BizException.forbidden("你不是该项目成员，无权访问"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("乐观锁冲突：409 走业务异常通道，HTTP 状态也是 409（前端拦截器按状态码提示刷新）")
    void conflictKeepsItsOwnStatus() {
        ResponseEntity<Result<Void>> response = handler.handleBizException(
                new BizException(ResultCode.CONFLICT, "任务已被他人修改，请刷新看板后重试"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("路径写错（不存在的接口）→ 404（不是掉进兜底变成 500）")
    void resourceNotFoundIs404() {
        NoResourceFoundException exception = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/api/projects/1/stat/typo");

        ResponseEntity<Result<Void>> response = handler.handleNoResourceFound(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(404);
        assertThat(response.getBody().getMessage())
                .as("提示里带上出错的路径，便于定位")
                .contains("/api/projects/1/stat/typo");
    }
}
