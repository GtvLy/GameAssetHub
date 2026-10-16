import axios, { type AxiosInstance, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useProjectStore } from '@/stores/project'
import router from '@/router'
import type { Result } from '@/types'

/** 后端地址：开发期走 Vite 代理（同源），生产可改由环境变量注入 */
const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'

const request: AxiosInstance = axios.create({
  baseURL,
  timeout: 20000,
  headers: { 'Content-Type': 'application/json;charset=UTF-8' }
})

/**
 * 请求拦截器
 *
 * 做两件事：
 *   1. 注入 Authorization: Bearer <token>；
 *   2. 注入 X-Project-Id，把「当前项目」这一上下文带给后端。
 *      后端的 RBAC 切面据此（或据路径变量）解析 projectId 做成员与角色校验。
 *      注意：store 只能在拦截器<b>执行时</b>取，不能在模块顶层取——
 *      pinia 实例要等 app.use(pinia) 之后才可用。
 */
request.interceptors.request.use(
  (config) => {
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers.Authorization = `Bearer ${userStore.token}`
    }

    const projectStore = useProjectStore()
    // 项目列表 / 登录注册这类接口本身不需要项目上下文；
    // 而项目成员接口路径里已经带了项目ID，也不依赖这个头。
    // 这里统一注入，后端解析器按「路径优先、请求头兜底」的顺序取，冲突时以路径为准。
    if (projectStore.currentId != null) {
      config.headers['X-Project-Id'] = String(projectStore.currentId)
    }
    return config
  },
  (error) => Promise.reject(error)
)

/** 401 统一处理：清本地登录态并跳登录页。加锁避免并发请求弹出多个提示 */
let redirecting = false

function handleUnauthorized(message: string) {
  const userStore = useUserStore()
  userStore.clear()
  useProjectStore().clear()
  if (!redirecting) {
    redirecting = true
    ElMessage.error(message)
    const current = router.currentRoute.value
    router
      .push({ path: '/login', query: current.path === '/login' ? {} : { redirect: current.fullPath } })
      .finally(() => {
        redirecting = false
      })
  }
}

/**
 * 响应拦截器
 *
 * 后端对业务失败<b>同时</b>设置 HTTP 状态码与响应体 code
 * （越权返回 HTTP 403 + {"code":403}）。因此两个分支都要处理：
 * axios 会把 4xx/5xx 直接抛到 error 分支，只写成功分支会漏掉最常见的情况。
 */
request.interceptors.response.use(
  (response: AxiosResponse<Result>) => {
    const body = response.data
    // 非统一包装的响应（例如静态资源）原样返回
    if (body == null || typeof body.code !== 'number') {
      return body as unknown as never
    }

    const { code, message, data } = body
    if (code === 200) {
      // 直接返回业务数据，调用方不必每次都 .data.data
      return data as never
    }

    if (code === 401) {
      handleUnauthorized(message || '登录已过期，请重新登录')
    } else if (code === 403) {
      ElMessage.error('无权访问该资源')
    } else if (code === 409) {
      ElMessage.warning('数据已被他人修改，请刷新后重试')
    } else {
      ElMessage.error(message || '请求失败')
    }
    return Promise.reject(new Error(message || '请求失败'))
  },
  (error) => {
    const status: number | undefined = error?.response?.status
    const message: string | undefined = error?.response?.data?.message

    if (status === 401) {
      handleUnauthorized(message || '登录已过期，请重新登录')
    } else if (status === 403) {
      ElMessage.error('无权访问该资源')
    } else if (status === 409) {
      ElMessage.warning('数据已被他人修改，请刷新后重试')
    } else if (status === 404) {
      ElMessage.error(message || '资源不存在')
    } else if (error?.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请检查网络后重试')
    } else {
      ElMessage.error(message || '网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export default request
