<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Box, ChatDotRound, Delete, Position, User } from '@element-plus/icons-vue'
import { addComment, deleteComment, fetchTask } from '@/api/task'
import { TASK_COLUMNS, dueState, taskPriorityMeta, taskTypeMeta } from '@/constants/task'
import { assetStatusMeta } from '@/constants/asset'
import type { ProjectMemberVO } from '@/types'
import type { TaskCommentVO, TaskVO } from '@/types/task'

/**
 * 任务详情抽屉（文档 7.2）。
 *
 * 四块内容：字段、关联资产卡片、评论区、状态变更日志。
 * 关联资产卡片可点击跳资产详情——这是文档 5.5「任务与资产双向联动」里
 * 「从任务侧看资源状态」的那一半，另一半（资产详情里的「相关任务」）
 * 在资产详情页。
 */
const props = defineProps<{
  modelValue: boolean
  taskId: number | null
  projectId: number
  members: ProjectMemberVO[]
  /** 允许写看板时才显示评论输入与删除按钮（VIEWER 只读） */
  editable: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'changed'): void
  (e: 'open-asset', assetId: number): void
  (e: 'edit', task: TaskVO): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const loading = ref(false)
const detail = ref<TaskVO | null>(null)
const draft = ref('')
const posting = ref(false)
const mentionOpen = ref(false)

const priority = computed(() =>
  detail.value ? taskPriorityMeta(detail.value.priority, detail.value.priorityColor) : null
)
const typeMeta = computed(() => (detail.value ? taskTypeMeta(detail.value.taskType) : null))
const due = computed(() =>
  detail.value ? dueState(detail.value.dueDate, detail.value.columnStatus) : null
)
const assetMeta = computed(() =>
  detail.value?.assetStatus ? assetStatusMeta(detail.value.assetStatus) : null
)

/** ACTIVE 成员才能被 @：与后端 mentionCandidates 的候选名单一致 */
const mentionCandidates = computed(() => props.members.filter((m) => m.status === 'ACTIVE'))

async function load() {
  const id = props.taskId
  if (!id) {
    detail.value = null
    return
  }
  loading.value = true
  try {
    detail.value = await fetchTask(id)
  } catch {
    detail.value = null
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.taskId] as const,
  ([open]) => {
    if (open) {
      void load()
      draft.value = ''
    }
  }
)

/** 把 @昵称 插到输入框里：让用户不必记登录名（解析同时认登录名与昵称） */
function appendMention(member: ProjectMemberVO) {
  const name = member.nickname || member.username
  draft.value = draft.value ? `${draft.value} @${name} ` : `@${name} `
  mentionOpen.value = false
}

async function submitComment() {
  const id = props.taskId
  const content = draft.value.trim()
  if (!id || !content) {
    ElMessage.warning('评论内容不能为空')
    return
  }
  posting.value = true
  try {
    await addComment(id, content)
    draft.value = ''
    await load()
    ElMessage.success('评论已发表')
  } catch {
    // 拦截器已提示
  } finally {
    posting.value = false
  }
}

