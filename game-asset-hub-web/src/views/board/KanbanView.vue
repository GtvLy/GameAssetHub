<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import KanbanColumn from '@/components/board/KanbanColumn.vue'
import TaskEditDialog from '@/components/board/TaskEditDialog.vue'
import TaskDetailDrawer from '@/components/board/TaskDetailDrawer.vue'
import { fetchBoard, moveTask } from '@/api/task'
import { fetchMembers } from '@/api/project'
import { canWriteTasks } from '@/constants/task'
import { useProjectStore } from '@/stores/project'
import type { ProjectMemberVO } from '@/types'
import type { BoardColumnVO, BoardVO, TaskCardVO, TaskColumn, TaskMoveVO, TaskVO } from '@/types/task'

/**
 * Kanban 看板（文档 7.2）。
 *
 * ## 拖拽为什么要「快照 + 服务端修正」
 *
 * `vuedraggable` 会在拖拽时**就地改本地数组**——这就是乐观更新，用户放手的
 * 那一瞬间卡片已经在新位置了。接下来：
 *
 * 1. 用新位置的前后卡片算出 `prevTaskId` / `nextTaskId` 发给服务端；
 * 2. 服务端返回它算出的最终 `sortOrder` 与新的 `version`，前端**以它为准**修正；
 * 3. 失败（409 / 网络）时用拖拽开始前的快照整块回滚，并提示「已恢复原位置」。
 *
 * 第 2 步不能省：多端并发时本地算出来的位置与服务端可能不同；
 * 第 3 步也不能省：不回滚的话界面会停在一个服务端并不认可的位置上，
 * 用户刷新一下顺序就变了。
 *
 * `version` 同样必须回填：它每次写操作 +1，不回填的话同一张卡片
 * 连续拖第二次必然 409。
 */
const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()

const projectId = computed(() => Number(route.params.projectId))
/** VIEWER 只读：不给拖拽、不给「新建任务」、评论区也退成只读 */
const editable = computed(() => canWriteTasks(projectStore.myRole))

const board = ref<BoardVO | null>(null)
const members = ref<ProjectMemberVO[]>([])
const loading = ref(false)

const editVisible = ref(false)
const editingTask = ref<TaskCardVO | null>(null)
const detailVisible = ref(false)
const detailTaskId = ref<number | null>(null)

/** 拖拽开始前的整块看板快照，用于失败回滚 */
let snapshot: BoardColumnVO[] | null = null

async function loadBoard() {
  loading.value = true
  try {
    board.value = await fetchBoard(projectId.value)
  } catch {
    board.value = null
  } finally {
    loading.value = false
  }
}

async function loadMembers() {
  try {
    members.value = await fetchMembers(projectId.value)
  } catch {
    members.value = []
  }
}

function cloneColumns(): BoardColumnVO[] {
  return JSON.parse(JSON.stringify(board.value?.columns ?? [])) as BoardColumnVO[]
}

/** 列头的数量与故事点合计跟着本地数组走，避免乐观更新后列头数字对不上 */
function recalcCounts() {
  if (!board.value) return
  let total = 0
  for (const column of board.value.columns) {
    column.taskCount = column.tasks.length
    column.storyPointTotal = column.tasks.reduce((sum, t) => sum + (t.storyPoint ?? 0), 0)
    total += column.tasks.length
  }
  board.value.totalTasks = total
}

function findCard(taskId: number): TaskCardVO | null {
  if (!board.value) return null
  for (const column of board.value.columns) {
    const hit = column.tasks.find((t) => t.id === taskId)
    if (hit) return hit
  }
  return null
}

function onDragStart() {
  snapshot = cloneColumns()
}

function restoreSnapshot() {
  if (snapshot && board.value) {
    board.value.columns = snapshot
    recalcCounts()
  }
  snapshot = null
}

/**
 * 把服务端的落位结果写回本地。
 *
 * 服务端触发整列重排时会顺带改**别的**卡片的排序值，而响应只带回被拖动那一张，
 * 因此重排场景直接重新拉一次看板——本地推断不出别人变成了多少。
 */
function applyServerResult(res: TaskMoveVO) {
  if (!board.value) return
  if (res.rebalanced) {
    void loadBoard()
    return
  }
  const card = findCard(res.id)
  if (card) {
    card.columnStatus = res.columnStatus
    card.columnLabel = res.columnLabel
    card.sortOrder = res.sortOrder
    card.version = res.version
    card.finishedAt = res.finishedAt
  }
  const column = board.value.columns.find((c) => c.status === res.columnStatus)
  if (column) {
    // 与服务端同一口径：sort_order 升序，并列时按 id——两处不一致会让
    //「本地看到的位置」和「刷新后的位置」不一样
    column.tasks.sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)
  }
  recalcCounts()
}

/** 拖拽落位（跨列与本列内都走这里） */
async function handleDrop(payload: {
  columnStatus: TaskColumn
  task: TaskCardVO
  newIndex: number
}) {
  const column = board.value?.columns.find((c) => c.status === payload.columnStatus)
  if (!column) return

  const index = column.tasks.findIndex((t) => t.id === payload.task.id)
  const prev = index > 0 ? column.tasks[index - 1] : null
  const next = index >= 0 && index < column.tasks.length - 1 ? column.tasks[index + 1] : null

  try {
    const res = await moveTask(payload.task.id, {
      toColumn: payload.columnStatus,
      prevTaskId: prev ? prev.id : null,
      nextTaskId: next ? next.id : null,
      version: payload.task.version
    })
    applyServerResult(res)
  } catch (error) {
    restoreSnapshot()
    const conflict = (error as { response?: { status?: number } })?.response?.status === 409
    // 409 的「数据已被他人修改，请刷新后重试」由响应拦截器统一提示，
    // 这里补上「已恢复原位置」——不然用户只看到一句刷新提示，界面还停在错误位置
    ElMessage.error(conflict ? '移动失败，已恢复原位置，请刷新看板' : '移动失败，已恢复原位置')
    if (conflict) await loadBoard()
  }
}

