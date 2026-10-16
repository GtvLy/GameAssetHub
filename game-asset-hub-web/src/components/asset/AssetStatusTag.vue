<script setup lang="ts">
import { computed } from 'vue'
import { assetPriorityMeta, assetStatusMeta } from '@/constants/asset'

/**
 * 资产状态 / 优先级标签。
 *
 * 两者合成一个组件是因为它们总是一起出现（卡片角标、表格行、详情页头部），
 * 拆分后调用方要写两遍相同的布局。用 show 控制显示哪一个。
 */
const props = withDefaults(
  defineProps<{
    status?: string | null
    priority?: string | null
    showStatus?: boolean
    showPriority?: boolean
    size?: 'small' | 'default' | 'large'
  }>(),
  {
    status: null,
    priority: null,
    showStatus: true,
    showPriority: false,
    size: 'small'
  }
)

const statusMeta = computed(() => assetStatusMeta(props.status))
const priorityMeta = computed(() => assetPriorityMeta(props.priority))
</script>

<template>
  <span class="tags">
    <el-tag v-if="showStatus" :type="statusMeta.tagType" :size="size" effect="light" round>
      {{ statusMeta.label }}
    </el-tag>
    <el-tag v-if="showPriority" :type="priorityMeta.tagType" :size="size" effect="plain">
      {{ priorityMeta.label }}
    </el-tag>
  </span>
</template>

<style scoped>
.tags {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
</style>
