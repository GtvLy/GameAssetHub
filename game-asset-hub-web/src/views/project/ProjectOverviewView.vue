<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Calendar, Clock, Edit, Refresh, User, Box, List, Warning } from '@element-plus/icons-vue'
import { archiveProject, changeProjectStatus, fetchProjectOverview } from '@/api/project'
import { fetchAssetByCategory, fetchAssetCompletion } from '@/api/stat'
import { useChartTheme } from '@/composables/useChartTheme'
import { useEcharts } from '@/composables/useEcharts'
import { useProjectStore } from '@/stores/project'
import { PROJECT_STATUSES, roleMeta } from '@/constants'
import { assetStatusMeta } from '@/constants/asset'
import { dueState, taskColumnMeta } from '@/constants/task'
import type { AssetCategoryStatVO, AssetCompletionVO } from '@/types/stat'
import type { ProjectOverviewVO, ProjectStatusValue } from '@/types'

/**
 * 项目概览首页。
 *
 * 数据来自 GET /api/projects/{id}/overview，后端做了 10 分钟 Redis 缓存；
 * 项目信息与成员的任何写操作都会主动失效该 key，所以这里拿到的不会是"陈旧但合法"的数据。
 *
 * 资产与任务指标由模块2 / 模块3 在后端聚合后一并下发：{@code recentAssets}
 * 与 {@code myTasks} 就是接口返回的真实数据，前端不再保留任何静态占位——
 * 页面上的每一个数字与列表项都来自同一次请求，不会出现"卡片有数、列表是空的"的割裂。
 */
const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()

const projectId = computed(() => Number(route.params.projectId))
const loading = ref(false)
const overview = ref<ProjectOverviewVO | null>(null)

const project = computed(() => projectStore.currentProject)
const statusMeta = computed(
  () =>
    PROJECT_STATUSES.find((s) => s.value === (overview.value?.projectStatus || project.value?.status)) ?? {
      value: 'ACTIVE' as const,
      label: overview.value?.projectStatus || '—',
      tagType: 'info' as const
    }
)

/** 剩余天数展示：负数表示已逾期，需要明显区分出来 */
const deadline = computed(() => {
  const days = overview.value?.daysToDeadline
  if (days == null) {
    return { text: '未设置计划结束日期', type: 'info' as const, icon: Calendar }
  }
  if (days < 0) {
    return { text: `已逾期 ${Math.abs(days)} 天`, type: 'danger' as const, icon: Warning }
  }
  if (days <= 14) {
    return { text: `剩余 ${days} 天，临近截止`, type: 'warning' as const, icon: Clock }
  }
  return { text: `剩余 ${days} 天`, type: 'success' as const, icon: Clock }
})

const taskProgress = computed(() => {
  const vo = overview.value
  if (!vo || !vo.taskTotal) return 0
  return Math.round((vo.taskDone / vo.taskTotal) * 100)
})

// ==================== 模块二 / 模块三：概览页的两张速览列表 ====================
//
// 后端在同一个概览接口里把这两份数据一并下发：
//   - recentAssets 由 AssetOverviewSupport 在写缓存前补齐（项目维度，最多 6 条）；
//   - myTasks 由 TaskOverviewSupport 在缓存之外按当前登录用户单独查（最多 5 条）。
// 前端只负责渲染。两份数据都为空时给各自的空态文案，而不是一句笼统的「暂无数据」。
const recentAssets = computed(() => overview.value?.recentAssets ?? [])
const myTasks = computed(() => overview.value?.myTasks ?? [])

/** 点资产跳详情；点任务跳到看板并直接打开它的抽屉（与通知铃铛的落点一致） */
function openAsset(id: number) {
  router.push(`/project/${projectId.value}/assets/${id}`)
}

function openTask(id: number) {
  router.push({ path: `/project/${projectId.value}/board`, query: { task: id } })
}

// ==================== 模块四：统计图表 ====================
//
// 放在概览页而不是单独开一页，是因为「资产完成率」在这里本来就已经有一个
// 数字卡片了。看板、燃尽图、工作量各自有页面，而资产的两张图属于
// 「打开项目第一眼就该看到」的层面，与概览的定位一致。

