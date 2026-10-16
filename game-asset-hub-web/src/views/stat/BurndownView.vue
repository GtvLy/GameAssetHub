<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Refresh, TrendCharts, Timer, Warning } from '@element-plus/icons-vue'
import { fetchBurndown } from '@/api/stat'
import { useEcharts } from '@/composables/useEcharts'
import { useChartTheme } from '@/composables/useChartTheme'
import type { BurndownVO } from '@/types/stat'

/**
 * 燃尽图（文档 5.6 / 7.4）。
 *
 * 两条 series：
 *   理想线 —— 从计划开始到计划结束**整段区间**的线性递减，虚线、无数据点标记；
 *   实际线 —— 只画到 min(计划结束日, 今天)，数据由后端用 task_status_log 逐日回放。
 *
 * 横轴取「理想线的日期」作为类目轴：理想线覆盖的是计划全区间，
 * 实际线是它的子集，所以拿它当轴天然对齐，实际线到今天为止自然断掉
 * （缺失的日期补 null，ECharts 的折线就停在那里，不会连一条假的线穿到未来）。
 *
 * 「今天」的位置画一条 markLine：文档 7.4 把 markLine 写在了 option 顶层，
 * 而 ECharts 里 markLine 属于 series——挂在顶层不会生效，
 * 图能出来但那条竖线永远不出现，属于「看着像没配」。这里挂在实际线上。
 */
const route = useRoute()
const projectId = computed(() => Number(route.params.projectId))

const loading = ref(false)
const burndown = ref<BurndownVO | null>(null)
const chartEl = ref<HTMLElement | null>(null)

/** 进度判定的展示元数据：颜色与文案都由后端给的状态码决定 */
const statusMeta = computed(() => {
  const status = burndown.value?.scheduleStatus
  if (status === 'AHEAD') return { type: 'success' as const, icon: TrendCharts }
  if (status === 'BEHIND') return { type: 'danger' as const, icon: Warning }
  return { type: 'warning' as const, icon: Timer }
})

/** 已消耗天数在总天数里的占比，用于进度条 */
const elapsedPercent = computed(() => {
  const vo = burndown.value
  if (!vo || !vo.totalDays) return 0
  return Math.min(100, Math.round((vo.elapsedDays / vo.totalDays) * 100))
})

const { chartTheme, refreshTheme } = useChartTheme(() => render())

const { render, chartReady } = useEcharts(chartEl, () => {
  const vo = burndown.value
  if (!vo || vo.idealLine.length === 0) {
    return null
  }
  const theme = chartTheme.value

  // 类目轴 = 理想线的全部日期（计划全区间）
  const dates = vo.idealLine.map((p) => p.date)
  const ideal = vo.idealLine.map((p) => p.points)

  // 实际线按日期对齐到同一个轴上，今天之后的位置为 null
  const actualMap = new Map(vo.actualLine.map((p) => [p.date, p.points]))
  const actual = dates.map((d) => (actualMap.has(d) ? (actualMap.get(d) as number) : null))

  return {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value: unknown) => (value == null ? '—' : `${value} 点`)
    },
    legend: {
      data: ['理想线', '实际线'],
      textStyle: { color: theme.text },
      top: 0
    },
    grid: { left: 56, right: 24, top: 44, bottom: 56 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: dates,
      axisLine: { lineStyle: { color: theme.border } },
      axisLabel: {
        color: theme.muted,
        // 日期只显示 MM-DD：完整日期太长，122 个刻度会糊成一团
        formatter: (value: string) => value.slice(5),
        hideOverlap: true
      }
    },
    yAxis: {
      type: 'value',
      name: '剩余故事点',
      nameTextStyle: { color: theme.muted },
      axisLine: { show: false },
      axisLabel: { color: theme.muted },
      splitLine: { lineStyle: { color: theme.border, type: 'dashed' } }
    },
    series: [
      {
        name: '理想线',
        type: 'line',
        data: ideal,
        symbol: 'none',
        lineStyle: { type: 'dashed', width: 1.5, color: theme.muted },
        itemStyle: { color: theme.muted }
      },
      {
        name: '实际线',
        type: 'line',
        data: actual,
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        itemStyle: { color: theme.accent },
        lineStyle: { width: 2.5, color: theme.accent },
        areaStyle: { opacity: 0.15, color: theme.accent },
        // 实际线只画到今天，未来的位置是 null，这里不连线也不插值
        connectNulls: false,
        markLine: {
          silent: true,
          symbol: 'none',
          data: [{ xAxis: vo.today, label: { formatter: '今天' } }],
          lineStyle: { color: theme.warning, type: 'dotted', width: 1.5 },
          label: { color: theme.warning, position: 'insideEndTop' }
        }
      }
    ]
  }
})

