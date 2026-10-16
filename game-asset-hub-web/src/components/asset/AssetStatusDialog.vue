<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Right } from '@element-plus/icons-vue'
import { changeAssetStatus } from '@/api/asset'
import { assetStatusMeta, requiresRemark, transitionLabel } from '@/constants/asset'
import type { AssetStatus, AssetVO } from '@/types/asset'

/**
 * 状态流转确认对话框。
 *
 * 目标状态由调用方从 `asset.nextStatuses` 里挑一个传进来——
 * 这里**不判断这次流转合不合法**，合法性只有后端的状态机说了算，
 * 前端再写一份判断只会多出一处需要同步维护的真相。
 * 本组件的职责只有三件：把「这次变更意味着什么」讲清楚、
 * 收齐审核意见、把请求发出去。
 */
const props = defineProps<{
  modelValue: boolean
  asset: AssetVO | null
  /** 目标状态，由调用方从 nextStatuses 里选一个 */
  toStatus: AssetStatus | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  /** 流转成功，父组件据此刷新详情与历史 */
  (e: 'changed', asset: AssetVO): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive<{ remark: string }>({ remark: '' })

const fromMeta = computed(() => assetStatusMeta(props.asset?.status))
const toMeta = computed(() => assetStatusMeta(props.toStatus))

/** 只有「打回」是必填审核意见的动作，与后端校验保持一致 */
const remarkRequired = computed(() => (props.toStatus ? requiresRemark(props.toStatus) : false))

/** 标题与按钮用「动作」名而不是目标状态名：REJECTED→PENDING 是「重新提交」而非「待审核」 */
const actionLabel = computed(() =>
  props.toStatus ? transitionLabel(props.asset?.status, props.toStatus) : '状态流转'
)

const confirmText = computed(() => (remarkRequired.value ? '打回并记录意见' : `确定${actionLabel.value}`))

/** whitespace 一并交给校验器：只敲了几个空格的「意见」留痕等于没留，必须拦下 */
const rules = computed<FormRules>(() => ({
  remark: remarkRequired.value
    ? [
        {
          required: true,
          whitespace: true,
          message: '打回必须写清问题所在，否则美术不知道要改什么',
          trigger: 'blur'
        }
      ]
    : []
}))

const placeholder = computed(() =>
  remarkRequired.value
    ? '例如：肩甲布线出现明显三角面，请重做后再提交；护腕贴图接缝错位'
    : '可选：补充本次变更的说明，会一并记入流转历史'
)

// 对话框是复用同一个实例的，不重置就会出现「打开打回、再打开提交审核时
// 上一次的意见还在、红字校验也还在」的情况
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    form.remark = ''
    // 对话框内容这一刻还没挂载完，表单实例要等一帧才拿得到
    void nextTick(() => formRef.value?.clearValidate())
  }
)

async function submit() {
  const target = props.toStatus
  const current = props.asset
  if (!target || !current) return

  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  // 废弃在本系统里是单向的（没有「取消废弃」这个动作），因此多一道确认。
  // 文案要说明历史版本仍在，否则美术会以为废弃等于删除而不敢点
  if (target === 'DEPRECATED') {
    try {
      await ElMessageBox.confirm(
        `废弃「${current.name}」后，它不会再被当作可用资源，也不能再流转回制作中。` +
          '但历史版本一个都不会删，仍可下载与追溯。确定废弃吗？',
        '废弃确认',
        { confirmButtonText: '确定废弃', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return
    }
  }

  submitting.value = true
  try {
    const vo = await changeAssetStatus(current.id, {
      toStatus: target,
      // 选填项留空时传 null 而不是空串：后端按 null 判定「没有意见」，
      // 空串会在时间线上留下一片没有内容的空白行
      remark: form.remark.trim() || null
    })
    ElMessage.success(`状态已变更为「${assetStatusMeta(vo.status).label}」`)
    emit('changed', vo)
    visible.value = false
  } catch {
    // 拦截器已统一提示。这里**故意不关闭**对话框：失败常见于
    // 「状态已被他人流转」（409）或越权，保留用户已经写好的意见，
    // 让他刷新后能直接再提交一次；父页面在对话框关闭时会重新拉取详情
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="actionLabel"
    width="580px"
    append-to-body
    :close-on-click-modal="false"
  >
    <template v-if="asset && toStatus">
      <!-- 用色块 + 说明文字把「这次变更意味着什么」摊开：
           只写「制作中 → 已打回」用户未必清楚后果，状态说明是从筛选器
           到详情页都在复用的一份文案，口径统一 -->
      <div class="flow">
        <div class="flow__side">
          <div class="flow__head">
            <span class="flow__dot" :style="{ background: fromMeta.color }" />
            <span class="flow__label">{{ fromMeta.label }}</span>
          </div>
          <p class="flow__desc muted">{{ fromMeta.description }}</p>
        </div>

        <el-icon class="flow__arrow" :size="20"><Right /></el-icon>

        <div class="flow__side">
          <div class="flow__head">
            <span class="flow__dot" :style="{ background: toMeta.color }" />
            <span class="flow__label">{{ toMeta.label }}</span>
          </div>
          <p class="flow__desc muted">{{ toMeta.description }}</p>
        </div>
      </div>

      <div class="subject">
        <span class="mono subject__no">{{ asset.assetNo }}</span>
        <span class="subject__name">{{ asset.name }}</span>
      </div>

      <el-alert
        v-if="remarkRequired"
        class="tip"
        type="warning"
        :closable="false"
        show-icon
        title="打回必须写清问题，否则美术不知道要改什么"
        description="这条意见会原样保存在流转历史里，之后每次回看这一版都能看到，请写具体位置与现象。"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-width="84px" class="form">
        <el-form-item label="审核意见" prop="remark">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            :placeholder="placeholder"
          />
        </el-form-item>
      </el-form>
    </template>

    <el-empty v-else :image-size="60" description="没有可执行的流转动作" />

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!asset || !toStatus"
        @click="submit"
      >
        {{ confirmText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.flow {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-panel);
}

.flow__side {
  flex: 1;
  min-width: 0;
}

.flow__head {
  display: flex;
  align-items: center;
  gap: 7px;
}

.flow__dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex: none;
}

.flow__label {
  font-size: 14px;
  font-weight: 600;
}

.flow__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.flow__arrow {
  flex: none;
  color: var(--text-muted);
}

.subject {
  display: flex;
  align-items: baseline;
  gap: 9px;
  margin: 14px 0 4px;
}

.subject__no {
  font-size: 12px;
  color: var(--text-muted);
}

.subject__name {
  font-size: 13.5px;
  font-weight: 600;
}

.tip {
  margin-top: 12px;
}

.form {
  margin-top: 14px;
}
</style>
