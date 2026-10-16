<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Calendar, Document, Refresh } from '@element-plus/icons-vue'
import { fetchMonthlyReport, fetchWeeklyReport } from '@/api/stat'
import type { ReportVO } from '@/types/stat'

/**
 * 项目周报 / 月报（文档 5.10）。
 *
 * 四段内容：任务进展 → 资产进展 → 成员产出 → 自动生成的结论与风险提示。
 * 页面刻意把「结论文本」放在最上面：报表是给人做判断用的，
 * 一堆数字排在那里、让读者自己去得出结论，等于把这一步推回给了读者。
 *
 * 顶部显示快照是否落库（`snapshotSaved`）：没有落库时报表依然可用，
 * 但「这份报表将来还查得到吗」这个问题的答案就变了，必须让用户看得见。
 */
const route = useRoute()
const projectId = computed(() => Number(route.params.projectId))

const loading = ref(false)
const report = ref<ReportVO | null>(null)
/** WEEK / MONTH，对应文档里的周报与月报两个接口 */
const type = ref<'WEEK' | 'MONTH'>('WEEK')
/** 周报的起始日 / 月报的月份，为空表示取默认（本周一 / 当前月） */
const weekStart = ref<string>('')
const month = ref<string>('')

async function load() {
  loading.value = true
  try {
    report.value =
      type.value === 'WEEK'
        ? await fetchWeeklyReport(projectId.value, weekStart.value || undefined)
        : await fetchMonthlyReport(projectId.value, month.value || undefined)
  } catch {
    /* 拦截器已提示（weekStart/month 格式非法会拿到 400） */
  } finally {
    loading.value = false
  }
}

function switchType(next: 'WEEK' | 'MONTH') {
  type.value = next
  void load()
}

/** 「再点一次生成」= 幂等验证：同一周期重复生成不会报错，只会刷新快照 */
async function regenerate() {
  await load()
}

