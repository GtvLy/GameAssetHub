package com.demo.gameasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务评论（{@code task_comment}，文档 3.2 DDL）。
 *
 * <p>评论支持 {@code @提及}：被提及的用户ID以逗号分隔存在
 * {@link #mentionIds}，同时在评论落库时给这些人推 {@code MENTION} 通知（文档 5.8）。</p>
 *
 * <p><b>为什么存 ID 而不是用户名</b>：用户名可以改（虽然本项目未开放改名），
 * 而且渲染时要展示的是昵称，存 ID 才能批量查出来再拼。
 * 存用户名的话，用户改名后历史评论里的 @ 就指向了一个不存在的人。</p>
 *
 * @author 管理员
 */
@Data
@TableName("task_comment")
public class TaskComment implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** 冗余项目ID：单资源查询要带项目条件时不必回查 task 表 */
    private Long projectId;

    /** 评论人 */
    private Long userId;

    /** 评论内容（原文，含 @提及） */
    private String content;

    /** 被 @ 的用户ID，逗号分隔，如 {@code 2,5}；无提及时为 null */
    private String mentionIds;

    private LocalDateTime createTime;

    /** 逻辑删除：删自己的评论只置 1，历史不抹掉 */
    @TableLogic
    private Integer deleted;
}
