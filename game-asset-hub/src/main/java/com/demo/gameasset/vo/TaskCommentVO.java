package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务评论（文档 4.2 评论列表 / 发表评论）。
 *
 * @author 管理员
 */
@Data
public class TaskCommentVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long taskId;

    private Long userId;

    private String userName;

    private String userAvatar;

    /** 评论原文（含 @提及 文本，前端按 mentionNames 高亮渲染） */
    private String content;

    /** 被 @ 的用户ID */
    private List<Long> mentionIds = new ArrayList<>();

    /** 被 @ 的用户显示名，前端直接高亮，不必自己去用户表里找 */
    private List<String> mentionNames = new ArrayList<>();

    /**
     * 当前登录用户能否删除这条评论。
     *
     * <p>由服务端算好下发，而不是让前端比 {@code userId}：
     * 「能不能删」是权限判断，判断的依据将来可能变（例如允许 OWNER 删任何评论），
     * 那时前端不该跟着发一次版。</p>
     */
    private Boolean canDelete;

    private LocalDateTime createTime;
}