function hours(value: number | null | undefined): string {
  return (value ?? 0).toFixed(1)
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="report">
    <section class="panel head">
      <div>
        <h2 class="head__title">项目周报 / 月报</h2>
        <p class="head__desc muted">
          报表由服务端按周期聚合，并自动生成结论文本与风险提示；
          同一周期重复生成是幂等的，快照会被刷新而不是报错。
        </p>
      </div>
      <div class="head__actions">
        <el-radio-group :model-value="type" size="small" @change="switchType($event as 'WEEK' | 'MONTH')">
          <el-radio-button value="WEEK">周报</el-radio-button>
          <el-radio-button value="MONTH">月报</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" :loading="loading" @click="regenerate">重新生成</el-button>
      </div>
    </section>

    <section class="panel filters">
      <template v-if="type === 'WEEK'">
        <span class="filters__label muted">周期起始日</span>
        <el-date-picker
          v-model="weekStart"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="默认本周一"
          size="default"
          @change="load"
        />
      </template>
      <template v-else>
        <span class="filters__label muted">月份</span>
        <el-date-picker
          v-model="month"
          type="month"
          value-format="YYYY-MM"
          placeholder="默认当前月"
          size="default"
          @change="load"
        />
      </template>
      <el-tag v-if="report" size="small" type="info" effect="plain">
        <el-icon><Calendar /></el-icon>
        {{ report.reportTypeLabel }}：{{ report.period }}
      </el-tag>
      <el-tag
        v-if="report"
        size="small"
        :type="report.snapshotSaved ? 'success' : 'warning'"
        effect="plain"
      >
        <el-icon><Document /></el-icon>
        {{ report.snapshotSaved ? '快照已落库' : '快照未落库（报表仍可用）' }}
      </el-tag>
    </section>

    <el-alert
      v-if="report"
      :type="report.risks.length > 0 ? 'warning' : 'success'"
      :closable="false"
      show-icon
      class="report__summary"
    >
      <template #title>结论</template>
      <p class="report__summary-text">{{ report.summary }}</p>
      <ul v-if="report.risks.length" class="report__risks">
        <li v-for="(risk, index) in report.risks" :key="index">{{ risk }}</li>
      </ul>
    </el-alert>

    <section v-if="report" class="metrics">
      <div class="metric panel">
        <div class="metric__label muted">本周期新增任务</div>
        <div class="metric__value tabular">{{ report.taskCreated }}</div>
        <div class="metric__hint muted tabular">完成 {{ report.taskCompleted }} 个</div>
      </div>
      <div class="metric panel">
        <div class="metric__label muted">剩余故事点</div>
        <div class="metric__value tabular">{{ report.remainingPoints }}</div>
        <div class="metric__hint muted tabular">
          进度判定：{{ report.burndownStatusLabel }}
        </div>
      </div>
      <div class="metric panel">
        <div class="metric__label muted">逾期任务</div>
        <div class="metric__value tabular">{{ report.taskOverdue }}</div>
        <div class="metric__hint muted tabular">
          任务总数 {{ report.taskTotal }} · 已完成 {{ report.taskDone }}
        </div>
      </div>
      <div class="metric panel">
        <div class="metric__label muted">资产进展</div>
        <div class="metric__value tabular">{{ report.assetAdded }}</div>
        <div class="metric__hint muted tabular">
          通过 {{ report.assetApproved }} · 待审核存量 {{ report.assetPendingReview }}
        </div>
      </div>
    </section>

    <section class="panel">
      <div class="panel-title">
        <span>成员产出</span>
        <el-tag size="small" type="info" effect="plain">
          生成时间 {{ report?.generatedAt || '—' }}
        </el-tag>
      </div>
      <div class="report__table">
        <el-table :data="report?.workloads ?? []" size="small">
          <el-table-column prop="nickname" label="成员" min-width="120" />
          <el-table-column prop="roleLabel" label="角色" width="110" />
          <el-table-column prop="taskCreated" label="本期新增" width="100" align="right" />
          <el-table-column prop="taskCompleted" label="本期完成" width="100" align="right" />
          <el-table-column prop="taskOpen" label="名下未完成" width="110" align="right" />
          <el-table-column prop="versionUploaded" label="上传版本" width="100" align="right" />
          <el-table-column prop="assetApproved" label="通过资产" width="100" align="right" />
          <el-table-column prop="points" label="完成故事点" width="110" align="right" />
          <el-table-column label="实际工时" width="100" align="right">
            <template #default="{ row }">{{ hours(row.actualHours) }}</template>
          </el-table-column>
          <template #empty>
            <el-empty :image-size="70" description="项目里还没有已加入的成员" />
          </template>
        </el-table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.report {
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
  align-items: center;
  gap: 10px;
  flex: none;
}

.filters {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px 18px;
}

.filters__label {
  font-size: 12.5px;
}

.report__summary-text {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.85;
}

.report__risks {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.9;
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

.metric__hint {
  margin-top: 6px;
  font-size: 11.5px;
}

.panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13.5px;
  font-weight: 600;
  margin-bottom: 8px;
  padding: 14px 18px 0;
}

/**
 * 表格的内缩必须加在普通包裹层上，**不能**加在 .el-table 自己身上。
 *
 * Element Plus 的 TableLayout.updateColumnsWidth() 用 `el.clientWidth`（**含 padding**）
 * 当表格可用宽度，再把这个值写成内部 <table> 的像素宽；同时它给 .el-table 根节点挂了
 * ResizeObserver，尺寸一变就重算列宽。而 .report 是 display:grid，隐式列轨按子项的
 * max-content 定宽——于是形成正反馈：
 *   根节点 padding 16px → 内部 table 被撑到 clientWidth → 溢出 padding box
 *   → grid item 的 max-content 变成「轨道 + 16」→ 轨道变宽 → clientWidth 又变大……
 * 实测每帧 +48px，页面无限往右扩（真实时间 6 秒内 .el-table 从 5820px 涨到 23100px）。
 * 加在普通包裹层上时，内部 table 宽取自包裹层的 content 宽，两边正好抵消，是稳定不动点。
 */
.report__table {
  padding: 0 8px 12px;
}
</style>