const completion = ref<AssetCompletionVO | null>(null)
const categories = ref<AssetCategoryStatVO[] | null>(null)
/** 当前角色没有 STAT_READ（文档 3.3：VIEWER 不含统计）时给一句说明，而不是把页面搞成空的 */
const statForbidden = ref(false)

const completionChartEl = ref<HTMLElement | null>(null)
const categoryChartEl = ref<HTMLElement | null>(null)

const categoryRows = computed(() => categories.value ?? [])
const emptyCategoryCount = computed(() => categoryRows.value.filter((c) => c.total === 0).length)

const { chartTheme } = useChartTheme(() => {
  renderCompletion()
  renderCategory()
})

const { render: renderCompletion } = useEcharts(completionChartEl, () => {
  const vo = completion.value
  // 一个资产都没有时不画：空环形图比一句「还没有资产」更难懂
  if (!vo || vo.assetTotal === 0) {
    return null
  }
  const theme = chartTheme.value
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item', formatter: '{b}：{c} 个（{d}%）' },
    legend: { bottom: 0, textStyle: { color: theme.text } },
    series: [
      {
        name: '资产状态',
        type: 'pie',
        radius: ['50%', '70%'],
        center: ['50%', '44%'],
        itemStyle: { borderColor: theme.card, borderWidth: 2 },
        label: { color: theme.text, formatter: '{b} {c}' },
        emphasis: {
          label: { show: true, fontSize: 14, fontWeight: 'bold' }
        },
        data: vo.statusDistribution
          // 0 个的状态不画扇区（画不出来），但图例里留着也没意义——
          // 图上只出现真实存在的状态，才不会让人以为漏了几档
          .filter((item) => item.count > 0)
          .map((item) => ({
            name: item.label,
            value: item.count,
            itemStyle: { color: item.color }
          }))
      }
    ]
  }
})

const { render: renderCategory } = useEcharts(categoryChartEl, () => {
  const rows = categoryRows.value
  if (rows.length === 0) {
    return null
  }
  const theme = chartTheme.value
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['资产总数', '已通过'], textStyle: { color: theme.text }, top: 0 },
    grid: { left: 48, right: 20, top: 44, bottom: 52 },
    xAxis: {
      type: 'category',
      data: rows.map((c) => c.categoryName),
      axisLine: { lineStyle: { color: theme.border } },
      axisLabel: { color: theme.muted, interval: 0, rotate: rows.length > 6 ? 28 : 0 }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: theme.muted },
      splitLine: { lineStyle: { color: theme.border, type: 'dashed' } }
    },
    series: [
      {
        name: '资产总数',
        type: 'bar',
        barMaxWidth: 26,
        data: rows.map((c) => c.total),
        itemStyle: { color: theme.accent, borderRadius: [4, 4, 0, 0] }
      },
      {
        name: '已通过',
        type: 'bar',
        barMaxWidth: 26,
        data: rows.map((c) => c.approved),
        itemStyle: { color: theme.success, borderRadius: [4, 4, 0, 0] }
      }
    ]
  }
})

/**
 * 统计图表单独拉取。
 *
 * 与概览主数据<b>分开 try/catch</b>：VIEWER 有 PROJECT_READ 但没有 STAT_READ，
 * 若把三个请求绑在同一个 catch 里，一次 403 会让整个概览页看起来像坏了。
 * 没有统计权限时只是不画图，主数据照常显示。
 */
async function loadStats() {
  try {
    const [completionData, categoryData] = await Promise.all([
      fetchAssetCompletion(projectId.value),
      fetchAssetByCategory(projectId.value)
    ])
    completion.value = completionData
    categories.value = categoryData
    statForbidden.value = false
    renderCompletion()
    renderCategory()
  } catch {
    // 403（无统计权限）与其它失败都在这里：不弹第二次提示，拦截器已经提示过了
    statForbidden.value = true
  }
}

async function load() {
  loading.value = true
  try {
    overview.value = await fetchProjectOverview(projectId.value)
  } catch {
    /* 拦截器已提示（非成员 403 / 项目不存在 404） */
  } finally {
    loading.value = false
  }
  await loadStats()
}

onMounted(load)

