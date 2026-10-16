import type { NotificationType, TaskColumn, TaskPriority, TaskType } from '@/types/task'

/**
 * 任务看板的展示元数据（列 / 优先级 / 类型 / 通知类型）。
 *
 * 与模块1、模块2 同一原则：这里只定义「长什么样」，
 * **不定义「能做什么」**。列的顺序与配色由后端下发（`BoardVO.columns`），
 * 这里只保留一份「后端没下发时的兜底」与「非看板场景（表单下拉/筛选）要用的选项」。
 *
 * 权限判定一律以后端 `RolePermissionMap` 为准，前端只镜像一份用于隐藏按钮。
 */

export interface TaskColumnMeta {
  value: TaskColumn
  label: string
  color: string
  description: string
}

/** 四列及其顺序（与后端 TaskColumn.ordered() 一致） */
export const TASK_COLUMNS: TaskColumnMeta[] = [
  { value: 'TODO', label: '待办', color: '#909399', description: '已建但未开工' },
  { value: 'DOING', label: '进行中', color: '#5b8cff', description: '正在做' },
  { value: 'REVIEW', label: '待审核', color: '#e6a23c', description: '做完等待验收' },
  { value: 'DONE', label: '已完成', color: '#67c23a', description: '验收通过' }
]

const COLUMN_MAP = new Map<string, TaskColumnMeta>(TASK_COLUMNS.map((c) => [c.value, c]))

export function taskColumnMeta(status: string | null | undefined): TaskColumnMeta {
  return (
    (status ? COLUMN_MAP.get(status) : undefined) ?? {
      value: 'TODO',
      label: status || '未知',
      color: '#909399',
      description: ''
    }
  )
}

export interface TaskPriorityMeta {
  value: TaskPriority
  label: string
  /** Element Plus tag 的 type */
  tagType: 'danger' | 'warning' | 'info'
  /** 卡片左侧色条（文档 7.2：P0 红 / P1 橙 / P2 灰） */
  color: string
}

export const TASK_PRIORITIES: TaskPriorityMeta[] = [
  { value: 'P0', label: '紧急', tagType: 'danger', color: '#f56c6c' },
  { value: 'P1', label: '高', tagType: 'warning', color: '#e6a23c' },
  { value: 'P2', label: '普通', tagType: 'info', color: '#909399' }
]

const PRIORITY_MAP = new Map<string, TaskPriorityMeta>(TASK_PRIORITIES.map((p) => [p.value, p]))

/** 优先级元数据；值缺失时用服务端下发的颜色兜底（脏数据不该让看板渲染不出来） */
export function taskPriorityMeta(
  priority: string | null | undefined,
  color?: string | null
): TaskPriorityMeta {
  const hit = priority ? PRIORITY_MAP.get(priority) : undefined
  if (hit) return hit
  return { value: 'P2', label: priority || '普通', tagType: 'info', color: color || '#909399' }
}

export interface TaskTypeMeta {
  value: TaskType
  label: string
  color: string
}

export const TASK_TYPES: TaskTypeMeta[] = [
  { value: 'ART', label: '美术', color: '#5b8cff' },
  { value: 'PROGRAM', label: '程序', color: '#67c23a' },
  { value: 'DESIGN', label: '策划', color: '#e6a23c' },
  { value: 'BUG', label: '缺陷', color: '#f56c6c' },
  { value: 'OTHER', label: '其它', color: '#909399' }
]

const TYPE_MAP = new Map<string, TaskTypeMeta>(TASK_TYPES.map((t) => [t.value, t]))

export function taskTypeMeta(type: string | null | undefined): TaskTypeMeta {
  return (
    (type ? TYPE_MAP.get(type) : undefined) ?? {
      value: 'OTHER',
      label: type || '未知',
      color: '#909399'
    }
  )
}

/** 通知类型的中文名（后端也会下发 typeLabel，这里用于兜底与筛选下拉） */
export const NOTIFICATION_TYPE_LABELS: Record<NotificationType, string> = {
  TASK_ASSIGNED: '任务指派',
  TASK_STATUS: '任务状态',
  COMMENT: '评论',
  MENTION: '提及',
  ASSET_REVIEW: '资产审核',
  TASK_DUE_SOON: '即将到期'
}

/**
 * 看板写权限的**界面可用性**判断。
 *
 * 真值在后端 `RolePermissionMap`：OWNER / ARTIST / PROGRAMMER / DESIGNER
 * 都有 `TASK_WRITE`（看板读写），**VIEWER 只有 `TASK_READ`**。
 * 前端据此隐藏按钮只是体验优化，不是安全边界——后端每个接口仍各自校验。
 */
export function canWriteTasks(role: string | null | undefined): boolean {
  return role === 'OWNER' || role === 'ARTIST' || role === 'PROGRAMMER' || role === 'DESIGNER'
}

/**
 * 截止日期的展示文案与紧急度。
 *
 * 「已完成的任务不算逾期」这条必须与后端一致（后端 `TaskViewSupport#isOverdue`
 * 用的是同一个判断），否则会出现「卡片标红但看板统计里不算逾期」的矛盾。
 */
export function dueState(
  dueDate: string | null | undefined,
  columnStatus: string | null | undefined
): { text: string; level: 'normal' | 'soon' | 'overdue' | 'done' } {
  if (!dueDate) return { text: '未设截止', level: 'normal' }
  if (columnStatus === 'DONE') return { text: dueDate, level: 'done' }

  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const due = new Date(`${dueDate}T00:00:00`)
  const days = Math.round((due.getTime() - today.getTime()) / 86400000)

  if (days < 0) return { text: `逾期 ${-days} 天`, level: 'overdue' }
  if (days === 0) return { text: '今天到期', level: 'soon' }
  if (days === 1) return { text: '明天到期', level: 'soon' }
  return { text: dueDate, level: 'normal' }
}
