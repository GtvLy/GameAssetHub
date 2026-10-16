<script setup lang="ts">
import { Delete, Upload, View } from '@element-plus/icons-vue'
import AssetStatusTag from './AssetStatusTag.vue'
import { assetPriorityMeta } from '@/constants/asset'
import type { AssetVO } from '@/types/asset'

/**
 * 资产表格视图（文档 7.3 的列表形态）。
 *
 * 与卡片墙的分工：卡片墙用缩略图回答「这个资产长什么样」，
 * 表格用来横向比较——谁的版本数最多、哪几条还停在待审核、谁负责。
 * 因此这里刻意不塞缩略图，把所有列压在一屏内。
 *
 * 组件本身不请求数据，也不改数据：列表由父页面持有，
 * 任何写操作完成后父页面重新拉取（见 AssetListView）。
 * 这里只负责渲染与转发事件，避免出现「本地数组推算出来的状态」。
 */
defineProps<{
  assets: AssetVO[]
  loading: boolean
  /** 只读角色下不渲染「新版本 / 删除」，避免点下去必然 403 */
  canWrite: boolean
}>()

const emit = defineEmits<{
  (e: 'open', asset: AssetVO): void
  (e: 'upload-version', asset: AssetVO): void
  (e: 'delete', asset: AssetVO): void
}>()
</script>

<template>
  <!-- 外层 scroll 容器 + 内层 min-width：窄屏（笔记本分屏、投影）上列不会被挤成一团，
       而是整体横向滚动，列宽在所有屏幕上保持稳定 -->
  <div class="asset-table panel">
    <div class="asset-table__scroll">
      <el-table
        v-loading="loading"
        :data="assets"
        class="asset-table__el"
        :empty-text="loading ? '加载中…' : '暂无资产'"
        style="width: 100%"
      >
        <el-table-column label="资产编号" width="150">
          <template #default="{ row }">
            <span class="mono asset-table__no">{{ row.assetNo }}</span>
          </template>
        </el-table-column>

        <el-table-column label="名称" min-width="200">
          <template #default="{ row }">
            <el-button text class="asset-table__name" @click="emit('open', row)">
              {{ row.name }}
            </el-button>
          </template>
        </el-table-column>

        <el-table-column label="分类" min-width="130">
          <template #default="{ row }">
            <span class="asset-table__category">
              <span class="asset-table__dot" :style="{ background: row.categoryColor || 'var(--border)' }" />
              {{ row.categoryName || '未分类' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <AssetStatusTag :status="row.status" />
          </template>
        </el-table-column>

        <el-table-column label="优先级" width="90">
          <template #default="{ row }">
            <!-- 与卡片右上角一致，显示 P0/P1/P2 而不是中文标签：
                 表格是用来扫读排序的，短码更容易纵向对齐比较 -->
            <el-tag :type="assetPriorityMeta(row.priority).tagType" size="small" effect="plain">
              {{ row.priority }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="负责人" min-width="120">
          <template #default="{ row }">
            <span :class="{ muted: !row.ownerName }">{{ row.ownerName || '未指派' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="版本数" width="90" align="right">
          <template #default="{ row }">
            <span class="tabular">{{ row.versionCount }}</span>
          </template>
        </el-table-column>

        <el-table-column label="更新时间" width="165">
          <template #default="{ row }">
            <span class="muted asset-table__time">{{ row.updateTime || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="200" align="right">
          <template #default="{ row }">
            <el-button text size="small" :icon="View" @click="emit('open', row)">查看</el-button>
            <template v-if="canWrite">
              <el-button text size="small" :icon="Upload" @click="emit('upload-version', row)">
                新版本
              </el-button>
              <el-button text size="small" type="danger" :icon="Delete" @click="emit('delete', row)">
                删除
              </el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.asset-table__scroll {
  overflow-x: auto;
}

/* 1258 ≈ 固定列之和(795) + min-width 列之和(450) + 边框，
   低于这个宽度就横向滚动，而不是把名称/分类压成「角色…」 */
.asset-table__el {
  min-width: 1260px;
}

.asset-table__no {
  font-size: 12.5px;
  letter-spacing: 0.3px;
}

.asset-table__name {
  height: auto;
  padding: 0;
  font-weight: 600;
  font-size: 13.5px;
  color: var(--text-primary);
}

.asset-table__name:hover {
  color: var(--accent);
}

.asset-table__category {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.asset-table__dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
  flex: none;
}

.asset-table__time {
  font-size: 12.5px;
}
</style>
