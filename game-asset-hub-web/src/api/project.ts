import request from './request'
import type {
  MemberInviteDTO,
  ProjectCreateDTO,
  ProjectMemberVO,
  ProjectOverviewVO,
  ProjectRole,
  ProjectStatusValue,
  ProjectUpdateDTO,
  ProjectVO
} from '@/types'

// ==================== 项目 ====================

/** 我参与的项目列表（含我的角色） */
export function fetchMyProjects() {
  return request.get<never, ProjectVO[]>('/projects')
}

/** 创建项目，创建者自动成为 OWNER */
export function createProject(payload: ProjectCreateDTO) {
  return request.post<never, ProjectVO>('/projects', payload)
}

/** 项目详情 */
export function fetchProject(id: number) {
  return request.get<never, ProjectVO>(`/projects/${id}`)
}

/** 修改项目信息（OWNER） */
export function updateProject(id: number, payload: ProjectUpdateDTO) {
  return request.put<never, ProjectVO>(`/projects/${id}`, payload)
}

/** 变更项目状态（OWNER） */
export function changeProjectStatus(id: number, status: ProjectStatusValue) {
  return request.patch<never, ProjectVO>(`/projects/${id}/status`, { status })
}

/** 归档项目（OWNER，逻辑删除） */
export function archiveProject(id: number) {
  return request.delete<never, void>(`/projects/${id}`)
}

/** 项目概览（后端 Redis 缓存 10 分钟） */
export function fetchProjectOverview(id: number) {
  return request.get<never, ProjectOverviewVO>(`/projects/${id}/overview`)
}

// ==================== 成员 ====================

/** 成员列表 */
export function fetchMembers(projectId: number) {
  return request.get<never, ProjectMemberVO[]>(`/projects/${projectId}/members`)
}

/** 邀请成员（OWNER）：按用户名或邮箱，返回带邀请码的成员记录 */
export function inviteMember(projectId: number, payload: MemberInviteDTO) {
  return request.post<never, ProjectMemberVO>(`/projects/${projectId}/members`, payload)
}

/** 修改成员角色（OWNER） */
export function updateMemberRole(projectId: number, userId: number, role: ProjectRole) {
  return request.put<never, ProjectMemberVO>(`/projects/${projectId}/members/${userId}`, { role })
}

/** 移除成员（OWNER） */
export function removeMember(projectId: number, userId: number) {
  return request.delete<never, void>(`/projects/${projectId}/members/${userId}`)
}

/** 凭邀请码加入项目 */
export function joinProject(inviteCode: string) {
  return request.post<never, ProjectVO>('/projects/join', { inviteCode })
}
