package com.demo.gameasset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评论请求（文档 4.2 {@code POST /api/tasks/{id}/comments}）。
 *
 * @author 管理员
 */
@Data
public class CommentCreateDTO {

    /**
     * 评论内容，支持 {@code @用户名} 提及。
     *
     * <p>长度上限取 2000：{@code task_comment.content} 是 TEXT 不限制，
     * 但评论框里贴一整份日志不是协作，是刷屏——真正的长文应该进
     * 任务描述或资产说明。</p>
     */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 2000, message = "评论内容不能超过 2000 个字符")
    private String content;
}
