import request from './request'
import type {
  AssetCategoryStatVO,
  AssetCompletionVO,
  BurndownVO,
  ReportVO,
  TaskTrendVO,
  WorkloadVO
} from '@/types/stat'

/**
 * 统计与进度可视化接口（模块4，文档 4.2 的 `/api/projects/{pid}/stat|report`）。
 *
 * 八个接口全部挂在项目路径下，项目ID 一律走路径，
 * 不依赖 `X-Project-Id` 请求头——越权与请求头无关，这是刻意的。
 *
 * 权限：后端按 `STAT_READ` 校验，OWNER / ARTIST / PROGRAMMER / DESIGNER 可看，
 * VIEWER 会拿到 403（文档 3.3 的权限矩阵）。前端只负责在没权限时
 * 把提示显示清楚，不做任何「前端隐藏等于安全」的推断。
 */

// ==================== 统计图表 ====================

/** 资产完成率与状态分布（环形图） */
export function fetchAssetCompletion(projectId: number) {
  return request.get<never, AssetCompletionVO>(`/projects/${projectId}/stat/asset-completion`)
}

/** 各分类资产数量与完成情况（柱状图） */
export function fetchAssetByCategory(projectId: number) {
  return request.get<never, AssetCategoryStatVO[]>(`/projects/${projectId}/stat/asset-by-category`)
}

/** 燃尽图（理想线 + 按状态日志回放的实际线） */
export function fetchBurndown(projectId: number) {
  return request.get<never, BurndownVO>(`/projects/${projectId}/stat/burndown`)
}

/** 成员工作量与负载均衡度 */
export function fetchWorkload(projectId: number) {
  return request.get<never, WorkloadVO>(`/projects/${projectId}/stat/workload`)
}

/** 任务新增 / 完成趋势（days 取值 7~180，缺省 30） */
export function fetchTaskTrend(projectId: number, days = 30) {
  return request.get<never, TaskTrendVO>(`/projects/${projectId}/stat/task-trend`, {
    params: { days }
  })
}

// ==================== 报表 ====================

/** 项目周报（weekStart 缺省为本周一） */
export function fetchWeeklyReport(projectId: number, weekStart?: string) {
  return request.get<never, ReportVO>(`/projects/${projectId}/report/weekly`, {
    params: weekStart ? { weekStart } : {}
  })
}

/** 项目月报（month 为 yyyy-MM，缺省为当前月） */
export function fetchMonthlyReport(projectId: number, month?: string) {
  return request.get<never, ReportVO>(`/projects/${projectId}/report/monthly`, {
    params: month ? { month } : {}
  })
}

// ==================== 导出 ====================

/**
 * 导出统计报表 xlsx。
 *
 * **必须走 axios 的 blob 模式，不能用 `window.open` / `location.href`**：
 * 导出接口要求 `Authorization: Bearer` 头，浏览器原生导航带不上这个头，
 * 结果必然是 401——而且是在新标签页里静默地失败，用户只会看到「点了没反应」。
 *
 * 另一个附带好处：接口出错时返回的是 `Result` JSON（HTTP 4xx），
 * 走 axios 能拿到状态码并提示；走 `window.open` 只会下载到一个
 * 内容是 403 JSON 的「文件」。
 *
 * 返回的 Blob 由调用方落盘（见 `utils/download.ts`），
 * 文件名取自响应头 `Content-Disposition`，取不到时用兜底名。
 */
export function exportStatReport(projectId: number) {
  return request.get<never, Blob>(`/projects/${projectId}/stat/export`, {
    responseType: 'blob',
    // 导出要跑好几个聚合，给足超时；默认 20s 在大项目上会误伤
    timeout: 60000
  })
}
