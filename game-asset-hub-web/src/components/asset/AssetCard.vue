<script setup lang="ts">
import { computed } from 'vue'
import { Picture, User } from '@element-plus/icons-vue'
import { assetPriorityMeta, assetStatusMeta } from '@/constants/asset'
import type { AssetVO } from '@/types/asset'

/**
 * 资产卡片（文档 7.3 的卡片墙）。
 *
 * 三处角标的分工（照文档的视觉约定）：
 *   - 左上角色块 = 状态，一眼看出这张资产能不能用；
 *   - 右上角标记 = 优先级，P0 会被立刻看到；
 *   - 悬停浮层 = 操作按钮，平时不干扰浏览缩略图。
 */
const props = defineProps<{ asset: AssetVO }>()

const emit = defineEmits<{
  (e: 'open', asset: AssetVO): void
  (e: 'upload-version', asset: AssetVO): void
  (e: 'delete', asset: AssetVO): void
}>()

const statusMeta = computed(() => assetStatusMeta(props.asset.status))
const priorityMeta = computed(() => assetPriorityMeta(props.asset.priority))

/** 缩略图缺失时的占位：用分类色做底，避免整片灰块 */
const placeholderStyle = computed(() => ({
  background: `linear-gradient(135deg, ${props.asset.categoryColor || '#5b8cff'}22, var(--bg-hover))`
}))

const currentVersion = computed(() => props.asset.currentVersion)
</script>

<template>
  <div class="card panel" @click="emit('open', asset)">
    <div class="card__thumb">
      <el-image
        v-if="asset.thumbnailUrl"
        :src="asset.thumbnailUrl"
        fit="cover"
        loading="lazy"
        class="card__image"
        :preview-src-list="[asset.thumbnailUrl]"
        :initial-index="0"
        preview-teleported
        @click.stop
      />
      <div v-else class="card__image card__image--empty" :style="placeholderStyle">
        <el-icon :size="30"><Picture /></el-icon>
        <span class="card__ext mono">{{ currentVersion?.fileExt || 'file' }}</span>
      </div>

      <!-- 左上角：状态色块 -->
      <span class="card__status" :style="{ background: statusMeta.color }">
        {{ statusMeta.label }}
      </span>

      <!-- 右上角：优先级标记（P2 不显示，避免满屏都是标记） -->
      <span
        v-if="asset.priority !== 'P2'"
        class="card__priority"
        :style="{ background: priorityMeta.color }"
      >
        {{ asset.priority }}
      </span>

      <!-- 悬停浮层：操作 -->
      <div class="card__overlay">
        <el-button size="small" type="primary" @click.stop="emit('open', asset)">查看</el-button>
        <el-button size="small" @click.stop="emit('upload-version', asset)">新版本</el-button>
        <el-button size="small" type="danger" plain @click.stop="emit('delete', asset)">删除</el-button>
      </div>
    </div>

    <div class="card__body">
      <div class="card__title-row">
        <span class="card__name" :title="asset.name">{{ asset.name }}</span>
        <span class="card__versions tabular">V{{ asset.versionCount }}</span>
      </div>
      <div class="card__no mono">{{ asset.assetNo }}</div>
      <div class="card__meta">
        <span v-if="asset.categoryName">{{ asset.categoryName }}</span>
        <span class="card__dot" />
        <span class="card__owner">
          <el-icon :size="12"><User /></el-icon>
          {{ asset.ownerName || '未指派' }}
        </span>
      </div>
      <div v-if="asset.tagList.length" class="card__tags">
        <el-tag v-for="tag in asset.tagList.slice(0, 3)" :key="tag" size="small" effect="plain" type="info">
          {{ tag }}
        </el-tag>
      </div>
    </div>
  </div>
</template>

<style scoped>
.card {
  overflow: hidden;
  cursor: pointer;
  transition:
    transform 0.15s ease,
    box-shadow 0.15s ease;
}

.card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(16, 24, 40, 0.14);
}

.card__thumb {
  position: relative;
  aspect-ratio: 4 / 3;
  background: var(--bg-hover);
}

.card__image {
  width: 100%;
  height: 100%;
  display: block;
}

.card__image--empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--text-muted);
}

.card__ext {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.card__status {
  position: absolute;
  top: 0;
  left: 0;
  padding: 3px 10px;
  border-bottom-right-radius: 10px;
  font-size: 11.5px;
  font-weight: 600;
  color: #fff;
}

.card__priority {
  position: absolute;
  top: 8px;
  right: 8px;
  min-width: 26px;
  padding: 2px 6px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 700;
  text-align: center;
  color: #fff;
}

.card__overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: rgba(16, 20, 28, 0.55);
  opacity: 0;
  transition: opacity 0.15s ease;
}

.card:hover .card__overlay {
  opacity: 1;
}

.card__body {
  padding: 11px 13px 13px;
  display: grid;
  gap: 5px;
}

.card__title-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.card__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
  font-size: 13.5px;
}

.card__versions {
  flex: none;
  font-size: 11.5px;
  color: var(--text-muted);
}

.card__no {
  font-size: 11.5px;
  color: var(--text-muted);
  letter-spacing: 0.3px;
}

.card__meta {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--text-muted);
}

.card__dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.6;
}

.card__owner {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 2px;
}
</style>
