<script setup lang="ts">
import { computed } from 'vue'
import { ChatDotRound, MoreFilled, Timer } from '@element-plus/icons-vue'
import { TASK_COLUMNS, dueState, taskPriorityMeta, taskTypeMeta } from '@/constants/task'
import { assetStatusMeta } from '@/constants/asset'
import type { TaskCardVO, TaskColumn } from '@/types/task'

/**
 * 看板卡片（文档 7.2）。
 *
 * 卡片上要一眼看全三件事：**多急**（左侧优先级色条）、**谁在做**（负责人头像）、
 * **什么时候要**（截止日期，临近/逾期变红）。其余信息（描述、评论、日志）
 * 点开详情再看——卡片塞得越满，看板上能同时看到的任务就越少。
 */
const props = defineProps<{
  task: TaskCardVO
  /** 允许写看板时才显示「改状态」下拉（VIEWER 只读） */
  editable: boolean
}>()

const emit = defineEmits<{
  (e: 'open', task: TaskCardVO): void
  (e: 'move', task: TaskCardVO, toColumn: TaskColumn): void
}>()

const priority = computed(() => taskPriorityMeta(props.task.priority, props.task.priorityColor))
const typeMeta = computed(() => taskTypeMeta(props.task.taskType))
const due = computed(() => dueState(props.task.dueDate, props.task.columnStatus))
const assetMeta = computed(() =>
  props.task.assetStatus ? assetStatusMeta(props.task.assetStatus) : null
)

/** 头像上的首字：昵称优先，取第一个字符 */
const avatarText = computed(() => {
  const name = props.task.assigneeName
  return name ? name.slice(0, 1) : '?'
})

function handleMove(command: TaskColumn) {
  if (command === props.task.columnStatus) return
  emit('move', props.task, command)
}
</script>

<template>
  <div class="card" :class="{ 'is-overdue': task.overdue }" @click="emit('open', task)">
    <span class="card__bar" :style="{ background: priority.color }" />

    <div class="card__head">
      <span class="card__title" :title="task.title">{{ task.title }}</span>

      <el-dropdown v-if="editable" trigger="click" @command="handleMove">
        <el-button
          text
          circle
          size="small"
          class="card__more"
          title="改状态（拖拽的备选路径）"
          @click.stop
        >
          <el-icon :size="14"><MoreFilled /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item
              v-for="col in TASK_COLUMNS"
              :key="col.value"
              :command="col.value"
              :disabled="col.value === task.columnStatus"
            >
              <span class="card__dot" :style="{ background: col.color }" />
              移动到「{{ col.label }}」
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <div class="card__tags">
      <el-tag size="small" effect="plain" :style="{ color: typeMeta.color, borderColor: typeMeta.color }">
        {{ task.taskTypeLabel }}
      </el-tag>
      <el-tag v-if="task.assetId" size="small" effect="plain" type="info">
        {{ task.assetCategoryName || '关联资产' }}
      </el-tag>
      <el-tag v-if="assetMeta" size="small" effect="plain" :type="assetMeta.tagType">
        {{ task.assetStatusLabel }}
      </el-tag>
      <span v-if="task.priority === 'P0'" class="card__flag">{{ priority.label }}</span>
    </div>

    <div v-if="task.assetName" class="card__asset" :title="task.assetName">
      {{ task.assetNo }} · {{ task.assetName }}
    </div>

    <div class="card__foot">
      <el-tooltip :content="task.assigneeName || '未指派'" placement="top">
        <el-avatar :size="20" class="card__avatar">{{ avatarText }}</el-avatar>
      </el-tooltip>

      <span v-if="task.dueDate" class="card__due" :class="`is-${due.level}`">
        <el-icon :size="12"><Timer /></el-icon>
        {{ due.text }}
      </span>
      <span v-else class="card__due muted">未设截止</span>

      <span class="card__spacer" />

      <span v-if="task.storyPoint != null" class="card__point">{{ task.storyPoint }} 点</span>
      <span v-if="task.commentCount > 0" class="card__comment">
        <el-icon :size="12"><ChatDotRound /></el-icon>
        {{ task.commentCount }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.card {
  position: relative;
  display: grid;
  gap: 7px;
  padding: 10px 11px 9px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-card);
  box-shadow: var(--shadow-card);
  cursor: pointer;
  transition: border-color 0.15s ease, transform 0.15s ease;
}

.card:hover {
  border-color: var(--accent);
}

.card.is-overdue {
  border-color: var(--danger);
}

/* 优先级色条：P0 红 / P1 橙 / P2 灰 */
.card__bar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  border-radius: var(--radius) 0 0 var(--radius);
}

.card__head {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}

.card__title {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.45;
  color: var(--text-primary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card__more {
  flex: none;
  opacity: 0;
}

.card:hover .card__more {
  opacity: 1;
}

.card__tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
}

.card__flag {
  font-size: 11px;
  font-weight: 700;
  color: var(--danger);
}

.card__asset {
  font-size: 11.5px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card__foot {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 11.5px;
  color: var(--text-muted);
}

.card__avatar {
  flex: none;
  background: var(--accent);
  color: #fff;
  font-size: 11px;
}

.card__due {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.card__due.is-overdue {
  color: var(--danger);
  font-weight: 600;
}

.card__due.is-soon {
  color: var(--warning);
  font-weight: 600;
}

.card__due.is-done {
  color: var(--success);
}

.card__spacer {
  flex: 1;
}

.card__comment {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.card__dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 6px;
  border-radius: 50%;
}
</style>
