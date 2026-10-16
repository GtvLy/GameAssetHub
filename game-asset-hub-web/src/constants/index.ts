import type { ProjectRole, ProjectStatusValue, MemberStatus } from '@/types'

/**
 * 前端展示用的枚举元数据。
 *
 * 为什么要有一份前端的映射：后端已经在 VO 里带了 roleLabel / statusLabel，
 * 但「角色的配色」和「可选项列表」属于纯展示决策，放在前端更合适。
 * 这份表只用于渲染，权限判定一律以后端 RolePermissionMap 为准，前端不做任何推断。
 */

export interface RoleMeta {
  value: ProjectRole
  label: string
  /** Element Plus tag 的 type */
  tagType: 'primary' | 'success' | 'warning' | 'danger' | 'info'
  /** 角色职责说明，用在成员页的提示里 */
  description: string
}

export const PROJECT_ROLES: RoleMeta[] = [
  {
    value: 'OWNER',
    label: '负责人',
    tagType: 'danger',
    description: '项目全权限：改项目、管成员、审资产'
  },
  {
    value: 'ARTIST',
    label: '美术',
    tagType: 'primary',
    description: '资产读写与删除、看板读写、可看统计'
  },
  {
    value: 'PROGRAMMER',
    label: '程序',
    tagType: 'success',
    description: '资产只读、看板读写、可看统计'
  },
  {
    value: 'DESIGNER',
    label: '策划',
    tagType: 'warning',
    description: '资产只读、看板读写、可看统计'
  },
  {
    value: 'VIEWER',
    label: '外部协作者',
    tagType: 'info',
    description: '项目内全部只读，不含统计'
  }
]

const ROLE_MAP = new Map<string, RoleMeta>(PROJECT_ROLES.map((r) => [r.value, r]))

export function roleMeta(role: string | null | undefined): RoleMeta {
  return (
    (role ? ROLE_MAP.get(role) : undefined) ?? {
      value: 'VIEWER',
      label: role || '未知',
      tagType: 'info',
      description: ''
    }
  )
}

/** 项目状态展示元数据 */
export const PROJECT_STATUSES: Array<{
  value: ProjectStatusValue
  label: string
  tagType: 'success' | 'warning' | 'info'
}> = [
  { value: 'ACTIVE', label: '进行中', tagType: 'success' },
  { value: 'PAUSED', label: '已暂停', tagType: 'warning' },
  { value: 'ARCHIVED', label: '已归档', tagType: 'info' }
]

/** 成员状态展示元数据 */
export const MEMBER_STATUSES: Record<MemberStatus, { label: string; tagType: 'success' | 'warning' | 'info' }> =
  {
    ACTIVE: { label: '已加入', tagType: 'success' },
    INVITED: { label: '待加入', tagType: 'warning' },
    REMOVED: { label: '已移除', tagType: 'info' }
  }

/** 可选引擎，供新建项目表单做下拉 */
export const ENGINE_OPTIONS = ['UE5', 'Unity', 'Godot', 'Other']
