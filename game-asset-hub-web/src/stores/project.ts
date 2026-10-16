import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { ProjectVO } from '@/types'

const CURRENT_PROJECT_KEY = 'gah_current_project'

/**
 * 当前项目上下文。
 *
 * 这个 store 是「项目级数据隔离」在前端的支点：
 *   - 请求拦截器把 currentId 写进 X-Project-Id 头；
 *   - 权限切面据路径/请求头解析出 projectId 做 RBAC 校验。
 * 因此切换项目必须同步更新 currentId，否则会出现「看着 A 项目的数据、
 * 实际请求的是 B 项目」这种最难排查的错误。
 */
export const useProjectStore = defineStore('project', () => {
  const projects = ref<ProjectVO[]>([])
  const currentId = ref<number | null>(readStoredId())
  const loading = ref(false)

  const currentProject = computed<ProjectVO | null>(
    () => projects.value.find((p) => p.id === currentId.value) || null
  )

  /** 我在当前项目中的角色 */
  const myRole = computed(() => currentProject.value?.myRole ?? null)

  /** 当前项目是否可管理（决定「邀请成员」「改角色」「归档」等按钮的可用性） */
  const canManage = computed(() => !!currentProject.value?.canManage)

  function readStoredId(): number | null {
    const raw = localStorage.getItem(CURRENT_PROJECT_KEY)
    if (!raw) return null
    const parsed = Number(raw)
    return Number.isFinite(parsed) ? parsed : null
  }

  function setProjects(list: ProjectVO[]) {
    projects.value = list
    // 当前项目已被归档/移除时，自动落到第一个可用项目，避免停在一个打不开的项目上
    if (currentId.value != null && !list.some((p) => p.id === currentId.value)) {
      setCurrent(list.length ? list[0].id : null)
    }
    // 首次进入且没有历史选择时，默认选中第一个
    if (currentId.value == null && list.length) {
      setCurrent(list[0].id)
    }
  }

  function setCurrent(id: number | null) {
    currentId.value = id
    if (id == null) {
      localStorage.removeItem(CURRENT_PROJECT_KEY)
    } else {
      localStorage.setItem(CURRENT_PROJECT_KEY, String(id))
    }
  }

  function clear() {
    projects.value = []
    setCurrent(null)
  }

  return {
    projects,
    currentId,
    loading,
    currentProject,
    myRole,
    canManage,
    setProjects,
    setCurrent,
    clear
  }
})
