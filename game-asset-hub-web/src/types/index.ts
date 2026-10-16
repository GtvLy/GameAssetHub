/**
 * 与后端 Result<T> 及模块1 各 VO 对应的类型定义。
 *
 * 说明：后端字段与这里一一对应，改后端 VO 时同步改这里，
 * 让 TS 在编译期就把不一致的地方指出来。
 */

import type { AssetVO } from './asset'
import type { TaskCardVO } from './task'

/** 统一响应包装 */
export interface Result<T = unknown> {
  code: number
  message: string
  data: T
  timestamp: number
}

/** 后端返回的用户信息 */
export interface UserVO {
  id: number
  username: string
  nickname: string | null
  email: string | null
  avatar: string | null
  role: string
}

/** 登录响应 */
export interface LoginVO {
  token: string
  tokenType: string
  expiresIn: number
  user: UserVO
}

/** 项目内角色 */
export type ProjectRole = 'OWNER' | 'ARTIST' | 'PROGRAMMER' | 'DESIGNER' | 'VIEWER'

/** 成员状态 */
export type MemberStatus = 'INVITED' | 'ACTIVE' | 'REMOVED'

/** 项目状态 */
export type ProjectStatusValue = 'ACTIVE' | 'PAUSED' | 'ARCHIVED'

/** 项目 */
export interface ProjectVO {
  id: number
  name: string
  code: string | null
  description: string | null
  engine: string | null
  coverUrl: string | null
  ownerId: number
  startDate: string | null
  planEndDate: string | null
  status: ProjectStatusValue
  statusLabel: string
  memberCount: number
  myRole: ProjectRole | null
  canManage: boolean
  createTime: string | null
  updateTime: string | null
}

/** 当前用户 + 我参与的项目 */
export interface CurrentUserVO {
  userId: number
  username: string
  nickname: string | null
  email: string | null
  avatar: string | null
  role: string
  projects: ProjectVO[]
}

/** 项目成员 */
export interface ProjectMemberVO {
  id: number
  projectId: number
  userId: number
  username: string | null
  nickname: string | null
  email: string | null
  avatar: string | null
  role: ProjectRole
  roleLabel: string
  status: MemberStatus
  /** 仅 OWNER 可见；其他角色后端返回 null */
  inviteCode: string | null
  joinTime: string | null
  createTime: string | null
}

/**
 * 项目概览。
 *
 * asset* 系列由模块2 填充、task* 系列由模块3 填充。
 *
 * `myTasks` 是**用户维度**数据：它不进 Redis 缓存（缓存按项目维度共享），
 * 由后端在缓存之外实时查、挂到副本上返回。因此 A 与 B 打开同一个概览
 * 会拿到各自的待办清单，不会串号。
 */
export interface ProjectOverviewVO {
  projectId: number
  projectName: string
  projectStatus: ProjectStatusValue
  memberCount: number
  assetTotal: number
  assetApproved: number
  assetPending: number
  assetCompletionRate: number
  taskTotal: number
  taskDone: number
  taskOverdue: number
  daysToDeadline: number | null
  recentAssets: AssetVO[]
  myTasks: TaskCardVO[]
}

/** 创建项目请求 */
export interface ProjectCreateDTO {
  name: string
  code?: string | null
  description?: string | null
  engine?: string | null
  coverUrl?: string | null
  startDate?: string | null
  planEndDate?: string | null
}

/** 修改项目请求 */
export type ProjectUpdateDTO = ProjectCreateDTO

/** 邀请成员请求 */
export interface MemberInviteDTO {
  account: string
  role?: ProjectRole | null
}
