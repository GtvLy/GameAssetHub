import type { AssetPriority, AssetStatus } from '@/types/asset'

/**
 * 资产状态与优先级的展示元数据。
 *
 * 与模块1 的 constants/index.ts 同一原则：
 * 这里只定义「长什么样」（配色 / 标签 / 图标），
 * **不定义「能做什么」**——状态流转的合法性由后端下发
 * （AssetVO.nextStatuses），前端不复制一份状态机。
 * 否则后端调整工作流时，前端会留下一个点了必然报 400 的按钮。
 */

export interface AssetStatusMeta {
  value: AssetStatus
  label: string
  /** Element Plus tag 的 type */
  tagType: 'primary' | 'success' | 'warning' | 'danger' | 'info'
  /** 卡片墙左上角色块用的颜色变量 */
  color: string
  /** 状态说明，用在筛选下拉与详情页提示里 */
  description: string
}

export const ASSET_STATUSES: AssetStatusMeta[] = [
  {
    value: 'IN_PROGRESS',
    label: '制作中',
    tagType: 'primary',
    color: '#5b8cff',
    description: '正在制作或修改，尚未提交审核'
  },
  {
    value: 'PENDING',
    label: '待审核',
    tagType: 'warning',
    color: '#e6a23c',
    description: '已提交，等待负责人审核'
  },
  {
    value: 'APPROVED',
    label: '已通过',
    tagType: 'success',
    color: '#34c77b',
    description: '审核通过，可作为正式资源使用'
  },
  {
    value: 'REJECTED',
    label: '已打回',
    tagType: 'danger',
    color: '#f56c6c',
    description: '审核未通过，需按审核意见修改后重新提交'
  },
  {
    value: 'DEPRECATED',
    label: '已废弃',
    tagType: 'info',
    color: '#909399',
    description: '不再使用，但历史版本仍可追溯与下载'
  }
]

const STATUS_MAP = new Map<string, AssetStatusMeta>(ASSET_STATUSES.map((s) => [s.value, s]))

export function assetStatusMeta(status: string | null | undefined): AssetStatusMeta {
  return (
    (status ? STATUS_MAP.get(status) : undefined) ?? {
      value: 'IN_PROGRESS',
      label: status || '未知',
      tagType: 'info',
      color: '#909399',
      description: ''
    }
  )
}

export interface AssetPriorityMeta {
  value: AssetPriority
  label: string
  tagType: 'danger' | 'warning' | 'info'
  /** 卡片右上角标记的颜色 */
  color: string
  description: string
}

export const ASSET_PRIORITIES: AssetPriorityMeta[] = [
  { value: 'P0', label: '紧急', tagType: 'danger', color: '#f56c6c', description: '卡住主流程，需优先处理' },
  { value: 'P1', label: '高', tagType: 'warning', color: '#e6a23c', description: '本迭代内需要完成' },
  { value: 'P2', label: '普通', tagType: 'info', color: '#909399', description: '有则更好，不阻塞流程' }
]

const PRIORITY_MAP = new Map<string, AssetPriorityMeta>(ASSET_PRIORITIES.map((p) => [p.value, p]))

export function assetPriorityMeta(priority: string | null | undefined): AssetPriorityMeta {
  return (
    (priority ? PRIORITY_MAP.get(priority) : undefined) ?? {
      value: 'P2',
      label: priority || '普通',
      tagType: 'info',
      color: '#909399',
      description: ''
    }
  )
}

/**
 * 状态流转动作的中文名。
 *
 * 与状态本身不同，这里描述的是「动作」：
 * 同一个目标状态在不同来源下叫法不同（REJECTED → PENDING 是「重新提交」，
 * IN_PROGRESS → PENDING 是「提交审核」），所以按「来源→目标」查表，
 * 查不到时回落到目标状态的中文名。
 */
const TRANSITION_LABELS: Record<string, string> = {
  'IN_PROGRESS->PENDING': '提交审核',
  'IN_PROGRESS->DEPRECATED': '废弃',
  'PENDING->APPROVED': '通过',
  'PENDING->REJECTED': '打回',
  'REJECTED->PENDING': '重新提交',
  'REJECTED->IN_PROGRESS': '退回继续改',
  'REJECTED->DEPRECATED': '废弃',
  'APPROVED->IN_PROGRESS': '退回继续改',
  'APPROVED->DEPRECATED': '废弃',
  'DEPRECATED->IN_PROGRESS': '重新启用'
}