async function handleStatusChange(status: ProjectStatusValue) {
  try {
    await changeProjectStatus(projectId.value, status)
    ElMessage.success('项目状态已更新')
    await load()
    // 项目状态变了，顶栏与侧栏的元信息也要跟着刷新
    await projectStore.setProjects(projectStore.projects.map((p) =>
      p.id === projectId.value ? { ...p, status, statusLabel: PROJECT_STATUSES.find((s) => s.value === status)?.label || status } : p
    ))
  } catch {
    /* 拦截器已提示 */
  }
}

async function handleArchive() {
  try {
    await ElMessageBox.confirm(
      '归档后该项目将从你的项目列表中移除（逻辑删除，数据仍保留在库中，可追溯）。确定继续吗？',
      '归档项目',
      { confirmButtonText: '归档', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await archiveProject(projectId.value)
    ElMessage.success('项目已归档')
    // 项目列表需要刷新，否则归档的项目还挂在切换器里
    const remaining = projectStore.projects.filter((p) => p.id !== projectId.value)
    projectStore.setProjects(remaining)
    await ElMessageBox.close()
  } catch {
    /* 拦截器已提示 */
  }
}
</script>

<template>
  <div v-loading="loading" class="overview">
    <!-- 项目头部 -->
    <section class="panel hero">
      <div class="hero__main">
        <div class="hero__title-row">
          <h2 class="hero__title">{{ overview?.projectName || project?.name || '项目概览' }}</h2>
          <el-tag :type="statusMeta.tagType" effect="dark" size="small">
            {{ statusMeta.label }}
          </el-tag>
          <el-tag v-if="projectStore.myRole" :type="roleMeta(projectStore.myRole).tagType" effect="plain" size="small">
            我的角色：{{ roleMeta(projectStore.myRole).label }}
          </el-tag>
        </div>

        <p class="hero__desc muted">{{ project?.description || '暂无项目简介' }}</p>

        <div class="hero__chips">
          <span class="hero__chip"><el-icon><User /></el-icon>{{ overview?.memberCount ?? 0 }} 名成员</span>
          <span class="hero__chip"><el-icon><Calendar /></el-icon>{{ project?.startDate || '未定' }} → {{ project?.planEndDate || '未定' }}</span>
          <span v-if="project?.engine" class="hero__chip mono">{{ project.engine }}</span>
          <span v-if="project?.code" class="hero__chip mono">#{{ project.code }}</span>
        </div>
      </div>

      <div class="hero__actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-dropdown v-if="projectStore.canManage" trigger="click">
          <el-button :icon="Edit">管理项目</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="s in PROJECT_STATUSES"
                :key="s.value"
                :disabled="s.value === overview?.projectStatus"
                @click="handleStatusChange(s.value)"
              >
                设为「{{ s.label }}」
              </el-dropdown-item>
              <el-dropdown-item divided @click="handleArchive">
                <span style="color: var(--danger)">归档项目</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-tag v-else type="info" effect="plain" size="small">只读视角</el-tag>
      </div>
    </section>

    <!-- 关键指标 -->
    <section class="metrics">
      <div class="metric panel">
        <div class="metric__icon" style="background: var(--accent-soft); color: var(--accent)">
          <el-icon :size="18"><User /></el-icon>
        </div>
        <div class="metric__label muted">项目成员</div>
        <div class="metric__value tabular">{{ overview?.memberCount ?? 0 }}</div>
        <div class="metric__hint muted">含已邀请待加入</div>
      </div>

      <div class="metric panel">
        <div class="metric__icon" style="background: rgba(52, 199, 123, 0.14); color: var(--success)">
          <el-icon :size="18"><Box /></el-icon>
        </div>
        <div class="metric__label muted">资产完成率</div>
        <div class="metric__value tabular">{{ (overview?.assetCompletionRate ?? 0).toFixed(1) }}%</div>
        <div class="metric__hint muted">
          {{ overview?.assetApproved ?? 0 }} 已通过 / {{ overview?.assetTotal ?? 0 }} 总数
        </div>
      </div>

      <div class="metric panel">
        <div class="metric__icon" style="background: rgba(230, 162, 60, 0.14); color: var(--warning)">
          <el-icon :size="18"><List /></el-icon>
        </div>
        <div class="metric__label muted">任务进度</div>
        <div class="metric__value tabular">{{ taskProgress }}%</div>
        <div class="metric__hint muted">
          {{ overview?.taskDone ?? 0 }} 已完成 / {{ overview?.taskTotal ?? 0 }} 总数
        </div>
      </div>

      <div class="metric panel">
        <div class="metric__icon" :style="{ background: 'var(--bg-hover)' }">
          <el-icon :size="18"><component :is="deadline.icon" /></el-icon>
        </div>
        <div class="metric__label muted">计划截止</div>
        <div class="metric__value metric__value--sm">{{ deadline.text }}</div>
        <div class="metric__hint muted">依据计划结束日期计算</div>
      </div>
    </section>

    <!-- 模块四：统计图表 -->
    <el-alert
      v-if="statForbidden"
      type="info"
      :closable="false"
      show-icon
      class="overview__notice"
      title="当前角色没有统计查看权限"
      description="文档 3.3 的权限矩阵里，外部协作者（VIEWER）的「统计查看」为否。概览的其它指标不受影响。"
    />

    <section v-if="!statForbidden" class="stats">
      <div class="panel chart-panel">
        <div class="panel-title">
          <span>资产完成率与状态分布</span>
          <el-tag v-if="completion" size="small" type="info" effect="plain">
            完成率 {{ completion.completionRate.toFixed(1) }}%
          </el-tag>
        </div>
        <div ref="completionChartEl" class="chart chart--pie"></div>
        <el-empty
          v-if="completion && completion.assetTotal === 0"
          :image-size="70"
          description="该项目还没有资产"
        />
        <p v-if="completion" class="chart-foot muted">{{ completion.completionDefinition }}</p>
      </div>

      <div class="panel chart-panel">
        <div class="panel-title">
          <span>各分类资产数量与完成情况</span>
          <el-tag size="small" type="info" effect="plain">按分类（不做层级汇总）</el-tag>
        </div>
        <div ref="categoryChartEl" class="chart"></div>
        <el-empty
          v-if="categories && categories.length === 0"
          :image-size="70"
          description="该项目还没有资产分类"
        />
        <p v-if="categoryRows.length" class="chart-foot muted">
          共 {{ categoryRows.length }} 个分类，其中 {{ emptyCategoryCount }} 个还没有资产；
          完成率 = 该分类已通过 ÷ 该分类总数。
        </p>
      </div>
    </section>

    <!-- 详情面板：最近资产（模块2）与我的待办（模块3），数据都来自同一个概览接口 -->
    <section class="detail">
      <div class="panel">
        <div class="panel-title">
          <span>最近更新的资产</span>
          <el-tag v-if="recentAssets.length" size="small" type="info" effect="plain">
            最近 {{ recentAssets.length }} 条
          </el-tag>
        </div>

        <div class="panel-body">
          <ul v-if="recentAssets.length" class="mini-list">
            <li
              v-for="asset in recentAssets"
              :key="asset.id"
              class="mini-list__item"
              @click="openAsset(asset.id)"
            >
              <span class="mini-list__thumb">
                <img v-if="asset.thumbnailUrl" :src="asset.thumbnailUrl" :alt="asset.name" />
                <span v-else class="mini-list__thumb-ph">{{ asset.name.slice(0, 1) }}</span>
              </span>
              <div class="mini-list__main">
                <div class="mini-list__title">{{ asset.name }}</div>
                <div class="mini-list__meta muted">
                  <span class="mono">{{ asset.assetNo }}</span>
                  <span v-if="asset.categoryName">· {{ asset.categoryName }}</span>
                  <span v-if="asset.ownerName">· {{ asset.ownerName }}</span>
                </div>
              </div>
              <el-tag size="small" :type="assetStatusMeta(asset.status).tagType" effect="plain">
                {{ assetStatusMeta(asset.status).label }}
              </el-tag>
            </li>
          </ul>
          <el-empty v-else :image-size="80" description="该项目还没有资产" />
        </div>
      </div>

      <div class="panel">
        <div class="panel-title">
          <span>我的待办任务</span>
          <el-tag v-if="myTasks.length" size="small" type="info" effect="plain">
            {{ myTasks.length }} 条未完成
          </el-tag>
        </div>
        <div class="panel-body">
          <ul v-if="myTasks.length" class="mini-list">
            <li
              v-for="task in myTasks"
              :key="task.id"
              class="mini-list__item"
              @click="openTask(task.id)"
            >
              <span
                class="mini-list__bar"
                :style="{ background: taskColumnMeta(task.columnStatus).color }"
              />
              <div class="mini-list__main">
                <div class="mini-list__title">{{ task.title }}</div>
                <div class="mini-list__meta muted">
                  <span>{{ task.columnLabel }}</span>
                  <span v-if="task.priorityLabel">· {{ task.priorityLabel }}</span>
                  <span v-if="task.assetName">· {{ task.assetName }}</span>
                </div>
              </div>
              <span class="mini-list__due" :class="`is-${dueState(task.dueDate, task.columnStatus).level}`">
                {{ dueState(task.dueDate, task.columnStatus).text }}
              </span>
            </li>
          </ul>
          <el-empty v-else :image-size="80" description="当前没有指派给你的未完成任务" />
        </div>
      </div>
    </section>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="overview__notice"
      title="关于本页指标的说明"
      description="资产指标与「最近更新的资产」由模块二（美术资产管理）聚合，任务指标与「我的待办任务」由模块三（任务看板）聚合，本页只负责展示——两者都随本接口一起下发。下方的两张统计图来自模块四（统计与进度可视化）：完成率 = 已通过审核的资产数 ÷ 资产总数，待审核与已废弃计入分母、不计入分子；没有统计权限的角色（文档 3.3 权限矩阵里 VIEWER 的「统计查看」为否）打开本页时只会少这两张图，其它内容不受影响。"
    />
  </div>
</template>

<style scoped>
.overview {
  display: grid;
  gap: 18px;
}

/* ---------- 头部 ---------- */

.hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
  padding: 22px 24px;
  background:
    radial-gradient(760px 260px at 0% 0%, var(--accent-soft), transparent 68%),
    var(--bg-card);
}

.hero__title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.hero__title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.3px;
}

