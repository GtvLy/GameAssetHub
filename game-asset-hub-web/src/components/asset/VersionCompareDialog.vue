<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import { compareVersions } from '@/api/asset'
import type { AssetVersionCompareVO, AssetVersionVO } from '@/types/asset'

/**
 * 版本对比（文档 7.3）。
 *
 * 只比元数据，不比文件内容——三维资产逐字节 diff 没有可读性，
 * 美术真正要确认的是「面数涨了多少」「贴图从 1K 变 2K 还是反过来」，
 * 这些结论后端已经算好（AssetVersionCompareVO.diffs），前端只负责
 * 把「哪几项变了」和「变的方向」做得一眼可辨。
 */
const props = defineProps<{
  modelValue: boolean
  assetId: number
  versions: AssetVersionVO[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

const fromId = ref<number | null>(null)
const toId = ref<number | null>(null)
const loading = ref(false)
const result = ref<AssetVersionCompareVO | null>(null)

function versionLabel(version: AssetVersionVO): string {
  const name = version.versionName || version.fileName
  return `V${version.versionNo} · ${name}`
}

/**
 * 默认组合：目标取当前版本（回滚过时它不是版本号最大的那一版，所以不能简单取第一项），
 * 基准取版本号紧邻它、更旧的那一版。
 *
 * 方向不能反：把「新」当基准会让面数与体积的增量整体变号，
 * 明明加了几万面却显示成减少，比不对比更误导人。
 */
function defaultPair(): { from: number | null; to: number | null } {
  const list = props.versions
  if (list.length < 2) {
    return { from: null, to: null }
  }
  const target = list.find((v) => v.current) ?? list[0]
  const index = list.indexOf(target)
  const base = list[index + 1] ?? list[index - 1] ?? null
  return { from: base ? base.id : null, to: target.id }
}

const canCompare = computed(
  () => fromId.value != null && toId.value != null && fromId.value !== toId.value && !loading.value
)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    const pair = defaultPair()
    fromId.value = pair.from
    toId.value = pair.to
    result.value = null
    // 默认组合本来就有意义，打开即对比，省掉一次多余的点击。
    // 版本不足两个时无可比组合，此时保持空态并给出提示
    if (pair.from != null && pair.to != null) {
      void compare()
    }
  },
  { immediate: true }
)

// 选择一变就丢掉上一次的结果：留着会让用户以为表格里是当前这对版本的差异
watch([fromId, toId], () => {
  const current = result.value
  if (current && (current.from.id !== fromId.value || current.to.id !== toId.value)) {
    result.value = null
  }
})

async function compare() {
  const from = fromId.value
  const to = toId.value
  // 同一个版本和自己比没有任何差异可言，后端也只会返回一份空表，不如不发这次请求
  if (from == null || to == null || from === to) return

  loading.value = true
  try {
    result.value = await compareVersions(props.assetId, from, to)
  } catch {
    // 拦截器已提示；清掉结果避免把上一次的差异当成这一次的结论
    result.value = null
  } finally {
    loading.value = false
  }
}

/** 面数增量：任一版本没填面数时后端给 null，此时不做符号渲染，直接说明无法比较 */
const polyDeltaText = computed(() => {
  const delta = result.value?.polyCountDelta
  if (delta == null) return '—'
  return delta > 0 ? `+${delta}` : String(delta)
})

const polyHint = computed(() => {
  const delta = result.value?.polyCountDelta
  if (delta == null) return '有版本未填写面数，无法比较'
  if (delta === 0) return '面数没有变化'
  return delta > 0 ? '面数增加，资源变重' : '面数减少，属于优化'
})

const sizeHint = computed(() => {
  const delta = result.value?.fileSizeDelta
  if (delta == null || delta === 0) return '体积没有变化'
  return delta > 0 ? '体积增加，注意包体预算' : '体积减少，属于优化'
})

/**
 * 增量配色：变大 = 需要留意（warning），变小 = 优化（success）。
 * 「变多」在资产管线里通常是坏事（面数与贴图预算会被吃满），
 * 因此不按数值大小、而按方向着色。
 */
function deltaColor(delta: number | null | undefined): string {
  if (delta == null || delta === 0) return 'var(--text-muted)'
  return delta > 0 ? 'var(--warning)' : 'var(--success)'
}

/** 变更行加底纹：差异表里没变的行占多数，靠底纹才能一眼扫到真正变了的几项 */
function rowClassName({ row }: { row: { changed: boolean } }): string {
  return row.changed ? 'row--changed' : ''
}
</script>

