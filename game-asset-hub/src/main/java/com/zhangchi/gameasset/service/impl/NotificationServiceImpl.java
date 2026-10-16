package com.zhangchi.gameasset.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhangchi.gameasset.common.constant.RedisKeys;
import com.zhangchi.gameasset.common.enums.NotificationType;
import com.zhangchi.gameasset.common.exception.BizException;
import com.zhangchi.gameasset.context.UserContext;
import com.zhangchi.gameasset.dto.NotificationQueryDTO;
import com.zhangchi.gameasset.entity.Notification;
import com.zhangchi.gameasset.entity.SysUser;
import com.zhangchi.gameasset.mapper.NotificationMapper;
import com.zhangchi.gameasset.mapper.SysUserMapper;
import com.zhangchi.gameasset.service.NotificationService;
import com.zhangchi.gameasset.service.RedisCacheService;
import com.zhangchi.gameasset.vo.NotificationVO;
import com.zhangchi.gameasset.vo.PageResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 站内通知实现（模块3，文档 5.8）。
 *
 * <h3>两条必须守住的规则</h3>
 * <ol>
 *   <li><b>不给自己发通知</b>——文档 5.8 的示例代码第一行就是这个判断。
 *       少了它，「我改了状态 → 我收到通知 → 我点开 → 我又收到」会变成噪音源。</li>
 *   <li><b>写一条就失效一次未读数缓存</b>——铃铛是每 30 秒轮询的，
 *       缓存不失效的表现是「角标最多慢 30 秒」，用户会觉得系统反应迟钝；
 *       而如果 TTL 设得长，甚至会出现「明明有未读却一直是 0」。</li>
 * </ol>
 *
 * <h3>未读数为什么按「项目 + 用户」缓存</h3>
 * <p>通知列表本身按当前项目上下文过滤（前端铃铛在项目内），
 * 未读数若跨项目统计，就会出现「角标显示 3、点开只有 1 条」的矛盾——
 * 这类不一致比慢几百毫秒更让人不信任。因此 key 里带上 projectId，
 * 与列表口径严格对齐。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /** 未读数缓存 TTL。短一点没关系：写操作会主动失效，TTL 只是兜底 */
    private static final Duration UNREAD_TTL = Duration.ofMinutes(5);

    private final NotificationMapper notificationMapper;
    private final SysUserMapper sysUserMapper;
    private final RedisCacheService redisCacheService;

    // ==================== 推送 ====================

    @Override
    public void push(NotificationType type, Long projectId, Long receiverId,
                     String title, String content, String bizType, Long bizId) {
        if (type == null || receiverId == null) {
            // 没有接收人不是错误：任务没指派给谁时本来就没有通知对象
            return;
        }
        Long senderId = UserContext.getUserId();
        if (receiverId.equals(senderId)) {
            // 文档 5.8：不给自己发通知
            return;
        }

        Notification notification = new Notification();
        notification.setProjectId(projectId);
        notification.setUserId(receiverId);
        notification.setType(type.name());
        notification.setTitle(title);
        notification.setContent(content);
        notification.setBizType(StringUtils.hasText(bizType) ? bizType : type.getBizType());
        notification.setBizId(bizId);
        notification.setSenderId(senderId);
        notification.setIsRead(0);
        notificationMapper.insert(notification);

        evictUnreadCount(projectId, receiverId);

        log.debug("[通知] 已推送 type={} receiver={} projectId={} biz={}#{}",
                type, receiverId, projectId, notification.getBizType(), bizId);
    }

    @Override
    public void push(NotificationType type, Long projectId, Long receiverId,
                     String title, String content, Long bizId) {
        push(type, projectId, receiverId, title, content, null, bizId);
    }

    // ==================== 查询与已读 ====================

    @Override
    public PageResultVO<NotificationVO> page(Long projectId, Long userId, NotificationQueryDTO query) {
        NotificationQueryDTO criteria = query == null ? new NotificationQueryDTO() : query;
        int pageNo = criteria.normalizedPage();
        int pageSize = criteria.normalizedSize();

        IPage<Notification> result = notificationMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.<Notification>lambdaQuery()
                        // user_id 是第一条件：通知是「我的」，越权在这里就断掉
                        .eq(Notification::getUserId, userId)
                        // 项目上下文：与未读数缓存同一个口径，避免「角标 3 条、列表 1 条」
                        .eq(projectId != null, Notification::getProjectId, projectId)
                        .eq(criteria.onlyUnread(), Notification::getIsRead, 0)
                        .orderByDesc(Notification::getCreateTime)
                        .orderByDesc(Notification::getId));

        return PageResultVO.of(toVOs(result.getRecords()), result.getTotal(), pageNo, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotificationVO markRead(Long projectId, Long userId, Long notificationId) {
        Notification notification = findMine(projectId, userId, notificationId);
        if (Integer.valueOf(1).equals(notification.getIsRead())) {
            // 幂等：重复标记已读不该报错，前端可能因为重试而重复调用
            return toVO(notification, null);
        }
        notification.setIsRead(1);
        notificationMapper.updateById(notification);
        evictUnreadCount(projectId, userId);
        return toVO(notification, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead(Long projectId, Long userId) {
        // 用 update 条件更新而不是「先查再逐条 updateById」：
        // 后者在未读上百条时会发出上百条 UPDATE，而且中途失败会留下一半已读一半未读
        int affected = notificationMapper.update(null,
                Wrappers.<Notification>lambdaUpdate()
                        .eq(Notification::getUserId, userId)
                        .eq(projectId != null, Notification::getProjectId, projectId)
                        .eq(Notification::getIsRead, 0)
                        .set(Notification::getIsRead, 1));
        evictUnreadCount(projectId, userId);
        log.debug("[通知] 全部已读 userId={} projectId={} 影响 {} 条", userId, projectId, affected);
        return affected;
    }

    @Override
    public long unreadCount(Long projectId, Long userId) {
        if (userId == null) {
            return 0L;
        }
        String key = RedisKeys.notifyUnread(userId, projectId);
        Long cached = redisCacheService.get(key, Long.class);
        if (cached != null) {
            return cached;
        }
        long count = countUnread(projectId, userId);
        redisCacheService.set(key, count, UNREAD_TTL);
        return count;
    }

    @Override
    public void evictUnreadCount(Long projectId, Long userId) {
        if (userId == null) {
            return;
        }
        redisCacheService.delete(RedisKeys.notifyUnread(userId, projectId));
    }

    // ==================== 内部工具 ====================

    /** 直查未读数（绕过缓存），缓存未命中与失效后回填都用它，保证口径只有一处 */
    private long countUnread(Long projectId, Long userId) {
        Long count = notificationMapper.selectCount(
                Wrappers.<Notification>lambdaQuery()
                        .eq(Notification::getUserId, userId)
                        .eq(projectId != null, Notification::getProjectId, projectId)
                        .eq(Notification::getIsRead, 0));
        return count == null ? 0L : count;
    }

    /**
     * 取「我的一条通知」。
     *
     * <p>查询带 {@code user_id}：别人的通知在<b>数据库层</b>就查不到，
     * 而不是查出来再在 Java 里比一次。少了这个条件，
     * 任何一处漏判都是「能看到别人收到过什么通知」的信息泄露。</p>
     *
     * <p>不存在时返回 404 而不是 403：403 等于告诉调用方「这条通知存在，
     * 只是不是你的」，可以被用来枚举。</p>
     */
    private Notification findMine(Long projectId, Long userId, Long notificationId) {
        if (notificationId == null) {
            throw BizException.badRequest("请指定要操作的通知");
        }
        Notification notification = notificationMapper.selectOne(
                Wrappers.<Notification>lambdaQuery()
                        .eq(Notification::getId, notificationId)
                        .eq(Notification::getUserId, userId)
                        .eq(projectId != null, Notification::getProjectId, projectId));
        if (notification == null) {
            throw BizException.notFound("通知不存在");
        }
        return notification;
    }

    private List<NotificationVO> toVOs(List<Notification> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, SysUser> senders = batchUsers(notifications.stream()
                .map(Notification::getSenderId).collect(Collectors.toSet()));
        List<NotificationVO> result = new ArrayList<>(notifications.size());
        for (Notification notification : notifications) {
            result.add(toVO(notification, senders));
        }
        return result;
    }

    private NotificationVO toVO(Notification notification, Map<Long, SysUser> senders) {
        NotificationVO vo = new NotificationVO();
        vo.setId(notification.getId());
        vo.setProjectId(notification.getProjectId());
        vo.setType(notification.getType());
        vo.setTypeLabel(NotificationType.label(notification.getType()));
        vo.setTitle(notification.getTitle());
        vo.setContent(notification.getContent());
        vo.setBizType(notification.getBizType());
        vo.setBizId(notification.getBizId());
        vo.setSenderId(notification.getSenderId());
        vo.setIsRead(notification.getIsRead());
        vo.setCreateTime(notification.getCreateTime());
        if (senders != null) {
            vo.setSenderName(displayName(senders.get(notification.getSenderId())));
        } else if (notification.getSenderId() != null) {
            vo.setSenderName(displayName(sysUserMapper.selectById(notification.getSenderId())));
        }
        return vo;
    }

    private Map<Long, SysUser> batchUsers(Set<Long> ids) {
        Set<Long> valid = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (valid.isEmpty()) {
            return Collections.emptyMap();
        }
        return sysUserMapper.selectBatchIds(valid).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));
    }

    /** 昵称优先，为空回落到登录名；系统触发的通知（sender 为空）返回 null */
    private String displayName(SysUser user) {
        if (user == null) {
            return null;
        }
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }
}
