<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { createCategory, deleteCategory, updateCategory } from '@/api/asset'
import type { AssetCategoryDTO, AssetCategoryVO } from '@/types/asset'

/**
 * 分类维护抽屉（树形增删改）。
 *
 * 数据归属：树本身由父页面持有（useCategoryTree），这里只渲染 + 发指令。
 * 每次增删改后 emit('changed')，父页面 reload 后把新树传回来——
 * 这样抽屉里看到的和资产列表用的一定是同一份数据，不会出现两处各自缓存、
 * 一边改了另一边还显示旧分类名的情况。
 */
const props = defineProps<{
  modelValue: boolean
  projectId: number
  tree: AssetCategoryVO[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  /** 分类发生增删改，父页面据此 reload() 分类树并刷新资产列表 */
  (e: 'changed'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

/** 字段映射常量放脚本里：写成模板内联对象的话每次渲染都是新引用，el-tree 会重新初始化 */
const TREE_PROPS = { label: 'name', children: 'children' }

/**
 * 分类变动后父组件会传下来一个新的 tree 引用，这里用 key 强制重建 el-tree。
 * 原因：default-expand-all 只在树首次创建时生效，重建后才能保证
 * 新增的二级分类立刻可见——刚建完却看不到，用户会以为没建成功。
 */
const treeKey = ref(0)
watch(
  () => props.tree,
  () => {
    treeKey.value += 1
  }
)

const totalCount = computed(() =>
  props.tree.reduce((sum, top) => sum + 1 + (top.children?.length ?? 0), 0)
)

// ==================== 表单 ====================

interface CategoryForm {
  name: string
  code: string
  icon: string
  color: string
  sortOrder: number
}

const formVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
/** 正在编辑的分类；为 null 表示新建 */
const editing = ref<AssetCategoryVO | null>(null)
/** 新建时的父级ID：0 = 一级分类（与后端约定一致） */
const parentId = ref(0)
/** 父级展示名，编辑时用来告诉用户「这个分类挂在哪」 */
const parentLabel = ref('（一级分类）')

const form = reactive<CategoryForm>({
  name: '',
  code: '',
  icon: '',
  color: '',
  sortOrder: 0
})

const formRules: FormRules = {
  name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入分类编码', trigger: 'blur' },
    {
      // 编码会成为资产编号的前缀（CHR_SM_0001），所以只允许大写字母、
      // 数字与下划线：小写和短横线在不同系统间传递时容易被规范化掉
      pattern: /^[A-Z0-9_]+$/,
      message: '只能是大写字母、数字与下划线，如 CHR_SM',
      trigger: 'blur'
    }
  ]
}

function resetForm() {
  form.name = ''
  form.code = ''
  form.icon = ''
  form.color = ''
  form.sortOrder = 0
}

/** 新建：parent 为 null 时建一级分类 */
function openCreate(parent: AssetCategoryVO | null) {
  editing.value = null
  parentId.value = parent?.id ?? 0
  parentLabel.value = parent ? `${parent.name} / 新子分类` : '（一级分类）'
  resetForm()
  formVisible.value = true
}

function openEdit(node: AssetCategoryVO) {
  editing.value = node
  parentId.value = node.parentId
  parentLabel.value = node.parentId === 0 ? '（一级分类）' : findParentName(node.parentId)
  form.name = node.name
  form.code = node.code
  form.icon = node.icon ?? ''
  form.color = node.color ?? ''
  form.sortOrder = node.sortOrder ?? 0
  formVisible.value = true
}

function findParentName(id: number): string {
  return props.tree.find((top) => top.id === id)?.name ?? '（上级分类）'
}

async function submitForm() {
  const instance = formRef.value
  if (!instance) return
  const valid = await instance.validate().catch(() => false)
  if (!valid) return

  const payload: AssetCategoryDTO = {
    // 编辑时原样回传 parentId：虽然界面上不让改上级（把一级分类挂到自己的
    // 子分类下会成环，后端也拦），但不传的话后端会把它当成「移动到顶级」
    parentId: editing.value ? editing.value.parentId : parentId.value,
    name: form.name.trim(),
    code: form.code.trim().toUpperCase(),
    icon: form.icon.trim() || null,
    color: form.color || null,
    sortOrder: form.sortOrder
  }

  submitting.value = true
  try {
    if (editing.value) {
      await updateCategory(props.projectId, editing.value.id, payload)
      ElMessage.success('分类已更新')
    } else {
      await createCategory(props.projectId, payload)
      ElMessage.success('分类已创建')
    }
    formVisible.value = false
    emit('changed')
  } catch {
    /* 拦截器已提示（同级编码重复 / 层级超限 / 非 OWNER），表单保持打开让用户改 */
  } finally {
    submitting.value = false
  }
}

/**
 * 删除。
 *
 * 这里刻意**不**在前端判断「有没有子分类 / 有没有资产」：
 * 这类校验只能有一份实现，写在后端服务层（在同一个事务里做才可靠）。
 * 前端再抄一份，两边一旦不一致就会出现两种坏结果——
 * 「前端说能删、后端拒绝」用户白点一次，或者更糟的
 * 「前端拦住了一个后端允许的操作」。所以直接把后端的拒绝信息展示出来。
 */
async function handleDelete(node: AssetCategoryVO) {
  try {
    await ElMessageBox.confirm(
      `确定要删除分类「${node.name}」吗？` +
        '若该分类下还有子分类或资产，后端会拒绝删除，需要先把它们移走。',
      '删除分类',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await deleteCategory(props.projectId, node.id)
    ElMessage.success('分类已删除')
    emit('changed')
  } catch {
    /* 拦截器已提示（分类下还有资产 / 子分类） */
  }
}
</script>

<template>
  <el-drawer v-model="visible" title="分类管理" size="580px">
    <div class="cat">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="分类是项目级的公共结构"
        description="所有资产都挂在分类下，资产编号以分类编码为前缀。因此分类只允许项目负责人维护，避免多人各建一套、口径对不上。"
      />

      <div class="cat__toolbar">
        <el-button type="primary" :icon="Plus" @click="openCreate(null)">新建一级分类</el-button>
        <span class="muted cat__count">共 {{ totalCount }} 个分类（两级）</span>
      </div>

      <el-tree
        :key="treeKey"
        :data="tree"
        node-key="id"
        :props="TREE_PROPS"
        default-expand-all
        :expand-on-click-node="false"
        empty-text="还没有分类，先新建一个一级分类"
        class="cat__tree"
      >
        <template #default="{ data }">
          <div class="node">
            <span
              class="node__swatch"
              :class="{ 'node__swatch--empty': !data.color }"
              :style="data.color ? { background: data.color } : undefined"
            />

            <span class="node__name">{{ data.name }}</span>
            <span class="node__code mono">{{ data.code }}</span>

            <el-tag size="small" type="info" effect="plain">
              {{ data.assetCount ?? 0 }} 个资产
            </el-tag>

            <span class="node__spacer" />

            <!-- 只支持两级，所以「添加子分类」只在一级分类上出现；
                 parentId === 0 由后端保证，比用树的层级推断更可靠 -->
            <el-button v-if="data.parentId === 0" text size="small" @click.stop="openCreate(data)">
              添加子分类
            </el-button>
            <el-button text size="small" @click.stop="openEdit(data)">编辑</el-button>
            <el-button text size="small" type="danger" @click.stop="handleDelete(data)">
              删除
            </el-button>
          </div>
        </template>
      </el-tree>
    </div>

    <!-- append-to-body：抽屉自身是一个定位层，不脱出去的话
         对话框会被压在下层，且遮罩只盖住抽屉 -->
    <el-dialog
      v-model="formVisible"
      :title="editing ? '编辑分类' : '新建分类'"
      width="520px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="88px">
        <el-form-item label="上级分类">
          <div class="parent">
            <span>{{ parentLabel }}</span>
            <span v-if="editing" class="muted parent__hint">
              分类的上下级关系不支持调整，需要换位置请新建后再删除旧分类
            </span>
          </div>
        </el-form-item>

        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="如：角色、主角模型" maxlength="30" show-word-limit />
        </el-form-item>

        <el-form-item label="编码" prop="code">
          <el-input v-model="form.code" placeholder="CHR_SM" />
          <div class="hint muted">作为资产编号前缀，如 CHR_SM 会生成 CHR_SM_0001</div>
        </el-form-item>

        <el-form-item label="图标">
          <el-input v-model="form.icon" placeholder="可选，图标名（如 Brush / User）" />
          <div class="hint muted">先做成文本，后续接图标集时直接换成选择器</div>
        </el-form-item>

        <el-form-item label="颜色">
          <div class="row">
            <el-color-picker v-model="form.color" />
            <span class="hint muted">用于卡片占位底色与分类标记</span>
          </div>
        </el-form-item>

        <el-form-item label="排序">
          <div class="row">
            <el-input-number
              v-model="form.sortOrder"
              :min="0"
              :max="9999"
              controls-position="right"
              class="sort-input"
            />
            <span class="hint muted">数字越小越靠前</span>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">
          {{ editing ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.cat {
  display: grid;
  gap: 16px;
}

.cat__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.cat__count {
  font-size: 12px;
}

.cat__tree {
  /* 树本身不需要滚动容器：抽屉已经滚了，再套一层会出现内外双滚动条 */
  background: transparent;
}

.node {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding-right: 6px;
  min-width: 0;
}

.node__swatch {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  flex: none;
}

.node__swatch--empty {
  border: 1px dashed var(--border);
}

.node__name {
  font-size: 13.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node__code {
  font-size: 11px;
  color: var(--text-muted);
  padding: 1px 6px;
  border: 1px solid var(--border);
  border-radius: 5px;
  flex: none;
}

.node__spacer {
  flex: 1;
  min-width: 8px;
}

.parent {
  display: grid;
  gap: 2px;
}

.parent__hint {
  font-size: 11.5px;
  line-height: 1.5;
}

.hint {
  font-size: 11.5px;
  line-height: 1.6;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.sort-input {
  width: 130px;
}
</style>