/** 卡片上的「改状态」下拉（拖拽的备选路径）：不带锚点，服务端会追加到目标列列尾 */
async function handleCardMove(task: TaskCardVO, toColumn: TaskColumn) {
  snapshot = cloneColumns()
  const from = board.value?.columns.find((c) => c.status === task.columnStatus)
  const to = board.value?.columns.find((c) => c.status === toColumn)
  if (from && to) {
    // 先做一次乐观更新：与拖拽的体验保持一致
    from.tasks = from.tasks.filter((t) => t.id !== task.id)
    to.tasks = [...to.tasks, task]
    task.columnStatus = toColumn
    recalcCounts()
  }
  try {
    const res = await moveTask(task.id, { toColumn, prevTaskId: null, nextTaskId: null, version: task.version })
    applyServerResult(res)
  } catch (error) {
    restoreSnapshot()
    const conflict = (error as { response?: { status?: number } })?.response?.status === 409
    ElMessage.error(conflict ? '改状态失败，已恢复原位置，请刷新看板' : '改状态失败，已恢复原位置')
    if (conflict) await loadBoard()
  }
}

function openTask(task: TaskCardVO) {
  detailTaskId.value = task.id
  detailVisible.value = true
  void router.replace({ query: { ...route.query, task: String(task.id) } })
}

function openEdit(task: TaskCardVO | null) {
  editingTask.value = task
  editVisible.value = true
}

function openAsset(assetId: number) {
  router.push(`/project/${projectId.value}/assets/${assetId}`)
}

function handleSaved(taskId: number) {
  void loadBoard()
  // 新建/编辑后直接打开详情：用户刚填完的内容能立刻被确认
  detailTaskId.value = taskId
  detailVisible.value = true
}

/** 详情抽屉里点了「编辑」：把详情当成卡片传给对话框（字段是超集） */
function handleEditFromDetail(task: TaskVO) {
  editingTask.value = task
  editVisible.value = true
}

watch(detailVisible, (open) => {
  if (!open && route.query.task) {
    const query = { ...route.query }
    delete query.task
    void router.replace({ query })
  }
})

/** 通知铃铛点进来时会带 ?task=<id>：自动打开那条任务的详情 */
function syncTaskFromQuery() {
  const raw = route.query.task
  const id = Array.isArray(raw) ? Number(raw[0]) : Number(raw)
  if (Number.isFinite(id) && id > 0) {
    detailTaskId.value = id
    detailVisible.value = true
  }
}

async function init() {
  await Promise.all([loadBoard(), loadMembers()])
  syncTaskFromQuery()
}

onMounted(() => {
  void init()
})

watch(
  () => [route.params.projectId, route.query.task],
  () => {
    void init()
  }
)
</script>

<template>
  <div class="kanban">
    <div class="kanban__bar">
      <div class="kanban__title">
        任务看板
        <span v-if="board" class="muted kanban__sum">
          共 {{ board.totalTasks }} 个任务
        </span>
      </div>
      <span class="muted kanban__hint">
        {{ editable ? '拖拽卡片推进状态，顺序会自动保存' : '你的角色是只读，只能查看看板' }}
      </span>
      <span class="spacer" />
      <el-button :icon="Refresh" text @click="init">刷新</el-button>
      <el-button v-if="editable" type="primary" :icon="Plus" @click="openEdit(null)">
        新建任务
      </el-button>
    </div>

    <div v-loading="loading" class="kanban__board">
      <KanbanColumn
        v-for="column in board?.columns ?? []"
        :key="column.status"
        :column="column"
        :editable="editable"
        @open="openTask"
        @move="handleCardMove"
        @drop="handleDrop"
      />
      <el-empty v-if="!loading && !board" class="kanban__empty" description="看板加载失败，请刷新重试" />
    </div>

    <TaskEditDialog
      v-model="editVisible"
      :project-id="projectId"
      :task="editingTask"
      :members="members"
      @saved="handleSaved"
    />

    <TaskDetailDrawer
      v-model="detailVisible"
      :task-id="detailTaskId"
      :project-id="projectId"
      :members="members"
      :editable="editable"
      @changed="loadBoard"
      @open-asset="openAsset"
      @edit="handleEditFromDetail"
    />
  </div>
</template>

<style scoped>
.kanban {
  display: flex;
  flex-direction: column;
  gap: 14px;
  /* 1366×768 下四列会挤，允许横向滚动（文档 9 节的兼容性要求） */
  min-height: 100%;
}

.kanban__bar {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
}

.kanban__title {
  font-size: 16px;
  font-weight: 700;
}

.kanban__sum {
  margin-left: 8px;
  font-size: 12.5px;
  font-weight: 400;
}

.kanban__hint {
  font-size: 12px;
}

.spacer {
  flex: 1;
}

.kanban__board {
  flex: 1;
  min-height: 420px;
  display: flex;
  gap: 12px;
  align-items: stretch;
  overflow-x: auto;
  padding-bottom: 6px;
}

.kanban__empty {
  margin: auto;
}
</style>
