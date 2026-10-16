<script setup lang="ts">
import draggable from 'vuedraggable'
import KanbanCard from './KanbanCard.vue'
import type { BoardColumnVO, TaskCardVO, TaskColumn } from '@/types/task'

/**
 * 看板的一列。
 *
 * 列头显示**数量与故事点合计**（由服务端算好下发，前端不遍历求——
 * 口径只有一处定义，列头也能立刻显示）。
 *
 * 列内卡片用 `vuedraggable`，`group="tasks"` 允许跨列拖拽。
 * `:list` 绑定的是列自己的任务数组：vuedraggable 会在拖拽时**就地修改**它，
 * 这就是「乐观更新」——先动 UI，再等接口结果。真正的失败回滚与
 * 服务端排序值修正由父组件（KanbanView）负责，它持有整块看板的快照。
 */
defineProps<{
  column: BoardColumnVO
  editable: boolean
}>()

const emit = defineEmits<{
  (e: 'open', task: TaskCardVO): void
  (e: 'move', task: TaskCardVO, toColumn: TaskColumn): void
  /**
   * 拖拽落位。added = 从别的列拖进来；moved = 本列内部换位置。
   * 两个事件都表示「这个数组已经被改过了」，父组件据此算出落位并调接口。
   */
  (e: 'drop', payload: { columnStatus: TaskColumn; task: TaskCardVO; newIndex: number }): void
}>()

interface ChangeEvent {
  added?: { element: TaskCardVO; newIndex: number }
  moved?: { element: TaskCardVO; newIndex: number }
}

function onChange(columnStatus: TaskColumn, event: ChangeEvent) {
  const payload = event.added ?? event.moved
  if (!payload) return
  emit('drop', { columnStatus, task: payload.element, newIndex: payload.newIndex })
}
</script>

<template>
  <section class="column">
    <header class="column__head">
      <span class="column__dot" :style="{ background: column.color }" />
      <span class="column__title">{{ column.title }}</span>
      <span class="column__count">{{ column.taskCount }}</span>
      <span class="column__spacer" />
      <span class="column__points" :title="`本列故事点合计 ${column.storyPointTotal}`">
        {{ column.storyPointTotal }} 点
      </span>
    </header>

    <draggable
      class="column__list"
      :list="column.tasks"
      group="tasks"
      item-key="id"
      :animation="150"
      ghost-class="column__ghost"
      drag-class="column__dragging"
      @change="onChange(column.status, $event as ChangeEvent)"
    >
      <template #item="{ element }">
        <div class="column__item">
          <KanbanCard :task="element" :editable="editable" @open="emit('open', $event)" @move="(t, c) => emit('move', t, c)" />
        </div>
      </template>

      <template #footer>
        <div v-if="!column.tasks.length" class="column__empty">拖动卡片到这里</div>
      </template>
    </draggable>
  </section>
</template>

<style scoped>
.column {
  display: flex;
  flex-direction: column;
  min-width: 250px;
  flex: 1 1 0;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-panel);
}

.column__head {
  flex: none;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}

.column__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.column__title {
  font-weight: 600;
}

.column__count {
  min-width: 20px;
  padding: 0 6px;
  border-radius: 9px;
  background: var(--bg-hover);
  color: var(--text-muted);
  font-size: 11.5px;
  text-align: center;
}

.column__spacer {
  flex: 1;
}

.column__points {
  color: var(--text-muted);
  font-size: 11.5px;
}

.column__list {
  flex: 1;
  min-height: 90px;
  overflow-y: auto;
  padding: 9px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.column__item {
  /* 卡片本身有自己的圆角与阴影，这一层只负责间距 */
  outline: none;
}

.column__empty {
  padding: 22px 8px;
  border: 1px dashed var(--border);
  border-radius: var(--radius);
  color: var(--text-muted);
  font-size: 12px;
  text-align: center;
}

/* 拖拽中的视觉反馈（文档 7.2：拖拽中旋转 + 阴影，落位有过渡） */
.column__ghost {
  opacity: 0.4;
  border: 1px dashed var(--accent) !important;
  background: var(--accent-soft) !important;
}

.column__dragging {
  transform: rotate(2deg);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.25);
}
</style>
