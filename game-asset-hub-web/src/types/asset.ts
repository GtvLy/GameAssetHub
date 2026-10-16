/**
 * 模块2（美术资产管理）的类型定义。
 *
 * 与后端 VO / DTO 一一对应，字段名完全一致。
 * 单独放一个文件而不是塞进 types/index.ts：模块1 的类型是「项目与成员」，
 * 模块2 是「资产与版本」，混在一起后改一边要在长文件里翻找。
 *
 * 与 types/task.ts 之间是**类型层面的双向引用**（这里要 TaskCardVO，
 * 那边要 PageResultVO）。两边都只用 `import type`，编译后不留任何运行时代码，
 * 因此不构成真正的循环依赖；这也是「任务与资产互相关联」在类型上的自然投影。
 */

import type { TaskCardVO } from './task'

/** 资产状态（与后端 AssetStatus 枚举一致） */
export type AssetStatus =
  | 'IN_PROGRESS'
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'DEPRECATED'

/** 资产优先级 */
export type AssetPriority = 'P0' | 'P1' | 'P2'

/** 资产分类（树形，一级分类的 children 装二级分类） */
export interface AssetCategoryVO {
  id: number
  projectId: number
  /** 0 表示一级分类 */
  parentId: number
  name: string
  /** 分类编码，资产编号的前缀 */
  code: string
  icon: string | null
  color: string | null
  sortOrder: number
  /** 一级分类的数量含其子分类 */
  assetCount: number
  children: AssetCategoryVO[]
  createTime: string | null
}

/** 资产版本（版本时间线上的一个节点） */
export interface AssetVersionVO {
  id: number
  assetId: number
  projectId: number
  versionNo: number
  versionName: string | null
  fileName: string
  fileKey: string | null
  fileSize: number
  /** 人类可读的体积，如「181.0 MB」，由后端算好 */
  fileSizeText: string
  fileExt: string | null
  fileMd5: string | null
  thumbnailUrl: string | null
  polyCount: number | null
  textureSize: string | null
  changeLog: string | null
  uploaderId: number
  uploaderName: string | null
  isCurrent: number
  current: boolean
  downloadUrl: string | null
  createTime: string | null
}

/** 资产（列表项与详情共用） */
export interface AssetVO {
  id: number
  projectId: number
  categoryId: number
  categoryName: string | null
  categoryCode: string | null
  categoryColor: string | null
  assetNo: string
  name: string
  description: string | null
  currentVersionId: number | null
  versionCount: number
  status: AssetStatus
  statusLabel: string
  priority: AssetPriority
  priorityLabel: string
  tags: string | null
  /** 后端已经 split 好的标签数组，前端不必再处理 */
  tagList: string[]
  ownerId: number
  ownerName: string | null
  reviewerId: number | null
  reviewerName: string | null
  reviewRemark: string | null
  reviewTime: string | null
  /** 当前状态下允许流转到的状态，由后端下发（状态机只有一份实现） */
  nextStatuses: AssetStatus[]
  currentVersion: AssetVersionVO | null
  thumbnailUrl: string | null
  lastUploaderName: string | null
  createTime: string | null
  updateTime: string | null
  /**
   * 相关任务（模块3 交付，文档 5.5 的双向联动）。
   *
   * **只有资产详情接口会返回它**，列表接口为 undefined——
   * 后端在列表路径上刻意不查这个字段，避免一页 20 条资产产生 20 次额外查询。
   * 因此渲染时要用 `asset.relatedTasks ?? []`，不要假设它一定有值。
   */
  relatedTasks?: TaskCardVO[]
}

/** 与看板共用的任务卡片类型（资产详情里的「相关任务」用它） */
export type { TaskCardVO }

/** 分页结果 */
export interface PageResultVO<T> {
  records: T[]
  total: number
  page: number
  size: number
  pages: number
  hasNext: boolean
}

/** 资产列表筛选参数 */
export interface AssetQuery {
  page?: number
  size?: number
  categoryId?: number | null
  /** 传一级分类时是否包含子分类，默认 true */
  categoryWithChildren?: boolean
  status?: AssetStatus | null
  ownerId?: number | null
  priority?: AssetPriority | null
  keyword?: string | null
}

/** 新增 / 修改分类 */
export interface AssetCategoryDTO {
  parentId?: number | null
  name: string
  code: string
  icon?: string | null
  color?: string | null
  sortOrder?: number | null
}

/** 新建资产（含首个版本的元数据） */
export interface AssetCreateDTO {
  categoryId: number
  name: string
  description?: string | null
  priority?: AssetPriority | null
  tags?: string | null
  versionName?: string | null
  changeLog?: string | null
  polyCount?: number | null
  textureSize?: string | null
  thumbnailUrl?: string | null
  /** 秒传：命中已有 MD5 时可只带它、不带文件 */
  fileMd5?: string | null
  fileName?: string | null
}

/** 修改资产信息 */
export interface AssetUpdateDTO {
  name: string
  description?: string | null
  categoryId?: number | null
  priority?: AssetPriority | null
  tags?: string | null
}

/** 上传新版本的元数据 */
export interface UploadVersionDTO {
  versionName?: string | null
  changeLog?: string | null
  polyCount?: number | null
  textureSize?: string | null
  thumbnailUrl?: string | null
  fileMd5?: string | null
  fileName?: string | null
}

/** 秒传检查请求 */
export interface Md5CheckDTO {
  md5: string
  fileName?: string | null
  fileSize?: number | null
}

/** 秒传检查结果 */
export interface Md5CheckVO {
  exists: boolean
  md5: string
  fileKey: string | null
  assetId: number | null
  assetNo: string | null
  assetName: string | null
  versionId: number | null
  versionNo: number | null
  fileName: string | null
  fileSize: number | null
  message: string
}

/** 分片上传初始化请求 */
export interface ChunkInitDTO {
  fileName: string
  fileSize: number
  totalChunks: number
  md5?: string | null
}

/** 分片上传初始化结果 */
export interface ChunkInitVO {
  uploadId: string
  /** 服务端要求的分片大小（字节），前端按它切片 */
  chunkSize: number
  totalChunks: number
  uploadedIndexes: number[]
}

/** 状态流转请求 */
export interface AssetStatusDTO {
  toStatus: AssetStatus
  remark?: string | null
}

/** 状态流转记录 */
export interface AssetStatusLogVO {
  id: number
  assetId: number
  fromStatus: string | null
  fromStatusLabel: string | null
  toStatus: string
  toStatusLabel: string
  operatorId: number
  operatorName: string | null
  remark: string | null
  createTime: string | null
}

/** 版本对比的单项差异 */
export interface VersionDiffItem {
  field: string
  before: string
  after: string
  changed: boolean
}

/** 版本对比结果 */
export interface AssetVersionCompareVO {
  assetId: number
  from: AssetVersionVO
  to: AssetVersionVO
  diffs: VersionDiffItem[]
  polyCountDelta: number | null
  fileSizeDelta: number
  fileSizeDeltaText: string
}