.hero__desc {
  margin: 10px 0 0;
  font-size: 13.5px;
  line-height: 1.75;
  max-width: 720px;
}

.hero__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 15px;
}

.hero__chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-panel);
  font-size: 12px;
  color: var(--text-muted);
}

.hero__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ---------- 指标卡 ---------- */

.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(208px, 1fr));
  gap: 16px;
}

.metric {
  padding: 18px 20px;
}

.metric__icon {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  margin-bottom: 12px;
}

.metric__label {
  font-size: 12.5px;
}

.metric__value {
  margin-top: 2px;
  font-size: 27px;
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: -0.6px;
}

.metric__value--sm {
  font-size: 17px;
  letter-spacing: 0;
}

.metric__hint {
  margin-top: 4px;
  font-size: 11.5px;
}

/* ---------- 模块四：统计图表 ---------- */

.stats {
  display: grid;
  grid-template-columns: minmax(300px, 1fr) minmax(360px, 1.3fr);
  gap: 16px;
}

@media (max-width: 1080px) {
  .stats {
    grid-template-columns: 1fr;
  }
}

.chart-panel {
  padding: 16px 18px 10px;
}

.chart {
  width: 100%;
  height: 300px;
}

.chart--pie {
  height: 300px;
}

.chart-foot {
  margin: 4px 0 0;
  font-size: 11.5px;
  line-height: 1.8;
}

/* ---------- 详情 ---------- */

.detail {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 16px;
}

.mini-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 2px;
}

.mini-list__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.mini-list__item:hover {
  background: var(--bg-hover);
}

/* 缩略图位：无图时用资产名首字兜底，保持行高一致 */
.mini-list__thumb {
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: 8px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-hover);
  border: 1px solid var(--border);
}

.mini-list__thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.mini-list__thumb-ph {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-muted);
}

/* 任务行的列色条：与看板卡片同一套列配色，扫一眼就能对上 */
.mini-list__bar {
  width: 3px;
  height: 30px;
  flex: none;
  border-radius: 2px;
}

.mini-list__main {
  flex: 1;
  min-width: 0;
}

.mini-list__title {
  font-size: 13.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mini-list__meta {
  margin-top: 2px;
  font-size: 11.5px;
  display: flex;
  gap: 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mini-list__due {
  flex: none;
  font-size: 11.5px;
  color: var(--text-muted);
}

.mini-list__due.is-overdue {
  color: var(--danger);
  font-weight: 600;
}

.mini-list__due.is-soon {
  color: var(--warning);
}

.overview__notice {
  align-items: flex-start;
}
</style>