/** 动作按钮的文案 */
export function transitionLabel(from: string | null | undefined, to: AssetStatus): string {
  return TRANSITION_LABELS[`${from}->${to}`] ?? assetStatusMeta(to).label
}

/** 打回是唯一要求必填审核意见的动作（与后端校验一致） */
export function requiresRemark(to: AssetStatus): boolean {
  return to === 'REJECTED'
}

/**
 * 动作按钮的视觉层级。
 * 审核结论用主色（不可逆、影响大），退回与废弃用次级色，
 * 避免「通过」和「废弃」长得一样而被误点。
 */
export function transitionButtonType(
  to: AssetStatus
): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  switch (to) {
    case 'APPROVED':
      return 'success'
    case 'PENDING':
      return 'primary'
    case 'REJECTED':
      return 'warning'
    case 'DEPRECATED':
      return 'danger'
    default:
      return 'info'
  }
}

/** 分片大小兜底值（5MB）。实际以 init 接口返回的 chunkSize 为准 */
export const DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024

/** 单文件上限 2GB（文档 6 节：UE5 资产常见体积，与后端 multipart max-file-size 一致） */
export const MAX_UPLOAD_SIZE = 2 * 1024 * 1024 * 1024

/**
 * 超过这个体积改走分片上传，否则整包直传。
 *
 * 阈值就取一个分片的大小：文件装不满一片时走分片只是白绕一圈——
 * 同样要读一遍算 MD5、再多两次请求，而直传失败重来的代价本来也可忽略。
 * 13MB 的模型切成 5+5+3 三片，正好是这条规则开始起作用的地方。
 */
export const CHUNK_UPLOAD_THRESHOLD = DEFAULT_CHUNK_SIZE

/** 常见资产格式，用于上传对话框的提示 */
export const ASSET_FILE_HINTS = [
  '模型：fbx / obj / blend / uasset',
  '贴图：png / jpg / tga / exr',
  '音效：wav / mp3 / ogg',
  '其他：任意格式均可上传'
]

/**
 * 资产写权限的**界面可用性**判断。
 *
 * 真值在后端 `RolePermissionMap`：OWNER 全权；ARTIST 有
 * ASSET_WRITE + ASSET_DELETE 但没有 ASSET_REVIEW（制作者能删自己的草稿，
 * 不能自己审自己）；PROGRAMMER / DESIGNER / VIEWER 对资产只读。
 *
 * 这里镜像一份，只为不把「点了必然 403 的按钮」摆在用户面前。
 * **前端判断不是安全边界**——后端每个接口仍各自校验一遍。
 */
export function canWriteAssets(role: string | null | undefined): boolean {
  return role === 'OWNER' || role === 'ARTIST'
}

/** 删除资产：与写权限同一批角色（ARTIST 可删自己的草稿） */
export function canDeleteAssets(role: string | null | undefined): boolean {
  return canWriteAssets(role)
}

/** 审核（通过 / 打回 / 废弃只给 OWNER） */
export function canReviewAssets(role: string | null | undefined): boolean {
  return role === 'OWNER'
}

/**
 * 分类维护。
 *
 * 与资产写权限同一批角色——这不是「想当然」，而是照后端实现：
 * `AssetCategoryController` 的增改用 `ASSET_WRITE`、删除用 `ASSET_DELETE`，
 * 因此 ARTIST 也能建分类。前端若在这里收窄成「只有 OWNER」，
 * 美术就会被挡在一个他本来有权限的功能外面。
 */
export function canManageCategories(role: string | null | undefined): boolean {
  return canWriteAssets(role)
}

/**
 * 判断一次流转是否需要审核权。
 *
 * `nextStatuses` 由后端下发，但里面混着两类动作：ARTIST 能做的
 * （提交审核 / 重新提交 / 退回继续改）和只有 OWNER 能做的（通过 / 打回 / 废弃）。
 * 后端在服务层按目标状态再判一道，前端据此把按钮禁掉而不是隐藏——
 * 隐藏会让美术不知道「还有审核这一步」，禁用加上 tooltip 更好。
 */
export function transitionNeedsReview(to: AssetStatus): boolean {
  return to === 'APPROVED' || to === 'REJECTED' || to === 'DEPRECATED'
}
