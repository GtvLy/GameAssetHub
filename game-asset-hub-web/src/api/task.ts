import request from './request'
import type {
  BoardVO,
  PageResultVO,
  TaskCardVO,
  TaskCommentVO,
  TaskCreateDTO,
  TaskMoveDTO,
  TaskMoveVO,
  TaskQuery,
  TaskUpdateDTO,
  TaskVO
} from '@/types/task'

/**
 * 任务看板与协作接口（模块3，文档 4.2）。
 *
 * 两条路径形态与模块2 的资产接口完全对称：
 *   `/projects/{pid}/board|tasks` —— 项目维度，项目ID 在路径里；
 *   `/tasks/{id}`、`/tasks/comments/{cid}` —— 任务维度，路径里没有项目段，
 *   项目上下文由请求拦截器全局注入的 `X-Project-Id` 提供。
 */

// ==================== 看板与列表 ====================

/** 整块看板：一次请求拿到四列（含每列数量与故事点合计） */
export function fetchBoard(projectId: number) {
  return request.get<never, BoardVO>(`/projects/${projectId}/board`)
}

/** 任务列表（分页 + 筛选） */
export function fetchTasks(projectId: number, query: TaskQuery) {
  return request.get<never, PageResultVO<TaskCardVO>>(`/projects/${projectId}/tasks`, {
    params: query
  })
}

/** 创建任务（可关联资产、可指派） */
export function createTask(projectId: number, payload: TaskCreateDTO) {
  return request.post<never, TaskVO>(`/projects/${projectId}/tasks`, payload)
}

// ==================== 任务维度 ====================

/** 任务详情（含评论与状态日志） */
export function fetchTask(id: number) {
  return request.get<never, TaskVO>(`/tasks/${id}`)
}

/** 更新任务内容（不含列状态） */
export function updateTask(id: number, payload: TaskUpdateDTO) {
  return request.put<never, TaskVO>(`/tasks/${id}`, payload)
}

/**
 * 拖拽移动。
 *
 * 必须带 `version`：它是乐观锁版本号，冲突时后端返回 409，
 * 响应拦截器会提示「数据已被他人修改，请刷新后重试」。
 * 响应里的 `sortOrder` 与 `version` 要覆盖本地值——前者是服务端算的最终位置，
 * 后者不回填的话下一次拖拽必然 409。
 */
export function moveTask(id: number, payload: TaskMoveDTO) {
  return request.patch<never, TaskMoveVO>(`/tasks/${id}/move`, payload)
}

/** 指派任务（assigneeId 为 null 表示取消指派） */
export function assignTask(id: number, assigneeId: number | null) {
  return request.patch<never, TaskVO>(`/tasks/${id}/assign`, { assigneeId })
}

/** 删除任务（逻辑删除，评论与状态日志保留） */
export function deleteTask(id: number) {
  return request.delete<never, void>(`/tasks/${id}`)
}

// ==================== 评论 ====================

/** 评论列表（按时间正序） */
export function fetchComments(taskId: number) {
  return request.get<never, TaskCommentVO[]>(`/tasks/${taskId}/comments`)
}

/** 发表评论，支持 @用户名 提及本项目成员 */
export function addComment(taskId: number, content: string) {
  return request.post<never, TaskCommentVO>(`/tasks/${taskId}/comments`, { content })
}

/** 删除自己的评论 */
export function deleteComment(commentId: number) {
  return request.delete<never, void>(`/tasks/comments/${commentId}`)
}
