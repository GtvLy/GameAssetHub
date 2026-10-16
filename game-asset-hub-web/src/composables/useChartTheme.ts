import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/**
 * ECharts 配色与主题。
 *
 * 图表不会自动继承 CSS 变量：`var(--text-primary)` 是浏览器给 DOM 用的，
 * ECharts 拿到的是一个普通的 JS 字符串。所以必须有人把当前生效的变量值
 * **读出来**再交给图表。这个组合式函数干的就是这件事。
 *
 * 为什么要在运行时读、而不是在代码里写两份（浅色一份、深色一份）：
 * 配色一旦有两份，改主题色时必然漏改一处，表现是「深色下某条线看不见」——
 * 而那是最难被当场发现、又最容易被当成 bug 的一类问题。
 *
 * 主题切换时 `stores/theme.ts` 会派发 `gah:theme-change` 事件，
 * 这里监听它并让调用方重绘（事件名由主题 store 定义，是两边唯一的契约）。
 */
export interface ChartTheme {
  /** 主文字色：坐标轴标签、图例 */
  text: string
  /** 次要文字色：坐标轴名称 */
  muted: string
  /** 分隔线色：网格线、坐标轴线 */
  border: string
  /** 卡片背景：饼图描边、tooltip 背景 */
  card: string
  accent: string
  success: string
  warning: string
  danger: string
  /** 类目配色的固定顺序，保证同一份数据每次上色一致 */
  palette: string[]
}

/** 从 :root 上读一个 CSS 变量，取不到就用兜底色（首屏样式未加载时不会画出透明图） */
function readVar(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

export function readChartTheme(): ChartTheme {
  const accent = readVar('--accent', '#5b8cff')
  const success = readVar('--success', '#34c77b')
  const warning = readVar('--warning', '#e6a23c')
  const danger = readVar('--danger', '#f56c6c')
  return {
    text: readVar('--text-primary', '#1f2329'),
    muted: readVar('--text-muted', '#8a9099'),
    border: readVar('--border', '#e4e7ed'),
    card: readVar('--bg-card', '#ffffff'),
    accent,
    success,
    warning,
    danger,
    palette: [accent, success, warning, danger, '#909399', '#8f6fff', '#13c2c2']
  }
}

/**
 * 提供一份响应主题切换的图表配色。
 *
 * @param onThemeChange 主题变化后的回调（通常是重绘图表）
 */
export function useChartTheme(onThemeChange?: () => void) {
  const chartTheme: Ref<ChartTheme> = ref(readChartTheme())

  const handler = () => {
    chartTheme.value = readChartTheme()
    onThemeChange?.()
  }

  onMounted(() => window.addEventListener('gah:theme-change', handler))
  onBeforeUnmount(() => window.removeEventListener('gah:theme-change', handler))

  return { chartTheme, refreshTheme: handler }
}
