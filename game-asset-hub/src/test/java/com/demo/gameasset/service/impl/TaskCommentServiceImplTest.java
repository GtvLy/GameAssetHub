package com.demo.gameasset.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.demo.gameasset.common.enums.NotificationType;
import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.dto.CommentCreateDTO;
import com.demo.gameasset.entity.ProjectMember;
import com.demo.gameasset.entity.SysUser;
import com.demo.gameasset.entity.Task;
import com.demo.gameasset.entity.TaskComment;
import com.demo.gameasset.mapper.ProjectMemberMapper;
import com.demo.gameasset.mapper.SysUserMapper;
import com.demo.gameasset.mapper.TaskCommentMapper;
import com.demo.gameasset.mapper.TaskMapper;
import com.demo.gameasset.service.NotificationService;
import com.demo.gameasset.support.MybatisPlusTestSupport;
import com.demo.gameasset.vo.TaskCommentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务评论与 @提及测试（模块3，文档 4.2 + 5.8）。
 *
 * <p>最要紧的一条：<b>被 @ 的人必须是该项目的 ACTIVE 成员</b>。
 * 候选人名单来自成员表，而不是拿全站用户去匹配——否则在评论里写一个
 * 项目外的人名就能给他推通知，既是干扰也是信息泄露。</p>
 *
 * <p>纯 Mockito 测试：不加载 Spring、不连 MySQL。</p>
 *
 * @author 管理员
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TaskCommentServiceImpl 任务评论与 @提及")
class TaskCommentServiceImplTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long TASK_ID = 504L;
    private static final Long ME = 1L;
    private static final Long ARTIST = 2L;
    private static final Long VIEWER = 4L;

    @Mock
    private TaskCommentMapper taskCommentMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private NotificationService notificationService;

    private TaskCommentServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleThreeEntities();
        service = new TaskCommentServiceImpl(taskCommentMapper, taskMapper, projectMemberMapper,
                sysUserMapper, notificationService);
        UserContext.setUserId(ME);
        UserContext.setUsername("demo");

        Task task = new Task();
        task.setId(TASK_ID);
        task.setProjectId(PROJECT_ID);
        task.setTitle("空间站地板模块接缝对齐");
        task.setAssigneeId(ARTIST);
        task.setCreatorId(ME);
        when(taskMapper.selectOne(any())).thenReturn(task);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private ProjectMember member(long userId) {
        ProjectMember m = new ProjectMember();
        m.setProjectId(PROJECT_ID);
        m.setUserId(userId);
        m.setRole("ARTIST");
        m.setStatus("ACTIVE");
        return m;
    }

    private SysUser user(long id, String username, String nickname) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        u.setNickname(nickname);
        return u;
    }

    private void membersAre(long... userIds) {
        List<ProjectMember> members = new java.util.ArrayList<>();
        for (long id : userIds) {
            members.add(member(id));
        }
        when(projectMemberMapper.selectList(any())).thenReturn(members);
    }

    private CommentCreateDTO comment(String content) {
        CommentCreateDTO dto = new CommentCreateDTO();
        dto.setContent(content);
        return dto;
    }

    // ==================== 发表评论与 @提及 ====================

    @Test
    @DisplayName("评论落库：写入项目ID、评论人、原文，并把被 @ 的人解析成 ID 存进 mention_ids")
    void storesCommentAndResolvedMentions() {
        membersAre(ME, ARTIST);
        when(sysUserMapper.selectBatchIds(any())).thenReturn(List.of(
                user(ME, "demo", "管理员"), user(ARTIST, "liyang", "李阳")));

        service.add(TASK_ID, PROJECT_ID, comment("@李阳 接缝这里和墙面模块对不齐，麻烦重新对齐"));

        ArgumentCaptor<TaskComment> captor = ArgumentCaptor.forClass(TaskComment.class);
        verify(taskCommentMapper).insert(captor.capture());
        TaskComment saved = captor.getValue();
        assertThat(saved.getTaskId()).isEqualTo(TASK_ID);
        assertThat(saved.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(saved.getUserId()).isEqualTo(ME);
        assertThat(saved.getMentionIds()).isEqualTo("2");
    }

    @Test
    @DisplayName("通知两类人：任务负责人收到 COMMENT，被 @ 的人收到 MENTION")
    void notifiesAssigneeAndMentioned() {
        membersAre(ME, ARTIST);
        when(sysUserMapper.selectBatchIds(any())).thenReturn(List.of(
                user(ME, "demo", "管理员"), user(ARTIST, "liyang", "李阳")));

        service.add(TASK_ID, PROJECT_ID, comment("@李阳 看一下接缝"));

        verify(notificationService).push(eq(NotificationType.COMMENT), eq(PROJECT_ID), eq(ARTIST),
                any(), any(), eq(TASK_ID));
        verify(notificationService).push(eq(NotificationType.MENTION), eq(PROJECT_ID), eq(ARTIST),
                any(), any(), eq(TASK_ID));
    }

    @Test
    @DisplayName("@ 项目外的人不产生任何提及：候选人只取该项目 ACTIVE 成员")
    void cannotMentionOutsiders() {
        membersAre(ME, ARTIST);
        when(sysUserMapper.selectBatchIds(any())).thenReturn(List.of(
                user(ME, "demo", "管理员"), user(ARTIST, "liyang", "李阳")));

        service.add(TASK_ID, PROJECT_ID, comment("@wangong 你也看下"));

        ArgumentCaptor<TaskComment> captor = ArgumentCaptor.forClass(TaskComment.class);
        verify(taskCommentMapper).insert(captor.capture());
        assertThat(captor.getValue().getMentionIds())
                .as("王工是受邀未加入（INVITED）的人，不能被他 @ 到")
                .isNull();
        verify(notificationService, never()).push(eq(NotificationType.MENTION), any(), any(),
                any(), any(), any());
    }

    @Test
    @DisplayName("候选人名单只查该项目的 ACTIVE 成员（SQL 带 project_id 与 status）")
    void mentionCandidatesAreScopedToProject() {
        membersAre(ME);
        when(sysUserMapper.selectBatchIds(any())).thenReturn(List.of());

        service.add(TASK_ID, PROJECT_ID, comment("没有提及的普通评论"));

        ArgumentCaptor<Wrapper<ProjectMember>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(projectMemberMapper).selectList(captor.capture());
        assertThat(captor.getValue().getTargetSql())
                .contains("project_id").contains("status");
    }

    @Test
    @DisplayName("任务不在本项目 → 404，且不落任何评论")
    void rejectsTaskFromAnotherProject() {
        when(taskMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.add(TASK_ID, 99L, comment("跨项目评论")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(404));
        verify(taskCommentMapper, never()).insert(any(TaskComment.class));
    }

    @Test
    @DisplayName("评论正文两侧空白被去掉，@提及 判定基于去掉空白后的内容")
    void trimsContent() {
        membersAre(ME, ARTIST);
        when(sysUserMapper.selectBatchIds(any())).thenReturn(List.of(
                user(ME, "demo", "管理员"), user(ARTIST, "liyang", "李阳")));

        service.add(TASK_ID, PROJECT_ID, comment("  @liyang 看下  "));

        ArgumentCaptor<TaskComment> captor = ArgumentCaptor.forClass(TaskComment.class);
        verify(taskCommentMapper).insert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("@liyang 看下");
        assertThat(captor.getValue().getMentionIds()).isEqualTo("2");
    }

    // ==================== 列表 ====================

    @Test
    @DisplayName("列表：按时间正序，并给出「能否删除」（自己发的才能删）")
    void listMarksOwnCommentsDeletable() {
        TaskComment mine = new TaskComment();
        mine.setId(1001L);
        mine.setTaskId(TASK_ID);
        mine.setProjectId(PROJECT_ID);
        mine.setUserId(ME);
        mine.setContent("我发的");
        TaskComment others = new TaskComment();
        others.setId(1002L);
        others.setTaskId(TASK_ID);
        others.setProjectId(PROJECT_ID);
        others.setUserId(ARTIST);
        others.setContent("别人发的");
        when(taskCommentMapper.selectList(any())).thenReturn(List.of(mine, others));
        when(sysUserMapper.selectBatchIds(any())).thenReturn(List.of(
                user(ME, "demo", "管理员"), user(ARTIST, "liyang", "李阳")));

        List<TaskCommentVO> vos = service.list(TASK_ID, PROJECT_ID);

        assertThat(vos).hasSize(2);
        assertThat(vos.get(0).getCanDelete()).isTrue();
        assertThat(vos.get(0).getUserName()).isEqualTo("管理员");
        assertThat(vos.get(1).getCanDelete()).as("别人的评论不能删").isFalse();
        assertThat(vos.get(1).getUserName()).isEqualTo("李阳");

        ArgumentCaptor<Wrapper<TaskComment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskCommentMapper).selectList(captor.capture());
        assertThat(captor.getValue().getTargetSql())
                .as("评论查询必须带 project_id")
                .contains("project_id").contains("task_id");
    }

    @Test
    @DisplayName("列表：被 @ 的人显示名一并返回，前端直接高亮")
    void listResolvesMentionNames() {
        TaskComment c = new TaskComment();
        c.setId(1001L);
        c.setTaskId(TASK_ID);
        c.setProjectId(PROJECT_ID);
        c.setUserId(ME);
        c.setContent("@李阳 看下");
        c.setMentionIds("2,4");
        when(taskCommentMapper.selectList(any())).thenReturn(List.of(c));
        when(sysUserMapper.selectBatchIds(any())).thenReturn(List.of(
                user(ME, "demo", "管理员"), user(ARTIST, "liyang", "李阳"),
                user(VIEWER, "chenjing", "陈静")));

        TaskCommentVO vo = service.list(TASK_ID, PROJECT_ID).get(0);

        assertThat(vo.getMentionIds()).containsExactly(2L, 4L);
        assertThat(vo.getMentionNames()).containsExactly("李阳", "陈静");
    }

    // ==================== 删除 ====================

    @Test
    @DisplayName("删除自己的评论：逻辑删除（deleteById），历史仍留在库里")
    void deletesOwnComment() {
        TaskComment mine = new TaskComment();
        mine.setId(1001L);
        mine.setProjectId(PROJECT_ID);
        mine.setUserId(ME);
        when(taskCommentMapper.selectOne(any())).thenReturn(mine);

        service.delete(1001L, PROJECT_ID);

        verify(taskCommentMapper).deleteById(1001L);
    }

    @Test
    @DisplayName("删除别人的评论 → 403，且不发生删除（静默成功会让用户以为删掉了）")
    void refusesToDeleteOthersComment() {
        TaskComment others = new TaskComment();
        others.setId(1002L);
        others.setProjectId(PROJECT_ID);
        others.setUserId(ARTIST);
        when(taskCommentMapper.selectOne(any())).thenReturn(others);

        assertThatThrownBy(() -> service.delete(1002L, PROJECT_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
        verify(taskCommentMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("评论不存在（或属于别的项目）→ 404")
    void missingCommentIsNotFound() {
        when(taskCommentMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.delete(1002L, PROJECT_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(404));

        ArgumentCaptor<Wrapper<TaskComment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskCommentMapper).selectOne(captor.capture());
        assertThat(captor.getValue().getTargetSql()).contains("project_id");
    }
}
