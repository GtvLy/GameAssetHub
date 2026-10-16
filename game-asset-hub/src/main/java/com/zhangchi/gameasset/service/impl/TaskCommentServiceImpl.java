package com.zhangchi.gameasset.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhangchi.gameasset.common.enums.NotificationType;
import com.zhangchi.gameasset.common.enums.ProjectMemberStatus;
import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.context.UserContext;
import com.zhangchi.gameasset.dto.CommentCreateDTO;
import com.zhangchi.gameasset.entity.ProjectMember;
import com.zhangchi.gameasset.entity.SysUser;
import com.zhangchi.gameasset.entity.Task;
import com.zhangchi.gameasset.entity.TaskComment;
import com.zhangchi.gameasset.mapper.ProjectMemberMapper;
import com.zhangchi.gameasset.mapper.SysUserMapper;
import com.zhangchi.gameasset.mapper.TaskCommentMapper;
import com.zhangchi.gameasset.mapper.TaskMapper;
import com.zhangchi.gameasset.service.NotificationService;
import com.zhangchi.gameasset.service.TaskCommentService;
import com.zhangchi.gameasset.service.support.MentionParser;
import com.zhangchi.gameasset.vo.TaskCommentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务评论实现（模块3，文档 4.2 + 5.8）。
 *
 * <h3>@提及 的一条硬规则</h3>
 * <p>被 @ 的人<b>必须是该项目的 ACTIVE 成员</b>。候选人名单从成员表取，
 * 而不是拿全站用户去匹配：否则在评论里写一个 {@code @zhangchi}
 * 就能给不相干的人推送通知——这既是干扰，也是信息泄露
 * （项目外的人由此知道这个项目里发生了什么）。</p>
 *
 * <p>解析本身交给 {@code MentionParser}（纯函数，单独测）；
 * 本类只负责「谁能被 @」这条规则。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskCommentServiceImpl implements TaskCommentService {

    private final TaskCommentMapper taskCommentMapper;
    private final TaskMapper taskMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;

    // ==================== 列表 ====================

    @Override
    public List<TaskCommentVO> list(Long taskId, Long projectId) {
        getTaskInProject(taskId, projectId);

        List<TaskComment> comments = taskCommentMapper.selectList(
                Wrappers.<TaskComment>lambdaQuery()
                        // project_id 与 task_id 同时带：越权防护的第二道条件
                        .eq(TaskComment::getProjectId, projectId)
                        .eq(TaskComment::getTaskId, taskId)
                        .orderByAsc(TaskComment::getCreateTime)
                        .orderByAsc(TaskComment::getId));
        if (comments.isEmpty()) {
            return new ArrayList<>();
        }
        return assemble(comments);
    }

    // ==================== 发表 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskCommentVO add(Long taskId, Long projectId, CommentCreateDTO dto) {
        Long userId = UserContext.requireUserId();
        Task task = getTaskInProject(taskId, projectId);

        String content = dto.getContent().trim();
        // @提及 必须在读库里成员名单的基础上判定，不能只解析文本
        Map<String, Long> candidates = mentionCandidates(projectId);
        List<Long> mentionIds = MentionParser.resolve(content, candidates);

        TaskComment comment = new TaskComment();
        comment.setTaskId(taskId);
        comment.setProjectId(projectId);
        comment.setUserId(userId);
        comment.setContent(content);
        comment.setMentionIds(MentionParser.join(mentionIds));
        taskCommentMapper.insert(comment);

        // 通知任务负责人「有新评论」；通知被 @ 到的人「有人提到你」。
        // 两类通知都由 NotificationService 统一挡掉「给自己发」的情况
        notificationService.push(NotificationType.COMMENT, projectId, task.getAssigneeId(),
                "任务有新评论：" + task.getTitle(), summary(content), taskId);
        for (Long mentionedId : mentionIds) {
            notificationService.push(NotificationType.MENTION, projectId, mentionedId,
                    "你在任务里被提及：" + task.getTitle(),
                    displayName(userId) + "：" + summary(content), taskId);
        }

        log.info("[任务] 评论已发表 taskId={} commentId={} 提及 {} 人 operator={}",
                taskId, comment.getId(), mentionIds.size(), userId);

        // 回填用户名与提及人昵称，接口直接返回可渲染的数据
        List<TaskCommentVO> vos = assemble(List.of(comment));
        return vos.get(0);
    }

    // ==================== 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long commentId, Long projectId) {
        Long userId = UserContext.requireUserId();
        if (commentId == null) {
            throw BizException.badRequest("请指定要删除的评论");
        }
        // 查询带 project_id：跨项目的评论在这里就查不到
        TaskComment comment = taskCommentMapper.selectOne(
                Wrappers.<TaskComment>lambdaQuery()
                        .eq(TaskComment::getId, commentId)
                        .eq(TaskComment::getProjectId, projectId));
        if (comment == null) {
            throw BizException.notFound("评论不存在");
        }
        if (!Objects.equals(comment.getUserId(), userId)) {
            throw BizException.forbidden("只能删除自己发表的评论");
        }
        // 逻辑删除：讨论的历史在库里仍然完整，只是不再展示
        taskCommentMapper.deleteById(commentId);
        log.info("[任务] 评论已删除 commentId={} taskId={} operator={}",
                commentId, comment.getTaskId(), userId);
    }

    // ==================== 内部工具 ====================

    /**
     * 取项目内可被 @ 的人：{@code 登录名 → userId} 与 {@code 昵称 → userId}。
     *
     * <p>只取 ACTIVE 成员：被邀请但还没加入（INVITED）、以及已被移除的人
     * 都不该出现在候选名单里——否则评论里的 @ 会指向一个看不到项目的人。</p>
     */
    private Map<String, Long> mentionCandidates(Long projectId) {
        List<ProjectMember> members = projectMemberMapper.selectList(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getStatus, ProjectMemberStatus.ACTIVE));
        if (members.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> userIds = members.stream().map(ProjectMember::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SysUser> users = sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));

        // 按成员表顺序放入，重名时先放入的生效——同一段文本每次解析结果一致，
        // 不会出现「这条评论这次 @ 到了 A，刷新后又变成 B」
        Map<Long, List<String>> namesByUserId = new LinkedHashMap<>();
        for (Long id : userIds) {
            SysUser user = users.get(id);
            if (user == null) {
                continue;
            }
            List<String> names = new ArrayList<>();
            if (StringUtils.hasText(user.getUsername())) {
                names.add(user.getUsername());
            }
            if (StringUtils.hasText(user.getNickname())) {
                names.add(user.getNickname());
            }
            namesByUserId.put(id, names);
        }
        return MentionParser.candidateMap(namesByUserId);
    }

    /** 批量装配 VO：评论人、被提及人的昵称一次取齐，不在循环里逐条查 */
    private List<TaskCommentVO> assemble(List<TaskComment> comments) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (TaskComment comment : comments) {
            if (comment.getUserId() != null) {
                userIds.add(comment.getUserId());
            }
            userIds.addAll(MentionParser.split(comment.getMentionIds()));
        }
        Map<Long, SysUser> users = userIds.isEmpty()
                ? Collections.emptyMap()
                : sysUserMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));

        Long currentUserId = UserContext.getUserId();
        List<TaskCommentVO> result = new ArrayList<>(comments.size());
        for (TaskComment comment : comments) {
            TaskCommentVO vo = new TaskCommentVO();
            vo.setId(comment.getId());
            vo.setTaskId(comment.getTaskId());
            vo.setUserId(comment.getUserId());
            SysUser author = users.get(comment.getUserId());
            vo.setUserName(displayName(author));
            vo.setUserAvatar(author == null ? null : author.getAvatar());
            vo.setContent(comment.getContent());

            List<Long> mentionIds = MentionParser.split(comment.getMentionIds());
            vo.setMentionIds(mentionIds);
            List<String> mentionNames = new ArrayList<>();
            for (Long mentionedId : mentionIds) {
                String name = displayName(users.get(mentionedId));
                mentionNames.add(name == null ? ("用户" + mentionedId) : name);
            }
            vo.setMentionNames(mentionNames);

            // 「能不能删」由服务端判定，前端不重复实现权限规则
            vo.setCanDelete(Objects.equals(comment.getUserId(), currentUserId));
            vo.setCreateTime(comment.getCreateTime());
            result.add(vo);
        }
        return result;
    }

    private String displayName(SysUser user) {
        if (user == null) {
            return null;
        }
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }

    /** 取某个用户的显示名（拼「谁提到了你」的文案用） */
    private String displayName(Long userId) {
        if (userId == null) {
            return "有人";
        }
        SysUser user = sysUserMapper.selectById(userId);
        String name = displayName(user);
        return name == null ? "有人" : name;
    }

    /** 通知正文取评论摘要：通知列表的次行位置有限，整段评论塞不下也不该塞 */
    private String summary(String content) {
        return content.length() <= 200 ? content : content.substring(0, 200) + "…";
    }

    /**
     * 取本项目内的任务，查不到直接 404。
     *
     * <p>所有评论操作的第一步：先确认「你操作的任务在你说的项目里」，
     * 再谈其余。查询带 {@code project_id}，跨项目在这里就断了。</p>
     */
    private Task getTaskInProject(Long taskId, Long projectId) {
        if (taskId == null) {
            throw BizException.badRequest("请指定任务");
        }
        Task task = taskMapper.selectOne(
                Wrappers.<Task>lambdaQuery()
                        .eq(Task::getId, taskId)
                        .eq(Task::getProjectId, projectId));
        if (task == null) {
            throw BizException.notFound("任务不存在或不属于当前项目");
        }
        return task;
    }
}
