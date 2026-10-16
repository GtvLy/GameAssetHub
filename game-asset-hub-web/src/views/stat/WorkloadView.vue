<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, Refresh, Warning } from '@element-plus/icons-vue'
import { exportStatReport, fetchWorkload } from '@/api/stat'
import { useEcharts } from '@/composables/useEcharts'
import { useChartTheme } from '@/composables/useChartTheme'
import type { WorkloadItemVO, WorkloadVO } from '@/types/stat'

/**
 * 成员工作量（文档 4.4 / 5.7 / 7.4）。
 *
 * 三块内容：
 *   1. 横向柱状图对比各成员的任务数（另用一层浅色条标出「已完成」）；
 *   2. 角色分布环形图（谁在承担这个项目）；
 *   3. 明细表（任务数 / 已完成 / 完成率 / 工时）与导出按钮。
 *
 * 负载均衡度直接显示在指标卡上，并在 < 0.5 时把后端的提示文案顶出来——
 * 这个数字只有配上「所以该怎么办」才有意义，光给一个 0.32 没人会行动。
 */
const route = useRoute()
const projectId = computed(() => Number(route.params.projectId))

const loading = ref(false)
const exporting = ref(false)
const workload = ref<WorkloadVO | null>(null)
const chartEl = ref<HTMLElement | null>(null)
const roleChartEl = ref<HTMLElement | null>(null)

/** 均衡度的展示：0.85 以上算好，0.5 以下就是后端给的那条警告 */
const balanceMeta = computed(() => {
  const value = workload.value?.balanceIndex ?? 1
  if (value >= 0.85) return { type: 'success' as const, text: '分配均衡' }
  if (value >= 0.5) return { type: 'warning' as const, text: '轻度不均' }
  return { type: 'danger' as const, text: '分配不均' }
})

const { chartTheme } = useChartTheme(() => {
  render()
  renderRoleChart()
})

/** 横向柱状图：任务数 + 已完成 */
const { render } = useEcharts(chartEl, () => {
  const members = workload.value?.members ?? []
  if (members.length === 0) {
    return null
  }
  const theme = chartTheme.value
  // 横向柱状图的类目轴是「从下往上」的，反过来排一遍才符合阅读顺序（人多的在上面）
  const rows = [...members].reverse()

  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['任务数', '已完成'], textStyle: { color: theme.text }, top: 0 },
    grid: { left: 84, right: 32, top: 44, bottom: 24 },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: theme.muted },
      splitLine: { lineStyle: { color: theme.border, type: 'dashed' } }
    },
    yAxis: {
      type: 'category',
      data: rows.map((m) => m.nickname || `#${m.userId}`),
      axisLine: { lineStyle: { color: theme.border } },
      axisLabel: { color: theme.text }
    },
    series: [
      {
        name: '任务数',
        type: 'bar',
        data: rows.map((m) => m.taskTotal),
        barMaxWidth: 18,
        itemStyle: { color: theme.accent, borderRadius: [0, 4, 4, 0] },
        label: { show: true, position: 'right', color: theme.muted }
      },
      {
        name: '已完成',
        type: 'bar',
        data: rows.map((m) => m.taskDone),
        barMaxWidth: 18,
        itemStyle: { color: theme.success, borderRadius: [0, 4, 4, 0] }
      }
    ]
  }
})

/** 角色分布环形图 */
const { render: renderRoleChart } = useEcharts(roleChartEl, () => {
  const members = workload.value?.members ?? []
  if (members.length === 0) {
    return null
  }
  const theme = chartTheme.value
  // 同一角色的成员合并成一片：环形图看的是「这个项目由谁在承担」，
  // 按人分片会变成一张「谁是谁」的图，与它的用途不符
  const buckets = new Map<string, number>()
  for (const m of members) {
    const label = m.roleLabel || m.role || '未知'
    buckets.set(label, (buckets.get(label) ?? 0) + 1)
  }

  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item', formatter: '{b}：{c} 人（{d}%）' },
    legend: { bottom: 0, textStyle: { color: theme.text } },
    series: [
      {
        name: '角色分布',
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: theme.card, borderWidth: 2 },
        label: { color: theme.text, formatter: '{b} {c} 人' },
        data: [...buckets.entries()].map(([name, value], index) => ({
          name,
          value,
          itemStyle: { color: theme.palette[index % theme.palette.length] }
        }))
      }
    ]
  }
})

/** 表格里的工时列：null 与 0 要区分开吗？这里统一显示 0.0，避免出现两行看起来一样 */
function hours(value: number | null | undefined): string {
  return (value ?? 0).toFixed(1)
}

function rowClass({ row }: { row: WorkloadItemVO }) {
  return row.belowAverage ? 'row--below-average' : ''
}

