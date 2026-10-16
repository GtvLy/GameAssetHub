<script setup lang="ts">
import { computed, ref } from 'vue'
import { Download, RefreshLeft, User } from '@element-plus/icons-vue'
import { assetStatusMeta } from '@/constants/asset'
import type { AssetVersionVO } from '@/types/asset'

/**
 * 版本时间线（文档 7.3）。
 *
 * 每个节点可展开查看变更说明、面数、贴图尺寸等元数据，
 * 并提供「下载此版本」「设为此版本」两个操作。
 *
 * 「设为此版本」是**只改标记位**的回滚：历史文件一个都不删，
 * 因此时间线上永远能看到全部版本——这正是「历史版本可完整追溯」的界面体现。
 */
const props = defineProps<{
  versions: AssetVersionVO[]
  assetStatus?: string | null
  /** 当前用户是否可写（决定回滚按钮是否可用） */
  canWrite?: boolean
  /** 正在下载 / 切换的版本ID，用于按钮 loading */
  busyVersionId?: number | null
}>()

const emit = defineEmits<{
  (e: 'download', version: AssetVersionVO): void
  (e: 'set-current', version: AssetVersionVO): void
}>()

const expanded = ref<number[]>([])

const statusMeta = computed(() => assetStatusMeta(props.assetStatus))

/** 当前版本排最前，时间线按版本号倒序（后端已排好，这里不重排以免两处口径不一致） */
const timeline = computed(() => props.versions)

function toggle(versionId: number) {
  const index = expanded.value.indexOf(versionId)
  if (index >= 0) {
    expanded.value.splice(index, 1)
  } else {
    expanded.value.push(versionId)
  }
}

function isExpanded(versionId: number) {
  return expanded.value.includes(versionId)
}

/** 节点颜色：当前版本用强调色，其余用弱色，一眼看出「现在用的是哪一版」 */
function dotColor(version: AssetVersionVO): string {
  return version.current ? statusMeta.value.color : 'var(--border)'
}
</script>

<template>
  <div class="timeline">
    <el-empty v-if="!timeline.length" :image-size="70" description="还没有任何版本" />

    <el-timeline v-else>
      <el-timeline-item
        v-for="version in timeline"
        :key="version.id"
        :color="dotColor(version)"
        :hollow="!version.current"
        :timestamp="version.createTime"
        placement="top"
        size="large"
      >
        <div class="node" :class="{ 'node--current': version.current }">
          <div class="node__head" @click="toggle(version.id)">
            <span class="node__version mono">V{{ version.versionNo }}</span>
            <span class="node__name">{{ version.versionName || `V${version.versionNo}` }}</span>

            <el-tag v-if="version.current" size="small" effect="dark" round type="success">
              当前版本
            </el-tag>

            <span class="node__spacer" />

            <span class="node__uploader muted">
              <el-icon :size="12"><User /></el-icon>
              {{ version.uploaderName || '未知' }}
            </span>
            <el-tag size="small" effect="plain" type="info">{{ version.fileSizeText }}</el-tag>
            <el-button text size="small" @click.stop="toggle(version.id)">
              {{ isExpanded(version.id) ? '收起' : '详情' }}
            </el-button>
          </div>

          <div v-if="version.changeLog" class="node__log">{{ version.changeLog }}</div>

          <el-collapse-transition>
            <div v-show="isExpanded(version.id)" class="node__detail">
              <div class="node__grid">
                <div class="node__field">
                  <span class="muted">原始文件</span>
                  <span class="mono">{{ version.fileName }}</span>
                </div>
                <div class="node__field">
                  <span class="muted">格式</span>
                  <span class="mono">{{ version.fileExt || '—' }}</span>
                </div>
                <div class="node__field">
                  <span class="muted">面数</span>
                  <span class="tabular">{{ version.polyCount ?? '—' }}</span>
                </div>
                <div class="node__field">
                  <span class="muted">贴图尺寸</span>
                  <span class="mono">{{ version.textureSize || '—' }}</span>
                </div>
                <div class="node__field node__field--wide">
                  <span class="muted">文件 MD5</span>
                  <span class="mono">{{ version.fileMd5 || '—' }}</span>
                </div>
              </div>

              <div class="node__actions">
                <el-button
                  size="small"
                  :icon="Download"
                  :loading="busyVersionId === version.id"
                  @click="emit('download', version)"
                >
                  下载此版本
                </el-button>
                <el-button
                  v-if="!version.current"
                  size="small"
                  type="primary"
                  plain
                  :icon="RefreshLeft"
                  :disabled="!canWrite"
                  :loading="busyVersionId === version.id"
                  @click="emit('set-current', version)"
                >
                  设为此版本
                </el-button>
                <span v-else class="node__hint muted">当前正在使用这一版</span>
              </div>
            </div>
          </el-collapse-transition>
        </div>
      </el-timeline-item>
    </el-timeline>
  </div>
</template>

<style scoped>
.timeline {
  padding: 4px 0 0;
}

.node {
  padding: 10px 13px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--bg-panel);
}

.node--current {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.node__head {
  display: flex;
  align-items: center;
  gap: 9px;
  cursor: pointer;
  flex-wrap: wrap;
}

.node__version {
  font-weight: 700;
  font-size: 13px;
}

.node__name {
  font-size: 13px;
}

.node__spacer {
  flex: 1;
}

.node__uploader {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
}

.node__log {
  margin-top: 6px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-muted);
}

.node__detail {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
}

.node__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 8px 14px;
}

.node__field {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12.5px;
  min-width: 0;
}

.node__field--wide {
  grid-column: 1 / -1;
}

.node__field > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.node__hint {
  font-size: 12px;
}
</style>
