import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

const THEME_KEY = 'gah_theme'

/** light 浅色 / dark 深色 / auto 跟随系统（文档 6：跟随 prefers-color-scheme） */
export type ThemeMode = 'light' | 'dark' | 'auto'

const MEDIA_QUERY = '(prefers-color-scheme: dark)'

function readStoredMode(): ThemeMode {
  const raw = localStorage.getItem(THEME_KEY)
  return raw === 'light' || raw === 'dark' ? raw : 'auto'
}

/**
 * 主题状态。
 *
 * 实现方式：切换 <html> 上的 .dark 类，配合 CSS 变量（styles/dark-theme.scss）。
 * 不引 Element Plus 的 useDark，是因为它的默认行为是「切换 el-dark 变量集」，
 * 而我们还需要同时驱动自己的 CSS 变量与后续 ECharts 的配色。
 *
 * auto 模式下监听系统主题变化：用户在系统里切了深色，应用要立刻跟着变，
 * 而不是等下次刷新。
 */
export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(readStoredMode())
  const systemDark = ref(
    typeof window !== 'undefined' && window.matchMedia
      ? window.matchMedia(MEDIA_QUERY).matches
      : false
  )

  /** 实际生效的深色与否（auto 时由系统决定） */
  const isDark = computed(() => (mode.value === 'auto' ? systemDark.value : mode.value === 'dark'))

  function apply() {
    const root = document.documentElement
    root.classList.toggle('dark', isDark.value)
    // 让 Element Plus 的组件（弹窗、下拉等挂到 body 的元素）也用对配色
    root.style.colorScheme = isDark.value ? 'dark' : 'light'
    // 派发自定义事件：后续模块的 ECharts 需要据此重新初始化
    // （图表要显式传 backgroundColor / textStyle.color，不会自动继承 CSS 变量）
    window.dispatchEvent(new CustomEvent('gah:theme-change', { detail: { dark: isDark.value } }))
  }

  function setMode(next: ThemeMode) {
    mode.value = next
    // auto 不写 localStorage：这样「跟随系统」不会被上一次的手动选择固化
    if (next === 'auto') {
      localStorage.removeItem(THEME_KEY)
    } else {
      localStorage.setItem(THEME_KEY, next)
    }
    apply()
  }

  /** 浅色 / 深色两态互切（UI 上的那个按钮用这个） */
  function toggle() {
    setMode(isDark.value ? 'light' : 'dark')
  }

  /** 应用启动时调用一次 */
  function init() {
    if (window.matchMedia) {
      const media = window.matchMedia(MEDIA_QUERY)
      const handler = (e: MediaQueryListEvent) => {
        systemDark.value = e.matches
        // 只有 auto 模式才需要重新应用，手动模式下系统变化不应影响用户选择
        if (mode.value === 'auto') apply()
      }
      // addEventListener 在新浏览器可用；老 Safari 只有 addListener
      if (media.addEventListener) {
        media.addEventListener('change', handler)
      }
    }
    apply()
  }

  return { mode, isDark, setMode, toggle, init }
})
