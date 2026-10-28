package com.demo.gameasset.service;

import com.demo.gameasset.common.enums.NotificationType;
import com.demo.gameasset.dto.NotificationQueryDTO;
import com.demo.gameasset.vo.NotificationVO;
import com.demo.gameasset.vo.PageResultVO;

/**
 * 站内通知（模块3，文档 5.8）。
 *
 * <p>{@link #push} 是模块2 与模块3 共用的出口：资产审核通过/打回要通知资产负责人，
 * 任务被指派要通知被指派人。把它做成一个 Service 而不是让各处自己 insert，
 * 是为了让「不给自己发通知」「未读数缓存失效」这两条规则只有一处实现——
 * 少一处失效的表现是「角标不动」，少一处「不给自己发」的表现是
 * 「自己给自己发了一堆通知」，两者都很难在演示时被发现。</p>
 *
 * @author 管理员
 */
public interface NotificationService {

    /**
     * 推送一条通知。
     *
     * <p>接收人为空、或接收人就是当前登录用户时<b>静默跳过</b>：
     * 前者表示「这次操作没有通知对象」（例如任务没指派给任何人），
     * 后者是文档 5.8 明确要求的「不给自己发通知」。</p>
     *
     * @param type       通知类型，决定 {@code biz_type} 默认值
     * @param projectId  所属项目，可为空
     * @param receiverId 接收人
     * @param title      标题
     * @param content    正文，可为空
     * @param bizType    业务类型 TASK / ASSET / PROJECT；为空时取 {@code type} 的默认值
     * @param bizId      业务ID，前端跳转用
     */
    void push(NotificationType type, Long projectId, Long receiverId,
              String title, String content, String bizType, Long bizId);

    /** 推送通知（{@code bizType} 取通知类型的默认值） */
    void push(NotificationType type, Long projectId, Long receiverId,
              String title, String content, Long bizId);

    /** 我的通知（分页，可筛未读）。只返回当前项目下的通知 */
    PageResultVO<NotificationVO> page(Long projectId, Long userId, NotificationQueryDTO query);

    /** 标记单条已读（只能标记自己的，别人的返回 404 而不是 403，避免探测） */
    NotificationVO markRead(Long projectId, Long userId, Long notificationId);

    /** 全部标记已读，返回实际影响的条数 */
    int markAllRead(Long projectId, Long userId);

    /** 未读数（铃铛角标轮询），走 Redis 缓存 */
    long unreadCount(Long projectId, Long userId);

    /**
     * 使某个用户的未读数缓存失效。
     *
     * <p>公开出来是为了让「批量产生通知」的场景（一次操作推给多个人）
     * 能在循环结束后统一失效，而不是每推一条删一次 key。</p>
     */
    void evictUnreadCount(Long projectId, Long userId);
}
