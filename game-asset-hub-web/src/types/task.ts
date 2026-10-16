/**
 * 模块3（任务看板与协作）的类型定义。
 *
 * 与后端 VO/DTO 一一对应：后端改字段时同步改这里，
 * 让 vue-tsc 在编译期把不一致的地方直接指出来，而不是等到运行时白屏。
 *
 * 单独一个文件而不是塞进 types/index.ts：模块2 的资产类型已经独立成
 * types/asset.ts，看板的字段集（列、排序值、版本号、提及）与资产完全不同，
 * 混在一起会让 index.ts 变成什么都往里塞的杂物间。
 */

import type { PageResultVO } from '@/types/asset'

export type { PageResultVO }

/** 看板列（与后端 TaskColumn 一致） */
export type TaskColumn = 'TODO' | 'DOING' | 'REVIEW' | 'DONE'

/** 任务优先级 */
export type TaskPriority = 'P0' | 'P1' | 'P2'

/** 任务类型 */
export type TaskType = 'ART' | 'PROGRAM' | 'DESIGN' | 'BUG' | 'OTHER'

/** 通知类型（与后端 NotificationType 一致） */
export type NotificationType =
  | 'TASK_ASSIGNED'
  | 'TASK_STATUS'
  | 'COMMENT'
  | 'MENTION'
  | 'ASSET_REVIEW'
  | 'TASK_DUE_SOON'

/** 通知可跳转的业务类型 */
export type NotificationBizType = 'TASK' | 'ASSET' | 'PROJECT'

/**
 * 看板卡片。
 *
 * 注意 `version`：它是乐观锁版本号，拖拽时必须原样回传；
 * 服务端每次写操作都会把它 +1，因此 move 之后要用响应里的新值覆盖本地值，
 * 否则同一张卡片连续拖第二次必然 409。
 */
export interface TaskCardVO {
  id: number
  projectId: number
  title: string
  columnStatus: TaskColumn
  columnLabel: string
  priority: TaskPriority
  priorityLabel: string
  priorityColor: string
  taskType: TaskType
  taskTypeLabel: string
  taskTypeColor: string
  assigneeId: number | null
  assigneeName: string | null
  assigneeAvatar: string | null
  creatorId: number | null
  creatorName: string | null
  dueDate: string | null
  overdue: boolean
  dueSoon: boolean
  estimateHours: number | null
  actualHours: number | null
  storyPoint: number | null
  sortOrder: number
  version: number
  commentCount: number
  assetId: number | null
  assetName: string | null
  assetNo: string | null
  assetCategoryName: string | null
  assetCategoryColor: string | null
  assetStatus: string | null
  assetStatusLabel: string | null
  assetThumbnailUrl: string | null
  finishedAt: string | null
  createTime: string | null
  updateTime: string | null
}

/** 任务评论 */
export interface TaskCommentVO {
  id: number
  taskId: number
  userId: number
  userName: string | null
  userAvatar: string | null
  content: string
  mentionIds: number[]
  mentionNames: string[]
  /** 服务端判定「我能不能删这条」，前端不自己比 userId */
  canDelete: boolean
  createTime: string | null
}

/** 任务状态变更记录 */
export interface TaskStatusLogVO {
  id: number
  taskId: number
  fromStatus: TaskColumn | null
  fromLabel: string | null
  toStatus: TaskColumn
  toLabel: string
  operatorId: number
  operatorName: string | null
  createTime: string | null
}

/** 任务详情（卡片字段 + 描述 + 评论 + 状态日志） */
export interface TaskVO extends TaskCardVO {
  description: string | null
  comments: TaskCommentVO[]
  statusLogs: TaskStatusLogVO[]
}

/** 看板的一列 */
export interface BoardColumnVO {
  status: TaskColumn
  title: string
  color: string
  taskCount: number
  storyPointTotal: number
  tasks: TaskCardVO[]
}

/** 整块看板（一次请求拿到四列） */
export interface BoardVO {
  projectId: number
  totalTasks: number
  columns: BoardColumnVO[]
}

/** 拖拽落位结果：以服务端返回的 sortOrder / version 为准 */
export interface TaskMoveVO {
  id: number
  title: string | null
  columnStatus: TaskColumn
  columnLabel: string
  sortOrder: number
  version: number
  finishedAt: string | null
  rebalanced: boolean
  reorderedCount: number
}

/** 任务列表筛选与分页 */
export interface TaskQuery {
  page?: number
  size?: number
  columnStatus?: TaskColumn | null
  assigneeId?: number | null
  creatorId?: number | null
  assetId?: number | null
  priority?: TaskPriority | null
  taskType?: TaskType | null
  unfinishedOnly?: boolean | null
  overdueOnly?: boolean | null
  keyword?: string | null
  orderBy?: 'sortOrder' | 'dueDate' | 'updateTime' | null
}

/** 新建任务 */
export interface TaskCreateDTO {
  title: string
  description?: string | null
  columnStatus?: TaskColumn | null
  priority?: TaskPriority | null
  taskType?: TaskType | null
  assigneeId?: number | null
  dueDate?: string | null
  estimateHours?: number | null
  storyPoint?: number | null
  assetId?: number | null
}

/** 更新任务（不含列——改列只走 move） */
export interface TaskUpdateDTO {
  title: string
  description?: string | null
  priority?: TaskPriority | null
  taskType?: TaskType | null
  assigneeId?: number | null
  dueDate?: string | null
  estimateHours?: number | null
  actualHours?: number | null
  storyPoint?: number | null
  assetId?: number | null
}

/** 拖拽移动请求 */
export interface TaskMoveDTO {
  toColumn: TaskColumn
  prevTaskId?: number | null
  nextTaskId?: number | null
  /** 乐观锁版本号，必填 */
  version: number
}

/** 站内通知 */
export interface NotificationVO {
  id: number
  projectId: number | null
  type: NotificationType
  typeLabel: string
  title: string
  content: string | null
  bizType: NotificationBizType | null
  bizId: number | null
  senderId: number | null
  senderName: string | null
  isRead: number
  createTime: string | null
}

/** 通知列表查询参数 */
export interface NotificationQuery {
  page?: number
  size?: number
  unreadOnly?: boolean | null
}
