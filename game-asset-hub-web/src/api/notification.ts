import request from './request'
import type { NotificationQuery, NotificationVO, PageResultVO } from '@/types/task'

/**
 * 站内通知接口（模块3，文档 4.2）。
 *
 * 四条路径都没有项目段：项目上下文来自请求拦截器注入的 `X-Project-Id`。
 * 未读数与列表用同一个维度（用户 + 当前项目），因此角标数字与点开后看到的
 * 条数永远一致——不会出现「角标 3、列表 1 条」那种让人怀疑数据的情况。
 */

/** 我的通知（分页，可筛未读） */
export function fetchNotifications(query: NotificationQuery = {}) {
  return request.get<never, PageResultVO<NotificationVO>>('/notifications', { params: query })
}

/** 未读数（铃铛角标，每 30 秒轮询一次） */
export function fetchUnreadCount() {
  return request.get<never, number>('/notifications/unread-count')
}

/** 标记单条已读 */
export function markNotificationRead(id: number) {
  return request.patch<never, NotificationVO>(`/notifications/${id}/read`)
}

/** 全部标记已读，返回影响的条数 */
export function markAllNotificationsRead() {
  return request.patch<never, number>('/notifications/read-all')
}