<template>
  <el-dialog v-model="visible" title="版本对比" width="760px" append-to-body>
    <div class="picker">
      <div class="picker__side">
        <span class="muted picker__label">基准版本（旧）</span>
        <el-select v-model="fromId" placeholder="选择基准版本" style="width: 100%">
          <el-option
            v-for="version in versions"
            :key="version.id"
            :label="versionLabel(version)"
            :value="version.id"
            :disabled="version.id === toId"
          >
            <div class="version-option">
              <span>{{ versionLabel(version) }}</span>
              <span class="muted version-option__meta">{{ version.createTime }} · {{ version.fileSizeText }}</span>
            </div>
          </el-option>
        </el-select>
      </div>

      <el-icon class="picker__arrow" :size="18"><ArrowRight /></el-icon>

      <div class="picker__side">
        <span class="muted picker__label">对比目标（新）</span>
        <el-select v-model="toId" placeholder="选择对比目标" style="width: 100%">
          <el-option
            v-for="version in versions"
            :key="version.id"
            :label="versionLabel(version)"
            :value="version.id"
            :disabled="version.id === fromId"
          >
            <div class="version-option">
              <span>{{ versionLabel(version) }}</span>
              <span class="muted version-option__meta">{{ version.createTime }} · {{ version.fileSizeText }}</span>
            </div>
          </el-option>
        </el-select>
      </div>

      <el-button type="primary" :loading="loading" :disabled="!canCompare" @click="compare">
        开始对比
      </el-button>
    </div>

    <div v-loading="loading" class="result">
      <template v-if="result">
        <div class="metrics">
          <div class="metric">
            <span class="muted metric__label">面数变化</span>
            <span class="metric__value tabular" :style="{ color: deltaColor(result.polyCountDelta) }">
              {{ polyDeltaText }}
            </span>
            <span class="muted metric__hint">{{ polyHint }}</span>
          </div>

          <div class="metric">
            <span class="muted metric__label">体积变化</span>
            <span class="metric__value tabular" :style="{ color: deltaColor(result.fileSizeDelta) }">
              {{ result.fileSizeDeltaText }}
            </span>
            <span class="muted metric__hint">{{ sizeHint }}</span>
          </div>

          <div class="metric metric--wide">
            <span class="muted metric__label">对比范围</span>
            <span class="metric__range">
              <span class="mono">V{{ result.from.versionNo }}</span>
              <el-icon :size="13"><ArrowRight /></el-icon>
              <span class="mono">V{{ result.to.versionNo }}</span>
            </span>
            <span class="muted metric__hint">
              {{ result.from.uploaderName || '未知' }} · {{ result.from.createTime || '—' }}
              对比
              {{ result.to.uploaderName || '未知' }} · {{ result.to.createTime || '—' }}
            </span>
          </div>
        </div>

        <el-table
          :data="result.diffs"
          :row-class-name="rowClassName"
          size="small"
          border
          style="width: 100%"
          empty-text="两个版本的元数据完全一致"
        >
          <el-table-column prop="field" label="对比项" width="130" />
          <el-table-column label="基准版本（旧）" min-width="200">
            <template #default="{ row }">
              <span class="mono cell">{{ row.before }}</span>
            </template>
          </el-table-column>
          <el-table-column label="对比目标（新）" min-width="200">
            <template #default="{ row }">
              <span class="mono cell">{{ row.after }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="92" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.changed" size="small" type="warning" effect="light">已变更</el-tag>
              <span v-else class="muted">无变化</span>
            </template>
          </el-table-column>
        </el-table>

        <p class="footnote muted">
          这里对比的是元数据（面数、贴图尺寸、格式、体积、变更说明等），不做文件内容 diff：
          三维资产的差异要看几何与贴图，逐项列出关键指标比二进制对比更有判断价值。
        </p>
      </template>

      <el-empty
        v-else-if="!loading"
        :image-size="70"
        :description="versions.length < 2 ? '至少需要两个版本才能对比' : '选择两个不同的版本后开始对比'"
      />
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.picker {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}

.picker__side {
  flex: 1;
  min-width: 0;
}

.picker__label {
  display: block;
  margin-bottom: 5px;
  font-size: 12px;
}

.picker__arrow {
  flex: none;
  margin-bottom: 9px;
  color: var(--text-muted);
}

.version-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.version-option__meta {
  font-size: 11.5px;
}

.result {
  margin-top: 16px;
  min-height: 120px;
}

.metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 14px;
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 11px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-panel);
}

.metric--wide {
  grid-column: 1 / -1;
}

.metric__label {
  font-size: 11.5px;
}

.metric__value {
  font-size: 19px;
  font-weight: 700;
  line-height: 1.3;
}

.metric__range {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 15px;
  font-weight: 600;
}

.metric__hint {
  font-size: 11.5px;
  line-height: 1.5;
}

.cell {
  font-size: 12.5px;
  word-break: break-all;
}

.footnote {
  margin: 12px 0 0;
  font-size: 11.5px;
  line-height: 1.7;
}

/* 变更行底纹。必须用 :deep()：行是 el-table 内部渲染的，
   拿不到本组件的 scoped 属性，直接写 .row--changed 不会命中 */
.result :deep(tr.row--changed) td.el-table__cell {
  background: var(--accent-soft);
}
</style>
