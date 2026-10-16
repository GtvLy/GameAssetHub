package com.zhangchi.gameasset.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhangchi.gameasset.common.enums.NotificationType;
import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.context.UserContext;
import com.zhangchi.gameasset.dto.NotificationQueryDTO;
import com.zhangchi.gameasset.entity.Notification;
import com.zhangchi.gameasset.mapper.NotificationMapper;
import com.zhangchi.gameasset.mapper.SysUserMapper;
import com.zhangchi.gameasset.service.RedisCacheService;
import com.zhangchi.gameasset.support.MybatisPlusTestSupport;
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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知服务测试（模块3，文档 5.8）。
 *
 * <p>盯住三条规则：</p>
 * <ol>
 *   <li><b>不给自己发通知</b>——文档 5.8 示例代码的第一行；</li>
 *   <li><b>写一条就失效一次未读数缓存</b>——铃铛每 30 秒轮询，缓存不失效就一直是旧数字；</li>
 *   <li><b>未读数与列表用同一个维度</b>（user_id + project_id），
 *       否则会出现「角标显示 3、点开只有 1 条」。</li>
 * </ol>
 *
 * <p>纯 Mockito 测试：不加载 Spring、不连 MySQL / Redis。</p>
 *
 * @author 张忠驰
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationServiceImpl 站内通知")
class NotificationServiceImplTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long ME = 1L;
    private static final Long OTHER = 2L;
    private static final String UNREAD_KEY = "gah:notify:unread:2:7";

    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private RedisCacheService redisCacheService;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleThreeEntities();
        service = new NotificationServiceImpl(notificationMapper, sysUserMapper, redisCacheService);
        UserContext.setUserId(ME);
        UserContext.setUsername("zhangchi");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Notification notification(long id, long userId, String type, int isRead) {
        Notification n = new Notification();
        n.setId(id);
        n.setProjectId(PROJECT_ID);
        n.setUserId(userId);
        n.setType(type);
        n.setTitle("标题");
        n.setBizType(NotificationType.of(type).getBizType());
        n.setIsRead(isRead);
        return n;
    }

    // ==================== 推送 ====================

    @Test
    @DisplayName("不给自己发通知：接收人就是当前用户时静默跳过，一条也不落库")
    void doesNotNotifyYourself() {
        service.push(NotificationType.TASK_ASSIGNED, PROJECT_ID, ME,
                "有新任务指派给你：自己指派给自己", null, 305L);

        verify(notificationMapper, never()).insert(any(Notification.class));
        verify(redisCacheService, never()).delete(anyString());
    }

    @Test
    @DisplayName("推送给别人：落一条未读通知，并失效对方的未读数缓存")
    void pushesAndEvictsUnreadCache() {
        service.push(NotificationType.TASK_ASSIGNED, PROJECT_ID, OTHER,
                "有新任务指派给你：气闸舱门接入交互提示", "优先级 P1", 501L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(OTHER);
        assertThat(saved.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(saved.getType()).isEqualTo("TASK_ASSIGNED");
        assertThat(saved.getSenderId()).isEqualTo(ME);
        assertThat(saved.getIsRead()).isZero();
        assertThat(saved.getBizId()).isEqualTo(501L);
        assertThat(saved.getBizType()).as("bizType 为空时取通知类型的默认值").isEqualTo("TASK");

        verify(redisCacheService).delete(UNREAD_KEY);
    }

    @Test
    @DisplayName("显式传入 bizType 时以传入值为准（资产通知要跳到资产详情）")
    void honoursExplicitBizType() {
        service.push(NotificationType.ASSET_REVIEW, PROJECT_ID, OTHER, "资产审核通过：木箱 A 型",
                null, NotificationType.NotificationBizType.ASSET, 1L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getBizType()).isEqualTo("ASSET");
    }

    @Test
    @DisplayName("接收人为空时直接返回：没有通知对象不是错误（任务没指派给谁）")
    void skipsWhenReceiverIsNull() {
        service.push(NotificationType.TASK_ASSIGNED, PROJECT_ID, null, "没有接收人", null, 305L);

        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    @Test
    @DisplayName("定时任务触发（无登录用户）时 senderId 为空，通知仍然正常落库")
    void supportsSystemNotifications() {
        UserContext.clear();

        service.push(NotificationType.TASK_DUE_SOON, PROJECT_ID, OTHER,
                "任务即将到期：存档系统联调", "截止日期为明天", 505L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getSenderId()).isNull();
        // senderId 为空时「不给自己发通知」这条不会误伤系统通知
        assertThat(captor.getValue().getUserId()).isEqualTo(OTHER);
    }

    // ==================== 未读数 ====================

    @Test
    @DisplayName("未读数：缓存命中时直接返回，不再查库")
    void unreadCountUsesCache() {
        when(redisCacheService.get(UNREAD_KEY, Long.class)).thenReturn(3L);

        assertThat(service.unreadCount(PROJECT_ID, OTHER)).isEqualTo(3L);
        verify(notificationMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("未读数：缓存未命中时查库并回填，key 与列表口径一致（用户 + 项目）")
    void unreadCountFillsCacheOnMiss() {
        when(redisCacheService.get(UNREAD_KEY, Long.class)).thenReturn(null);
        when(notificationMapper.selectCount(any())).thenReturn(3L);

        assertThat(service.unreadCount(PROJECT_ID, OTHER)).isEqualTo(3L);

        ArgumentCaptor<Wrapper<Notification>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(notificationMapper).selectCount(captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertThat(sql).contains("user_id").contains("project_id").contains("is_read");

        verify(redisCacheService).set(eq(UNREAD_KEY), eq(3L), any(Duration.class));
    }

    @Test
    @DisplayName("未读数：没有登录用户时返回 0，不产生查询")
    void unreadCountWithoutUserIsZero() {
        assertThat(service.unreadCount(PROJECT_ID, null)).isZero();
        verify(notificationMapper, never()).selectCount(any());
    }

    // ==================== 已读 ====================

    @Test
    @DisplayName("标记已读：只查自己的通知（查询条件带 user_id），别人的查不到")
    void markReadScopesToCurrentUser() {
        when(notificationMapper.selectOne(any())).thenReturn(notification(9001L, OTHER, "COMMENT", 0));
        when(sysUserMapper.selectById(ME)).thenReturn(null);

        service.markRead(PROJECT_ID, OTHER, 9001L);

        ArgumentCaptor<Wrapper<Notification>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(notificationMapper).selectOne(captor.capture());
        assertThat(captor.getValue().getTargetSql()).contains("user_id").contains("project_id");
        verify(notificationMapper).updateById(any(Notification.class));
        verify(redisCacheService).delete(UNREAD_KEY);
    }

    @Test
    @DisplayName("标记已读：别人的通知查不到 → 404（用 404 而不是 403，避免被用来枚举）")
    void markReadOfOthersNotificationIsNotFound() {
        when(notificationMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.markRead(PROJECT_ID, OTHER, 9001L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(404));
        verify(notificationMapper, never()).updateById(any(Notification.class));
    }

    @Test
    @DisplayName("标记已读幂等：已读的通知重复标记不再写库，也不重复失效缓存")
    void markReadIsIdempotent() {
        when(notificationMapper.selectOne(any())).thenReturn(notification(9001L, ME, "COMMENT", 1));

        service.markRead(PROJECT_ID, ME, 9001L);

        verify(notificationMapper, never()).updateById(any(Notification.class));
    }

    @Test
    @DisplayName("全部已读：一条条件更新搞定，并失效未读数缓存")
    void markAllReadUsesConditionalUpdate() {
        when(notificationMapper.update(any(), any())).thenReturn(3);

        int affected = service.markAllRead(PROJECT_ID, ME);

        assertThat(affected).isEqualTo(3);
        ArgumentCaptor<Wrapper<Notification>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(notificationMapper).update(any(), captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertThat(sql).contains("is_read").contains("user_id");
        verify(redisCacheService).delete("gah:notify:unread:1:7");
    }

    // ==================== 列表 ====================

    @Test
    @DisplayName("列表：查询条件带 user_id + project_id，可筛未读；未读筛选是可选条件")
    void pageScopesToUserAndProject() {
        when(notificationMapper.selectPage(any(), any())).thenReturn(new Page<>());

        NotificationQueryDTO query = new NotificationQueryDTO();
        query.setUnreadOnly(true);
        service.page(PROJECT_ID, ME, query);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Notification>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(notificationMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertThat(sql).contains("user_id").contains("project_id").contains("is_read");
    }

    @Test
    @DisplayName("列表：不筛未读时查询条件里不出现 is_read（能看到已读历史）")
    void pageWithoutUnreadFilterDoesNotConstrainIsRead() {
        when(notificationMapper.selectPage(any(), any())).thenReturn(new Page<>());

        service.page(PROJECT_ID, ME, new NotificationQueryDTO());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Notification>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(notificationMapper).selectPage(any(), captor.capture());
        assertThat(captor.getValue().getTargetSql()).doesNotContain("is_read");
    }
}