async function load() {
  loading.value = true
  try {
    burndown.value = await fetchBurndown(projectId.value)
    // 数据到了之后重绘：图表的生命周期在组合式函数里，视图只负责「有新数据了」
    render()
    refreshTheme()
  } catch {
    /* 拦截器已提示（非成员 403 / 无统计权限 403） */
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="burndown">
    <section class="panel head">
      <div>
        <h2 class="head__title">燃尽图</h2>
        <p class="head__desc muted">
          理想线是计划，实际线由后端按 <span class="mono">task_status_log</span> 逐日回放——
          把已完成的任务拖回待办，<strong>不会</strong>改写已经过去的那几周。
        </p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </section>

    <section v-if="burndown" class="metrics">
      <div class="metric panel">
        <div class="metric__label muted">总故事点</div>
        <div class="metric__value tabular">{{ burndown.totalPoints }}</div>
        <div class="metric__hint muted tabular">
          {{ burndown.startDate }} → {{ burndown.endDate }}
        </div>
      </div>
      <div class="metric panel">
        <div class="metric__label muted">当前剩余</div>
        <div class="metric__value tabular">{{ burndown.currentPoints }}</div>
        <div class="metric__hint muted tabular">理想线今天应为 {{ burndown.idealPointsToday }}</div>
      </div>
      <div class="metric panel">
        <div class="metric__label muted">已消耗天数</div>
        <div class="metric__value tabular">
          {{ burndown.elapsedDays }}<span class="metric__unit">/ {{ burndown.totalDays }}</span>
        </div>
        <el-progress
          :percentage="elapsedPercent"
          :show-text="false"
          :stroke-width="6"
          class="metric__bar"
        />
      </div>
      <div class="metric panel">
        <div class="metric__label muted">进度判定</div>
        <div class="metric__value metric__value--sm">
          <el-tag :type="statusMeta.type" effect="dark" size="small">
            {{ burndown.scheduleStatusLabel }}
          </el-tag>
        </div>
        <div class="metric__hint muted">
          {{ burndown.overduePlan ? '已过计划结束日' : '计划进行中' }}
        </div>
      </div>
    </section>

    <section class="panel chart-panel">
      <div class="panel-title">
        <span>剩余故事点趋势</span>
        <el-tag v-if="burndown && burndown.actualEndDate" size="small" type="info" effect="plain">
          实际线只到 {{ burndown.actualEndDate }}
        </el-tag>
      </div>
      <div ref="chartEl" class="chart"></div>
      <el-empty v-if="burndown && !chartReady" :image-size="70" description="计划日期不完整，暂时画不出燃尽图" />
    </section>

    <el-alert v-if="burndown" type="info" :closable="false" show-icon class="burndown__notice">
      <template #title>口径说明</template>
      <ul class="burndown__rules">
        <li>理想线：{{ burndown.startDate }} ~ {{ burndown.endDate }} 全区间逐日线性递减，含非工作日。</li>
        <li>
          实际线：只画到 min(计划结束日, 今天)={{ burndown.actualEndDate || '（尚未开始）' }}；
          某一天是否「已完成」只看当天是否已有 <span class="mono">to_status=DONE</span> 的日志，
          与任务当前在哪一列无关。
        </li>
        <li>该日期之后才创建的任务不计入当天的剩余故事点（否则历史曲线会凭空多出故事点）。</li>
        <li>今天的点位即以今天零点为界，回放口径与上面一致。</li>
      </ul>
    </el-alert>
  </div>
</template>

<style scoped>
.burndown {
  display: grid;
  gap: 16px;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 20px;
}

.head__title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
}

.head__desc {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.75;
  max-width: 720px;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 14px;
}

.metric {
  padding: 16px 18px;
}

.metric__label {
  font-size: 12.5px;
}

.metric__value {
  margin-top: 4px;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.5px;
  line-height: 1.3;
}

.metric__value--sm {
  font-size: 16px;
  letter-spacing: 0;
  margin-top: 8px;
}

.metric__unit {
  margin-left: 4px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-muted);
}

.metric__hint {
  margin-top: 6px;
  font-size: 11.5px;
}

.metric__bar {
  margin-top: 10px;
}

.chart-panel {
  padding: 16px 18px 8px;
}

.panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13.5px;
  font-weight: 600;
  margin-bottom: 8px;
}

.chart {
  width: 100%;
  height: 340px;
}

.burndown__rules {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.9;
}
</style>
