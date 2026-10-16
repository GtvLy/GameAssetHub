import request from './request'
import type { CurrentUserVO, LoginVO } from '@/types'

/** 注册请求体 */
export interface RegisterPayload {
  username: string
  password: string
  nickname?: string
  email?: string
}

/** 注册 */
export function register(payload: RegisterPayload) {
  return request.post<never, void>('/auth/register', payload)
}

/** 登录，返回 JWT */
export function login(username: string, password: string) {
  return request.post<never, LoginVO>('/auth/login', { username, password })
}

/** 登出：后端把当前令牌的 jti 写入 Redis 黑名单 */
export function logout() {
  return request.post<never, void>('/auth/logout')
}

/** 当前用户信息 + 我参与的项目列表 */
export function fetchCurrentUser() {
  return request.get<never, CurrentUserVO>('/auth/me')
}