async function load() {
  loading.value = true
  try {
    workload.value = await fetchWorkload(projectId.value)
    render()
    renderRoleChart()
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

/**
 * 导出统计报表。
 *
 * **不能写成 `window.open(url)`**：导出接口要求
 * `Authorization: Bearer` 头，浏览器原生导航带不上，必然 401，
 * 而且是在新标签页里静默失败。这里用 axios 的 blob 模式拿回字节，
 * 再用一个临时 <a download> 落盘——与资产详情页下载版本的写法一致。
 */
async function handleExport() {
  exporting.value = true
  try {
    const blob = await exportStatReport(projectId.value)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${projectId.value}-统计报表.xlsx`
    link.style.display = 'none'
    document.body.appendChild(link)
    link.click()
    link.remove()
    // 必须释放；但不能在 click() 之后同步释放——Firefox 是异步去读这个
    // blob URL 的，同步 revoke 会把还没开始的下载掐断，表现为「点了没反应」
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    ElMessage.success('报表已开始下载')
  } catch {
    /* 拦截器已提示（无统计权限 403 等） */
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="workload">
    <section class="panel head">
      <div>
        <h2 class="head__title">成员工作量</h2>
        <p class="head__desc muted">
          按项目内的 ACTIVE 成员统计任务、工时与资产产出（被邀请但未加入的成员不计入，
          否则人均任务量会被摊薄，真正的不均衡反而看不出来）。
        </p>
      </div>
      <div class="head__actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Download" :loading="exporting" @click="handleExport">
          导出报表
        </el-button>
      </div>
    </section>

    <section v-if="workload" class="metrics">
      <div class="metric panel">
        <div class="metric__label muted">成员数</div>
        <div class="metric__value tabular">{{ workload.memberCount }}</div>
        <div class="metric__hint muted">项目内已加入的成员</div>
      </div>
      <div class="metric panel">
        <div class="metric__label muted">任务数合计</div>
        <div class="metric__value tabular">{{ workload.taskTotalSum }}</div>
        <div class="metric__hint muted tabular">人均 {{ workload.averageTask }}</div>
      </div>
      <div class="metric panel">
        <div class="metric__label muted">负载均衡度</div>
        <div class="metric__value tabular">{{ workload.balanceIndex }}</div>
        <div class="metric__hint muted tabular">
          标准差 {{ workload.stdDevTask }} · 1 − σ/μ
        </div>
      </div>
      <div class="metric panel">
        <div class="metric__label muted">分配评价</div>
        <div class="metric__value metric__value--sm">
          <el-tag :type="balanceMeta.type" effect="dark" size="small">{{ balanceMeta.text }}</el-tag>
        </div>
        <div class="metric__hint muted">均衡度 &lt; 0.5 视为不均</div>
      </div>
    </section>

    <el-alert
      v-if="workload?.balanceWarning"
      type="warning"
      :closable="false"
      show-icon
      class="workload__warning"
    >
      <template #title>
        <el-icon><Warning /></el-icon>
        <span>{{ workload.hint }}</span>
      </template>
    </el-alert>

    <section class="charts">
      <div class="panel chart-panel">
        <div class="panel-title">成员任务数对比</div>
        <div ref="chartEl" class="chart"></div>
      </div>
      <div class="panel chart-panel">
        <div class="panel-title">角色分布</div>
        <div ref="roleChartEl" class="chart chart--pie"></div>
      </div>
    </section>

    <section class="panel">
      <div class="panel-title">
        <span>明细</span>
        <el-tag size="small" type="info" effect="plain">
          低于平均的成员已标黄
        </el-tag>
      </div>
      <el-table
        :data="workload?.members ?? []"
        :row-class-name="rowClass"
        size="small"
        class="workload__table"
      >
        <el-table-column prop="nickname" label="成员" min-width="120" />
        <el-table-column prop="roleLabel" label="角色" width="110" />
        <el-table-column prop="taskTotal" label="任务数" width="90" align="right" />
        <el-table-column prop="taskDone" label="已完成" width="90" align="right" />
        <el-table-column label="完成率" width="100" align="right">
          <template #default="{ row }">{{ row.taskCompletionRate.toFixed(1) }}%</template>
        </el-table-column>
        <el-table-column prop="points" label="故事点" width="90" align="right" />
        <el-table-column label="预估工时" width="100" align="right">
          <template #default="{ row }">{{ hours(row.estimateHours) }}</template>
        </el-table-column>
        <el-table-column label="实际工时" width="100" align="right">
          <template #default="{ row }">{{ hours(row.actualHours) }}</template>
        </el-table-column>
        <el-table-column prop="versionUploaded" label="上传版本" width="100" align="right" />
        <el-table-column prop="assetApproved" label="通过资产" width="100" align="right" />
        <template #empty>
          <el-empty :image-size="70" description="项目里还没有已加入的成员" />
        </template>
      </el-table>
    </section>

    <el-alert v-if="workload" type="info" :closable="false" show-icon>
      <template #title>口径说明</template>
      <p class="workload__definition">{{ workload.balanceDefinition }}</p>
      <p class="workload__definition">
        标准差取总体标准差（分母为成员数 n）；全员任务数为 0 时按「完全均衡」取 1.0，
        不做除法。均衡度可以为负，表示离散程度已经超过平均值本身。
      </p>
    </el-alert>
  </div>
</template>

<style scoped>
.workload {
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

.head__actions {
  display: flex;
  gap: 8px;
  flex: none;
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

.metric__hint {
  margin-top: 6px;
  font-size: 11.5px;
}

.charts {
  display: grid;
  grid-template-columns: minmax(320px, 1.4fr) minmax(280px, 1fr);
  gap: 16px;
}

@media (max-width: 1080px) {
  .charts {
    grid-template-columns: 1fr;
  }
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
  height: 320px;
}

.chart--pie {
  height: 320px;
}

.workload__table {
  width: 100%;
}

.workload__definition {
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.8;
}
</style>

<style>
/* 表格行样式必须全局：el-table 的行节点不在本组件的 scoped 作用域里 */
.workload .row--below-average {
  --el-table-tr-bg-color: rgba(230, 162, 60, 0.1);
}
</style>