async function removeComment(comment: TaskCommentVO) {
  try {
    await ElMessageBox.confirm('确定删除这条评论吗？删除后不再展示（历史仍保留在数据库）。', '删除评论', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteComment(comment.id)
    await load()
    ElMessage.success('评论已删除')
  } catch {
    /* 只 403/404 两种：别人的评论或已被删除，拦截器已提示 */
  }
}
</script>

<template>
  <el-drawer v-model="visible" size="600px" :title="detail ? detail.title : '任务详情'">
    <div v-loading="loading" class="drawer">
      <el-empty v-if="!loading && !detail" description="任务不存在或无权访问" />

      <template v-else-if="detail">
        <!-- 字段区 -->
        <div class="panel">
          <div class="row">
            <el-tag size="small" effect="dark" :color="typeMeta?.color" style="border: none">
              {{ detail.taskTypeLabel }}
            </el-tag>
            <el-tag size="small" effect="plain" :type="priority?.tagType">
              {{ detail.priority }} {{ detail.priorityLabel }}
            </el-tag>
            <el-tag size="small" effect="plain" type="info">{{ detail.columnLabel }}</el-tag>
            <el-tag v-if="detail.overdue" size="small" type="danger" effect="dark">已逾期</el-tag>
            <span class="spacer" />
            <el-button v-if="editable" text size="small" @click="emit('edit', detail)">编辑</el-button>
          </div>

          <p v-if="detail.description" class="desc">{{ detail.description }}</p>
          <p v-else class="desc muted">暂无描述</p>

          <div class="grid">
            <div class="grid__item">
              <span class="muted">负责人</span>
              <span>
                <el-icon :size="13"><User /></el-icon>
                {{ detail.assigneeName || '未指派' }}
              </span>
            </div>
            <div class="grid__item">
              <span class="muted">创建人</span>
              <span>{{ detail.creatorName || '—' }}</span>
            </div>
            <div class="grid__item">
              <span class="muted">截止日期</span>
              <span :class="due?.level === 'overdue' ? 'danger' : ''">{{ due?.text }}</span>
            </div>
            <div class="grid__item">
              <span class="muted">预估 / 实际</span>
              <span>
                {{ detail.estimateHours ?? '—' }} / {{ detail.actualHours ?? '—' }} 小时
              </span>
            </div>
            <div class="grid__item">
              <span class="muted">故事点</span>
              <span>{{ detail.storyPoint ?? '—' }}</span>
            </div>
            <div class="grid__item">
              <span class="muted">完成时间</span>
              <span>{{ detail.finishedAt || '—' }}</span>
            </div>
          </div>
        </div>

        <!-- 关联资产（文档 5.5：从任务侧看资源状态） -->
        <div v-if="detail.assetId" class="panel asset" @click="emit('open-asset', detail.assetId!)">
          <div class="asset__head">
            <el-icon :size="16"><Box /></el-icon>
            <span class="asset__name">{{ detail.assetName }}</span>
            <span class="spacer" />
            <el-tag v-if="assetMeta" size="small" effect="plain" :type="assetMeta.tagType">
              {{ detail.assetStatusLabel }}
            </el-tag>
          </div>
          <div class="asset__meta">
            <span class="mono">{{ detail.assetNo }}</span>
            <span v-if="detail.assetCategoryName">· {{ detail.assetCategoryName }}</span>
            <span class="spacer" />
            <span class="link">查看资产详情 →</span>
          </div>
        </div>

        <!-- 评论区 -->
        <div class="panel">
          <div class="panel__title">
            <el-icon :size="15"><ChatDotRound /></el-icon>
            评论（{{ detail.comments.length }}）
          </div>

          <div v-if="!detail.comments.length" class="muted empty">还没有评论</div>

          <ul v-else class="comments">
            <li v-for="c in detail.comments" :key="c.id" class="comment">
              <el-avatar :size="26" class="comment__avatar">{{ (c.userName || '?').slice(0, 1) }}</el-avatar>
              <div class="comment__body">
                <div class="comment__head">
                  <span class="comment__user">{{ c.userName || '未知用户' }}</span>
                  <span class="muted comment__time">{{ c.createTime }}</span>
                  <span class="spacer" />
                  <el-button
                    v-if="c.canDelete"
                    text
                    size="small"
                    type="danger"
                    :icon="Delete"
                    @click="removeComment(c)"
                  />
                </div>
                <div class="comment__text">{{ c.content }}</div>
                <div v-if="c.mentionNames.length" class="comment__mentions">
                  提及：
                  <el-tag v-for="n in c.mentionNames" :key="n" size="small" effect="plain" type="primary">
                    @{{ n }}
                  </el-tag>
                </div>
              </div>
            </li>
          </ul>

          <div v-if="editable" class="editor">
            <el-input
              v-model="draft"
              type="textarea"
              :rows="2"
              maxlength="2000"
              placeholder="写点什么……用 @ 可以提到项目成员"
            />
            <div class="editor__foot">
              <el-dropdown v-model:visible="mentionOpen" trigger="click">
                <el-button text size="small" :icon="Position">提及成员</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      v-for="m in mentionCandidates"
                      :key="m.userId"
                      @click="appendMention(m)"
                    >
                      {{ m.nickname || m.username }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <span class="muted editor__hint">被 @ 的成员会收到站内通知</span>
              <span class="spacer" />
              <el-button type="primary" size="small" :loading="posting" @click="submitComment">
                发表
              </el-button>
            </div>
          </div>
          <div v-else class="muted empty">你的角色是只读，不能发表评论</div>
        </div>

        <!-- 状态变更日志 -->
        <div class="panel">
          <div class="panel__title">状态变更日志</div>
          <el-timeline v-if="detail.statusLogs.length">
            <el-timeline-item
              v-for="log in detail.statusLogs"
              :key="log.id"
              :timestamp="log.createTime || ''"
              placement="top"
              :color="TASK_COLUMNS.find((c) => c.value === log.toStatus)?.color || '#909399'"
            >
              <span class="log__move">
                {{ log.fromLabel || '新建' }} → {{ log.toLabel }}
              </span>
              <span class="muted"> · {{ log.operatorName || '未知' }}</span>
            </el-timeline-item>
          </el-timeline>
          <div v-else class="muted empty">暂无状态变更</div>
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer {
  display: grid;
  gap: 14px;
}

.panel {
  padding: 12px 13px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-card);
}

.panel__title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 600;
}

.row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.spacer {
  flex: 1;
}

.desc {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  margin-top: 12px;
  font-size: 12.5px;
}

.grid__item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.grid__item > .muted {
  min-width: 62px;
}

.danger {
  color: var(--danger);
  font-weight: 600;
}

.asset {
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.asset:hover {
  border-color: var(--accent);
}

.asset__head {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
}

.asset__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.asset__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 7px;
  font-size: 12px;
  color: var(--text-muted);
}

.link {
  color: var(--accent);
}

.comments {
  display: grid;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.comment {
  display: flex;
  gap: 9px;
}

.comment__avatar {
  flex: none;
  background: var(--bg-hover);
  color: var(--text-primary);
  font-size: 12px;
}

.comment__body {
  flex: 1;
  min-width: 0;
}

.comment__head {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
}

.comment__user {
  font-weight: 600;
}

.comment__time {
  font-size: 11.5px;
}

.comment__text {
  margin-top: 3px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.comment__mentions {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 5px;
  font-size: 11.5px;
  color: var(--text-muted);
}

.editor {
  margin-top: 12px;
  display: grid;
  gap: 7px;
}

.editor__foot {
  display: flex;
  align-items: center;
  gap: 8px;
}

.editor__hint {
  font-size: 11.5px;
}

.empty {
  font-size: 12.5px;
}

.log__move {
  font-size: 13px;
  font-weight: 600;
}
</style>
