<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createTask, fetchTask, updateTask } from '@/api/task'
import { fetchAssets } from '@/api/asset'
import { TASK_COLUMNS, TASK_PRIORITIES, TASK_TYPES } from '@/constants/task'
import type { ProjectMemberVO } from '@/types'
import type { AssetVO } from '@/types/asset'
import type { TaskCardVO, TaskCreateDTO, TaskType, TaskPriority, TaskColumn } from '@/types/task'

/**
 * 新建 / 编辑任务对话框（文档 7.2）。
 *
 * 两种模式共用一套表单：
 *   - 新建：可指定初始列（后端会把它追加到该列列尾）；
 *   - 编辑：**不含列**——改列只走拖拽（move）那条路径。理由与后端
 *     `TaskUpdateDTO` 的注释一致：走 update 改列会绕开排序值、
 *     finished_at、状态日志与状态变更通知，早晚漏掉其中一项。
 */
const props = defineProps<{
  modelValue: boolean
  projectId: number
  /** 传入表示编辑，null 表示新建 */
  task: TaskCardVO | null
  members: ProjectMemberVO[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved', taskId: number): void
}>()

const formRef = ref<FormInstance>()
const saving = ref(false)
const assets = ref<AssetVO[]>([])
const loadingAssets = ref(false)

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const isEdit = computed(() => !!props.task)

const form = reactive({
  title: '',
  description: '',
  columnStatus: 'TODO' as TaskColumn,
  priority: 'P2' as TaskPriority,
  taskType: 'ART' as TaskType,
  assigneeId: null as number | null,
  dueDate: null as string | null,
  estimateHours: null as number | null,
  actualHours: null as number | null,
  storyPoint: null as number | null,
  assetId: null as number | null
})

const rules: FormRules = {
  title: [
    { required: true, message: '任务标题不能为空', trigger: 'blur' },
    { max: 200, message: '任务标题不能超过 200 个字符', trigger: 'blur' }
  ]
}

/** 只有 ACTIVE 成员能被指派：与后端 requireAssignee 的校验保持一致 */
const assignableMembers = computed(() => props.members.filter((m) => m.status === 'ACTIVE'))

async function loadAssets() {
  if (assets.value.length) return
  loadingAssets.value = true
  try {
    // 关联资产用下拉选择：一页 200 条足够覆盖演示与常见的项目规模，
    // 真要有上千资产，这里应该换成带搜索的远程选择器
    const page = await fetchAssets(props.projectId, { page: 1, size: 200 })
    assets.value = page.records ?? []
  } catch {
    assets.value = []
  } finally {
    loadingAssets.value = false
  }
}

function resetForm() {
  form.title = ''
  form.description = ''
  form.columnStatus = 'TODO'
  form.priority = 'P2'
  form.taskType = 'ART'
  form.assigneeId = null
  form.dueDate = null
  form.estimateHours = null
  form.actualHours = null
  form.storyPoint = null
  form.assetId = null
}

/** 编辑时回填：卡片上只有部分字段（没有 description / actualHours），再取一次详情 */
async function fillForm() {
  if (!props.task) {
    resetForm()
    return
  }
  try {
    const detail = await fetchTask(props.task.id)
    form.title = detail.title
    form.description = detail.description ?? ''
    form.columnStatus = detail.columnStatus
    form.priority = detail.priority
    form.taskType = detail.taskType
    form.assigneeId = detail.assigneeId
    form.dueDate = detail.dueDate
    form.estimateHours = detail.estimateHours
    form.actualHours = detail.actualHours
    form.storyPoint = detail.storyPoint
    form.assetId = detail.assetId
  } catch {
    // 详情拉不到（例如任务刚被别人删了）时退回用卡片上的字段，表单仍可用
    form.title = props.task.title
    form.description = ''
    form.columnStatus = props.task.columnStatus
    form.priority = props.task.priority
    form.taskType = props.task.taskType
    form.assigneeId = props.task.assigneeId
    form.dueDate = props.task.dueDate
    form.estimateHours = props.task.estimateHours
    form.actualHours = props.task.actualHours
    form.storyPoint = props.task.storyPoint
    form.assetId = props.task.assetId
  }
}

watch(visible, async (open) => {
  if (!open) return
  await loadAssets()
  await fillForm()
})

async function handleSubmit() {
  const form$ = formRef.value
  if (form$) {
    const ok = await form$.validate().catch(() => false)
    if (!ok) return
  }
  saving.value = true
  try {
    let taskId: number
    if (isEdit.value && props.task) {
      const updated = await updateTask(props.task.id, {
        title: form.title.trim(),
        description: form.description || null,
        priority: form.priority,
        taskType: form.taskType,
        assigneeId: form.assigneeId,
        dueDate: form.dueDate,
        estimateHours: form.estimateHours,
        actualHours: form.actualHours,
        storyPoint: form.storyPoint,
        assetId: form.assetId
      })
      taskId = updated.id
      ElMessage.success('任务已更新')
    } else {
      const payload: TaskCreateDTO = {
        title: form.title.trim(),
        description: form.description || null,
        columnStatus: form.columnStatus,
        priority: form.priority,
        taskType: form.taskType,
        assigneeId: form.assigneeId,
        dueDate: form.dueDate,
        estimateHours: form.estimateHours,
        storyPoint: form.storyPoint,
        assetId: form.assetId
      }
      const created = await createTask(props.projectId, payload)
      taskId = created.id
      ElMessage.success('任务已创建')
    }
    visible.value = false
    emit('saved', taskId)
  } catch {
    // 拦截器已提示（400 的参数错误、409 的并发冲突都在那里统一处理）
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="isEdit ? '编辑任务' : '新建任务'" width="640px" top="6vh">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="一句话说清要做什么" />
      </el-form-item>

      <el-form-item label="描述">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          placeholder="补充验收标准、参考链接等"
        />
      </el-form-item>

      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="类型">
            <el-select v-model="form.taskType" style="width: 100%">
              <el-option v-for="t in TASK_TYPES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="优先级">
            <el-select v-model="form.priority" style="width: 100%">
              <el-option
                v-for="p in TASK_PRIORITIES"
                :key="p.value"
                :label="`${p.value} ${p.label}`"
                :value="p.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="负责人">
            <el-select v-model="form.assigneeId" clearable placeholder="暂不指派" style="width: 100%">
              <el-option
                v-for="m in assignableMembers"
                :key="m.userId"
                :label="`${m.nickname || m.username}（${m.roleLabel}）`"
                :value="m.userId"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="截止日期">
            <el-date-picker
              v-model="form.dueDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="不设截止"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="12">
        <el-col :span="8">
          <el-form-item label="预估工时">
            <el-input-number v-model="form.estimateHours" :min="0" :max="999" :precision="1" :step="0.5" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item v-if="isEdit" label="实际工时">
            <el-input-number v-model="form.actualHours" :min="0" :max="999" :precision="1" :step="0.5" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="故事点">
            <el-input-number v-model="form.storyPoint" :min="0" :max="999" style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item v-if="!isEdit" label="初始列">
        <el-radio-group v-model="form.columnStatus">
          <el-radio-button v-for="c in TASK_COLUMNS" :key="c.value" :value="c.value">
            {{ c.label }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="关联资产">
        <el-select
          v-model="form.assetId"
          clearable
          filterable
          :loading="loadingAssets"
          placeholder="不关联资产（策划与排期类任务通常不需要）"
          style="width: 100%"
        >
          <el-option
            v-for="a in assets"
            :key="a.id"
            :label="`${a.assetNo} · ${a.name}`"
            :value="a.id"
          >
            <span class="mono muted">{{ a.assetNo }}</span>
            <span style="margin-left: 8px">{{ a.name }}</span>
          </el-option>
        </el-select>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSubmit">
        {{ isEdit ? '保存' : '创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>
