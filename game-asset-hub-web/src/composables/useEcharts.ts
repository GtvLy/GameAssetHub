import * as echarts from 'echarts'
import { nextTick, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import { useThemeStore } from '@/stores/theme'

/**
 * 把 ECharts 的生命周期收在一处。
 *
 * <h3>为什么用 SVG 渲染器而不是默认的 canvas</h3>
 * `echarts.init(el, null, { renderer: 'svg' })`。
 * canvas 画出来的东西在 DOM 里只有一个 `<canvas>` 节点，
 * 无头浏览器 `--dump-dom` 看到的是空的——渲染冒烟脚本就没法断言
 * 「图上真的有数据」。SVG 渲染器会把每个点、每条线、每个文字标签
 * 都变成真实节点，于是 `verify-p03-m4-ui.ps1` 能直接断言
 * 「坐标轴上出现了日期」「图例里出现了成员昵称」。
 * 代价是数据量极大时性能不如 canvas，而本项目的图表最多几百个点。
 *
 * <h3>为什么不在这里写 option</h3>
 * option 由调用方通过 `build()` 每次现算：主题切换、数据刷新之后
 * 都要用**最新的**配色与数据重绘，缓存一份 option 只会得到一个
 * 「切换主题后图还是旧颜色」的 bug。
 *
 * @param el    图表容器
 * @param build 返回 ECharts option 的函数；返回 null 表示当前没有数据可画
 */
export function useEcharts(
  el: Ref<HTMLElement | null>,
  build: () => echarts.EChartsOption | null
) {
  const themeStore = useThemeStore()
  let instance: echarts.ECharts | null = null

  /** 是否已经成功初始化（供视图决定要不要显示空态） */
  const chartReady = ref(false)

  function render() {
    if (!el.value) return
    const option = build()
    if (!option) {
      // 没有数据时不初始化：容器是空的，断言「图上出现了 X」自然不成立，
      // 比画一张空图更好排查
      return
    }
    if (!instance) {
      instance = echarts.init(el.value, null, { renderer: 'svg' })
    }
    instance.setOption(option, true)
    chartReady.value = true
  }

  function resize() {
    instance?.resize()
  }

  onMounted(() => {
    render()
    window.addEventListener('resize', resize)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', resize)
    instance?.dispose()
    instance = null
    chartReady.value = false
  })

  // 主题切换：等 DOM 上的 .dark 类生效、CSS 变量更新后再重绘，
  // 否则读到的还是切换前的那组颜色
  watch(
    () => themeStore.isDark,
    () => {
      void nextTick(render)
    }
  )

  return { render, resize, chartReady }
}
