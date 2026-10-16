package com.zhangchi.gameasset.service.support;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zhangchi.gameasset.common.enums.AssetStatus;
import com.zhangchi.gameasset.common.enums.NotificationType;
import com.zhangchi.gameasset.entity.Asset;
import com.zhangchi.gameasset.entity.ProjectMember;
import com.zhangchi.gameasset.entity.Task;
import com.zhangchi.gameasset.mapper.ProjectMemberMapper;
import com.zhangchi.gameasset.mapper.TaskMapper;
import com.zhangchi.gameasset.service.NotificationService;
import com.zhangchi.gameasset.support.MybatisPlusTestSupport;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资产状态流转的通知编排测试（模块3 为模块2 补的联动，文档 5.3 / 5.5 / 5.8）。
 *
 * <p>覆盖文档 5.8 事件表里属于资产侧的三行，以及「哪些流转不产生通知」。</p>
 *
 * <p>纯 Mockito 测试：不加载 Spring、不连 MySQL。</p>
 *
 * @author 张忠驰
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AssetReviewNotifier 资产状态联动的通知")
class AssetReviewNotifierTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long ASSET_ID = 128L;
    private static final Long OWNER_ID = 1L;
    private static final Long ARTIST_ID = 2L;

    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private NotificationService notificationService;

    private AssetReviewNotifier notifier;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleThreeEntities();
        notifier = new AssetReviewNotifier(projectMemberMapper, taskMapper, notificationService);
    }

    private Asset asset(String status, Long ownerId) {
        Asset a = new Asset();
        a.setId(ASSET_ID);
        a.setProjectId(PROJECT_ID);
        a.setName("木箱 A 型");
        a.setStatus(status);
        a.setOwnerId(ownerId);
        return a;
    }

    private ProjectMember owner(long userId) {
        ProjectMember m = new ProjectMember();
        m.setProjectId(PROJECT_ID);
        m.setUserId(userId);
        m.setRole("OWNER");
        m.setStatus("ACTIVE");
        return m;
    }

    private Task task(long id, Long assigneeId) {
        Task t = new Task();
        t.setId(id);
        t.setProjectId(PROJECT_ID);
        t.setTitle("气闸舱门接入交互提示");
        t.setAssigneeId(assigneeId);
        t.setColumnStatus("TODO");
        return t;
    }

    // ==================== 提交审核（PENDING） ====================

    @Test
    @DisplayName("提交审核：通知项目全部 ACTIVE 的 OWNER，bizType 指向资产")
    void submittingNotifiesProjectOwners() {
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(owner(1L), owner(9L)));
        when(taskMapper.selectList(any())).thenReturn(List.of());

        notifier.afterStatusChange(asset(AssetStatus.PENDING.name(), ARTIST_ID), "请安排审核");

        verify(notificationService).push(NotificationType.ASSET_REVIEW, PROJECT_ID, 1L,
                "资产提交审核：木箱 A 型", "请安排审核",
                NotificationType.NotificationBizType.ASSET, ASSET_ID);
        verify(notificationService).push(NotificationType.ASSET_REVIEW, PROJECT_ID, 9L,
                "资产提交审核：木箱 A 型", "请安排审核",
                NotificationType.NotificationBizType.ASSET, ASSET_ID);

        ArgumentCaptor<Wrapper<ProjectMember>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(projectMemberMapper).selectList(captor.capture());
        assertThat(captor.getValue().getTargetSql())
                .as("只通知该项目的 ACTIVE OWNER")
                .contains("project_id").contains("role").contains("status");
    }

    @Test
    @DisplayName("提交审核：给该资产下所有「未完成」任务的负责人推一条，提示可进入验收")
    void submittingNotifiesRelatedTaskAssignees() {
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(owner(OWNER_ID)));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(504L, ARTIST_ID)));

        notifier.afterStatusChange(asset(AssetStatus.PENDING.name(), OWNER_ID), null);

        verify(notificationService).push(NotificationType.ASSET_REVIEW, PROJECT_ID, ARTIST_ID,
                "关联资产已提交审核：木箱 A 型",
                "可在任务「气闸舱门接入交互提示」中进行验收",
                NotificationType.NotificationBizType.TASK, 504L);

        ArgumentCaptor<Wrapper<Task>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).selectList(captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertThat(sql)
                .as("只找这个资产、这个项目、且未完成的任务")
                .contains("project_id").contains("asset_id").contains("column_status");
    }

    @Test
    @DisplayName("提交审核：没有负责人、也没有关联任务时不推任何通知（不发空通知）")
    void submittingWithoutTargetsPushesNothing() {
        when(projectMemberMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectList(any())).thenReturn(List.of());

        notifier.afterStatusChange(asset(AssetStatus.PENDING.name(), OWNER_ID), null);

        verify(notificationService, never()).push(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("提交审核：任务负责人为空（没人认领）时也走 push，由通知服务挡掉空接收人")
    void submittingWithUnassignedTaskHandsNullReceiverToService() {
        when(projectMemberMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectList(any())).thenReturn(List.of(task(505L, null)));

        notifier.afterStatusChange(asset(AssetStatus.PENDING.name(), OWNER_ID), null);

        verify(notificationService).push(eq(NotificationType.ASSET_REVIEW), eq(PROJECT_ID), isNull(),
                any(), any(), eq(NotificationType.NotificationBizType.TASK), eq(505L));
    }

    // ==================== 通过 / 打回 ====================

    @Test
    @DisplayName("审核通过：只通知资产负责人，标题说明结果，正文是审核意见")
    void approvingNotifiesAssetOwner() {
        notifier.afterStatusChange(asset(AssetStatus.APPROVED.name(), ARTIST_ID),
                "模型结构合理，UV 需在下一版本优化");

        verify(notificationService).push(NotificationType.ASSET_REVIEW, PROJECT_ID, ARTIST_ID,
                "资产审核通过：木箱 A 型", "模型结构合理，UV 需在下一版本优化",
                NotificationType.NotificationBizType.ASSET, ASSET_ID);
    }

    @Test
    @DisplayName("打回：标题写明「被打回」，正文带上必须填写的审核意见")
    void rejectingNotifiesAssetOwner() {
        notifier.afterStatusChange(asset(AssetStatus.REJECTED.name(), ARTIST_ID),
                "尾段 1.2s 处有明显爆音，请重新混音后再提交");

        verify(notificationService).push(NotificationType.ASSET_REVIEW, PROJECT_ID, ARTIST_ID,
                "资产被打回：木箱 A 型", "尾段 1.2s 处有明显爆音，请重新混音后再提交",
                NotificationType.NotificationBizType.ASSET, ASSET_ID);
    }

    @Test
    @DisplayName("通过 / 打回不会去打扰项目负责人：那是审核人自己，只有资产负责人需要知道结果")
    void reviewingDoesNotQueryProjectMembersOrTasks() {
        notifier.afterStatusChange(asset(AssetStatus.APPROVED.name(), ARTIST_ID), "OK");

        verify(projectMemberMapper, never()).selectList(any());
        verify(taskMapper, never()).selectList(any());
    }

    // ==================== 不产生通知的流转 ====================

    @Test
    @DisplayName("退回制作中 / 废弃不推通知：它们不要求任何人立刻做决定，发了只会稀释真正的待办")
    void otherTransitionsPushNothing() {
        notifier.afterStatusChange(asset(AssetStatus.IN_PROGRESS.name(), ARTIST_ID), null);
        notifier.afterStatusChange(asset(AssetStatus.DEPRECATED.name(), ARTIST_ID), null);

        verify(notificationService, never()).push(any(), anyLong(), anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("资产为空时直接返回，不抛异常（防御性：调用方不该传空，但也不该炸在这里）")
    void toleratesNullAsset() {
        notifier.afterStatusChange(null, null);

        verify(notificationService, never()).push(any(), any(), any(), any(), any(), any(), any());
    }
}
