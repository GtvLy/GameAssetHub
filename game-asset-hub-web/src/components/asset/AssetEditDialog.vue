<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { updateAsset } from '@/api/asset'
import { ASSET_PRIORITIES } from '@/constants/asset'
import type { AssetCategoryVO, AssetPriority, AssetUpdateDTO, AssetVO } from '@/types/asset'

/**
 * 编辑资产信息（不含文件本身）。
 *
 * 与「上传新版本」的分工：版本承载文件与三维元数据，这里的名称 / 分类 /
 * 优先级 / 标签是**管理属性**，改了不需要产生新版本——否则「把优先级从
 * P2 调到 P0」也会刷出一个 V7，时间线很快被这类噪音填满。
 */
const props = defineProps<{
  modelValue: boolean
  asset: AssetVO | null
  categories: AssetCategoryVO[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved', asset: AssetVO): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

/** 标签在表单里是一个逗号分隔的字符串（后端 tags 就是字符串），不进表单数组 */
interface EditForm {
  name: string
  description: string
  categoryId: number | null
  priority: AssetPriority
  tagsText: string
}

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive<EditForm>({
  name: '',
  description: '',
  categoryId: null,
  priority: 'P2',
  tagsText: ''
})

const rules: FormRules = {
  // 名称是后端 AssetUpdateDTO 里唯一的必填项，且它会显示在列表、时间线、
  // 通知里，留空会让这些地方出现一片无法定位的空白
  name: [
    { required: true, whitespace: true, message: '请输入资产名称', trigger: 'blur' },
    { max: 100, message: '名称不超过 100 个字符', trigger: 'blur' }
  ]
}

/** 分类树的字段映射：后端 VO 用 name / children，Element 默认认 label / children */
const treeProps = { label: 'name', children: 'children' } as const

/**
 * 标签的规范化：中英文逗号都当分隔符（美术在中文输入法下敲出的「，」很常见），
 * 去空白、去空项、去重后统一用半角逗号拼回——
 * 服务端也是按逗号 split 的，不规范化就会出现「一个标签里带着空格」这种脏数据
 */
function normalizeTags(text: string): string {
  const parts = text
    .split(/[,，]/)
    .map((t) => t.trim())
    .filter(Boolean)
  return Array.from(new Set(parts)).join(',')
}

function fillForm(asset: AssetVO) {
  form.name = asset.name
  form.description = asset.description ?? ''
  form.categoryId = asset.categoryId
  form.priority = asset.priority
  form.tagsText = asset.tagList.length ? asset.tagList.join(', ') : (asset.tags ?? '')
}

// 对话框复用同一个实例，不重新填表就会残留上一个资产的数据——
// 这类错误在界面上看起来完全正常，只有点了保存才会发现改错了对象
watch(
  () => props.modelValue,
  (open) => {
    if (!open || !props.asset) return
    fillForm(props.asset)
    // 上一次的红字校验提示要一并清掉，否则刚打开就一派「请修正」的模样
    void nextTick(() => formRef.value?.clearValidate())
  }
)

async function submit() {
  const source = props.asset
  if (!source) return

  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  // 只提交改动过的字段：后端对 DTO 里的 null 字段按「这一项不动」处理，
  // 把没改的字段一并带上，等于用打开对话框那一刻的旧值去覆盖别人的修改
  const dto: AssetUpdateDTO = { name: form.name.trim() }

  if (form.description.trim() !== (source.description ?? '')) {
    dto.description = form.description.trim() || null
  }
  if (form.categoryId !== source.categoryId) {
    dto.categoryId = form.categoryId
  }
  if (form.priority !== source.priority) {
    dto.priority = form.priority
  }
  const tags = normalizeTags(form.tagsText)
  if (tags !== normalizeTags(source.tags ?? '')) {
    dto.tags = tags || null
  }

  if (Object.keys(dto).length === 1) {
    // 只剩必填的 name 说明用户其实什么都没改，不必发这一次请求
    ElMessage.info('没有需要保存的修改')
    visible.value = false
    return
  }

  submitting.value = true
  try {
    const vo = await updateAsset(source.id, dto)
    ElMessage.success('资产信息已保存')
    emit('saved', vo)
    visible.value = false
  } catch {
    /* 拦截器已提示（改名撞车、分类已被删除等），保留用户输入以便改完再提交 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="编辑资产信息" width="560px" append-to-body :close-on-click-modal="false">
    <template v-if="asset">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="84px">
        <el-form-item label="资产名称" prop="name">
          <el-input v-model="form.name" maxlength="100" show-word-limit placeholder="例如：主角·轻甲套装" />
        </el-form-item>

        <el-form-item label="所属分类" prop="categoryId">
          <!-- check-strictly：一级分类本身也可选（资产允许直接挂在一级分类下），
               默认的级联勾选会把父节点变成「全选/半选」而不是一个可选值 -->
          <el-tree-select
            v-model="form.categoryId"
            :data="categories"
            :props="treeProps"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            clearable
            filterable
            style="width: 100%"
            placeholder="不选则保持原分类"
          />
        </el-form-item>

        <el-form-item label="优先级" prop="priority">
          <el-select v-model="form.priority" style="width: 100%">
            <el-option
              v-for="option in ASSET_PRIORITIES"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            >
              <div class="priority-option">
                <span>{{ option.value }} · {{ option.label }}</span>
                <span class="muted priority-option__desc">{{ option.description }}</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item label="标签" prop="tagsText">
          <el-input
            v-model="form.tagsText"
            placeholder="用逗号分隔，例如：角色, 主角, 已绑定骨骼"
          />
          <div class="hint muted">标签用于跨分类检索，建议不超过 5 个。</div>
        </el-form-item>

        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="这份资产的用途、注意事项、面数预算等"
          />
        </el-form-item>
      </el-form>

      <div class="footnote muted">
        资产编号 <span class="mono">{{ asset.assetNo }}</span> 由系统按分类前缀生成，不可修改；
        文件本身请通过「上传新版本」更新，那会留下版本记录。
      </div>
    </template>

    <el-empty v-else :image-size="60" description="没有可编辑的资产" />

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!asset" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.priority-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.priority-option__desc {
  font-size: 11.5px;
}

.hint {
  margin-top: 4px;
  font-size: 11.5px;
  line-height: 1.6;
}

.footnote {
  padding-top: 12px;
  border-top: 1px dashed var(--border);
  font-size: 12px;
  line-height: 1.7;
}
</style>
