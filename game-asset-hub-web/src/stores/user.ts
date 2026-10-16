import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { CurrentUserVO, LoginVO, UserVO } from '@/types'

const TOKEN_KEY = 'gah_token'
const USER_KEY = 'gah_user'

function readUser(): UserVO | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as UserVO) : null
  } catch {
    // 存储内容被手工改坏时不要让整个应用起不来，退回未登录态
    return null
  }
}

/**
 * 登录用户状态。
 *
 * 持久化到 localStorage：刷新页面后仍保持登录，这是工具类应用的默认预期。
 * token 只放在这里，请求拦截器从 store 取，避免散落各处。
 */
export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref<UserVO | null>(readUser())

  const isLoggedIn = computed(() => !!token.value)
  const displayName = computed(() => user.value?.nickname || user.value?.username || '未登录')

  /** 头像兜底文字：取昵称首字符 */
  const avatarText = computed(() => {
    const name = user.value?.nickname || user.value?.username || '?'
    return name.charAt(0).toUpperCase()
  })

  function setLogin(payload: LoginVO) {
    token.value = payload.token
    user.value = payload.user
    localStorage.setItem(TOKEN_KEY, payload.token)
    localStorage.setItem(USER_KEY, JSON.stringify(payload.user))
  }

  /** 同步 /api/auth/me 返回的最新用户信息（忽略其中的 projects 字段，项目列表归 project store） */
  function setFromCurrentUser(me: CurrentUserVO) {
    setUser({
      id: me.userId,
      username: me.username,
      nickname: me.nickname,
      email: me.email,
      avatar: me.avatar,
      role: me.role
    })
  }

  function setUser(next: UserVO) {
    user.value = next
    localStorage.setItem(USER_KEY, JSON.stringify(next))
  }

  /** 清空本地登录态。不在这里调接口，登出请求由调用方负责，避免 store 反向依赖 api */
  function clear() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return {
    token,
    user,
    isLoggedIn,
    displayName,
    avatarText,
    setLogin,
    setUser,
    setFromCurrentUser,
    clear
  }
})
