/**
 * 模块四：统计与进度可视化的类型定义（与后端 VO 一一对应）。
 *
 * 这里的字段名就是后端 VO 的字段名，不做任何重命名——
 * 两边名字一旦不一致，就会出现「接口通了、图上没数据」这类只能靠肉眼发现的问题。
 */

/** 燃尽图上的一个点 */
export interface BurndownPointVO {
  /** yyyy-MM-dd */
  date: string
  points: number
}

/** 燃尽图（理想线 + 用状态日志回放的实际线） */
export interface BurndownVO {
  projectId: number
  startDate: string
  endDate: string
  /** 实际线画到的最后一天 = min(计划结束日, 今天) */
  actualEndDate: string | null
  today: string
  totalPoints: number
  totalDays: number
  /** 已消耗天数，已夹紧到 [0, totalDays] */
  elapsedDays: number
  /** 覆盖 [startDate, endDate] 全区间，逐日 */
  idealLine: BurndownPointVO[]
  /** 覆盖 [startDate, actualEndDate]，逐日；今天早于开始日时为空 */
  actualLine: BurndownPointVO[]
  currentPoints: number
  /** AHEAD / ON_TRACK / BEHIND */
  scheduleStatus: string
  scheduleStatusLabel: string
  idealPointsToday: number
  overduePlan: boolean
}

/** 通用计数项（状态分布、分类分布共用） */
export interface StatCountVO {
  key: string
  label: string
  color: string
  count: number
  ratio: number
}

/** 资产完成率与状态分布（环形图） */
export interface AssetCompletionVO {
  projectId: number
  assetTotal: number
  assetApproved: number
  assetPending: number
  assetInProgress: number
  assetRejected: number
  assetDeprecated: number
  /** 完成率（%）= 已通过 / 总数 */
  completionRate: number
  /** 完成率口径说明，直接显示在图表下方 */
  completionDefinition: string
  statusDistribution: StatCountVO[]
}

/** 单个分类的资产数量与完成情况（柱状图） */
export interface AssetCategoryStatVO {
  categoryId: number
  categoryName: string
  categoryCode: string
  parentId: number
  color: string
  total: number
  approved: number
  pending: number
  inProgress: number
  completionRate: number
}

/** 单个成员的工作量 */
export interface WorkloadItemVO {
  userId: number
  nickname: string
  role: string
  roleLabel: string
  taskTotal: number
  taskDone: number
  taskCompletionRate: number
  estimateHours: number
  actualHours: number
  points: number
  versionUploaded: number
  assetApproved: number
  belowAverage: boolean
}

/** 成员工作量与负载均衡度 */
export interface WorkloadVO {
  projectId: number
  memberCount: number
  members: WorkloadItemVO[]
  taskTotalSum: number
  /** 人均任务数 */
  averageTask: number
  /** 任务数总体标准差 */
  stdDevTask: number
  /** 负载均衡度 = 1 − 标准差 / 平均值 */
  balanceIndex: number
  balanceWarning: boolean
  hint: string
  balanceDefinition: string
}

/** 趋势图上的一个点（空日补 0） */
export interface TrendPointVO {
  date: string
  created: number
  completed: number
}

/** 任务新增 / 完成趋势 */
export interface TaskTrendVO {
  projectId: number
  days: number
  startDate: string
  endDate: string
  points: TrendPointVO[]
  totalCreated: number
  totalCompleted: number
  netGrowth: number
}

/** 周期内单个成员的产出 */
export interface ReportMemberVO {
  userId: number
  nickname: string
  role: string
  roleLabel: string
  taskCreated: number
  taskCompleted: number
  taskOpen: number
  versionUploaded: number
  assetApproved: number
  points: number
  actualHours: number
}

/** 项目周报 / 月报 */
export interface ReportVO {
  projectId: number
  projectName: string
  reportType: string
  reportTypeLabel: string
  periodStart: string
  periodEnd: string
  period: string
  taskCreated: number
  taskCompleted: number
  taskOverdue: number
  remainingPoints: number
  taskTotal: number
  taskDone: number
  burndownStatus: string
  burndownStatusLabel: string
  assetAdded: number
  assetApproved: number
  assetPendingReview: number
  workloads: ReportMemberVO[]
  risks: string[]
  summary: string
  generatedAt: string
  snapshotSaved: boolean
}
