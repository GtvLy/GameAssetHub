<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Document, FolderOpened } from '@element-plus/icons-vue'
import { checkMd5, createAsset, uploadVersion } from '@/api/asset'
import { ASSET_FILE_HINTS, ASSET_PRIORITIES, CHUNK_UPLOAD_THRESHOLD } from '@/constants/asset'
import { flattenCategories } from '@/composables/useCategoryTree'
import {
  UploadCancelledError,
  assertUploadable,
  completeVersionUpload,
  computeFileMd5,
  humanSize,
  uploadFileInChunks,
  type UploadPhase,
  type UploadProgress
} from '@/utils/upload'
import type {
  AssetCategoryVO,
  AssetCreateDTO,
  AssetPriority,
  AssetVO,
  AssetVersionVO,
  Md5CheckVO,
  UploadVersionDTO
} from '@/types/asset'

/**
 * 资产上传对话框（文档 7.4 / 7.5 / 7.6）。
 *
 * 一个对话框承担两件事：**新建资产（含首个版本）** 与 **给已有资产上传新版本**。
 * 二者共用绝大多数表单字段与整条上传链路，只在「元数据装着哪些字段」和
 * 「最后调哪个接口」上分岔，所以不拆成两个组件——拆了以后秒传、分片、
 * 取消这三段逻辑就要复制两份，改一处漏一处。
 *
 * 完整链路：**选文件 → 算指纹 → 秒传检查 → （复用 | 切片上传 | 整包直传）→ 创建记录**。
 * 各步骤的取舍散落在对应函数上方。
 */
const props = defineProps<{
  /** v-model 控制显隐 */
  modelValue: boolean
  projectId: number
  /** null = 新建资产；非 null = 给该资产上传新版本 */
  asset?: AssetVO | null
  /** 分类树，新建资产时用于选择分类（由父组件传入，不要在对话框里再拉一次） */
  categories: AssetCategoryVO[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 上传成功。新建时 version 为 null，上传新版本时 asset 为 null */
  (e: 'saved', payload: { asset: AssetVO | null; version: AssetVersionVO | null }): void
}>()

const isVersionMode = computed(() => props.asset != null)

/** 当前版本号：详情接口不一定带 currentVersion，退回版本总数（版本号就是从 1 连续递增的） */
const currentVersionNo = computed(
  () => props.asset?.currentVersion?.versionNo ?? props.asset?.versionCount ?? 0
)

const dialogTitle = computed(() =>
  isVersionMode.value
    ? `上传新版本 · ${props.asset?.assetNo}（当前 V${currentVersionNo.value}）`
    : '新建资产'
)

// ==================== 表单 ====================

interface UploadForm {
  categoryId: number | null
  name: string
  description: string
  priority: AssetPriority
  tags: string
  versionName: string
  changeLog: string
  /** 用 undefined 而不是 null：el-input-number 的 modelValue 收 `number | undefined`，
      传 null 过不了模板类型检查；真正发请求时再用 ?? null 补成后端认的空值 */
  polyCount: number | undefined
  textureSize: string
  file: File | null
}

const formRef = ref<FormInstance>()
const fileInputRef = ref<HTMLInputElement>()

const form = reactive<UploadForm>({
  categoryId: null,
  name: '',
  description: '',
  priority: 'P2',
  tags: '',
  versionName: '',
  changeLog: '',
  polyCount: undefined,
  textureSize: '',
  file: null
})

const rules = computed<FormRules>(() => ({
  // 新版本挂到已有资产上，分类与名称由资产本身决定，这两种模式各自只校验自己有的字段
  categoryId: isVersionMode.value
    ? []
    : [{ required: true, message: '请选择所属分类', trigger: 'change' }],
  name: isVersionMode.value
    ? []
    : [
        // whitespace 与 required 写在同一条规则里：只在输入全是空格时也算空
        { required: true, whitespace: true, message: '请输入资产名称', trigger: 'blur' },
        { max: 60, message: '资产名称不超过 60 个字符', trigger: 'blur' }
      ],
  file: [{ required: true, message: '请选择要上传的文件', trigger: 'change' }]
}))

/**
 * el-tree-select 的字段映射。分类树的节点用 name / id / children，
 * 与 el-tree 默认的 label / value 不同，必须显式告诉它去哪儿取值。
 * 提到脚本里而不是写在模板上：模板里的对象字面量每次渲染都是新引用，
 * 会让 tree-select 内部依赖它的计算属性反复失效。
 */
const CATEGORY_TREE_PROPS = { label: 'name', value: 'id', children: 'children' }

/** 分类下拉的完整路径与编号前缀提示：编号是后端按「分类编码 + 序号」拼的，先让用户看到前缀 */
const flatCategories = computed(() => flattenCategories(props.categories))
const selectedCategory = computed(
  () => flatCategories.value.find((c) => c.id === form.categoryId) ?? null
)

const priorityOptions = ASSET_PRIORITIES

/** 分片阈值以人话展示，别在界面上再写死一遍「5MB」 */
const chunkThresholdText = humanSize(CHUNK_UPLOAD_THRESHOLD)

// ==================== 上传状态 ====================

const md5 = ref<string | null>(null)
/** 秒传命中时的服务端信息（含它落在哪个资产 / 版本上） */
const dedupe = ref<Md5CheckVO | null>(null)
/** 命中秒传后用户的选择，默认复用：绝大多数情况下用户要的就是「别再传一遍」 */
const duplicateMode = ref<'reuse' | 'upload'>('reuse')

const phase = ref<UploadPhase | null>(null)
const progress = ref<UploadProgress | null>(null)
const uploadMode = ref<'chunked' | 'direct' | 'reuse' | null>(null)

const busy = computed(() => phase.value !== null)

/**
 * 取消句柄用普通变量而不是 ref。
 *
 * 它只被 abort 用，界面状态一律由 phase/progress 驱动，放进响应式系统没有收益；
 * 而 AbortController 是带内部槽的宿主对象，万一被 Proxy 包一层，
 * 调 abort() 时的 this 就错了（类似 Illegal invocation），这类坑不值得踩。
 */
let taskController: AbortController | null = null
/** 本批任务开始时刻，整包直传的速度文案要用（分片与算指纹的速度由 utils/upload.ts 算） */
let taskStartedAt = 0

/** 用户已点取消，但还没找到「没有请求在飞」的时机落地，见 handleTaskProgress */
const cancelRequested = ref(false)

const phaseText = computed(() => {
  if (cancelRequested.value) return '正在取消…'
  switch (phase.value) {
    case 'hashing':
      return '计算文件指纹（MD5）'
    case 'checking':
      return '秒传检查'
    case 'uploading':
      if (uploadMode.value === 'chunked') return '分片上传中'
      if (uploadMode.value === 'reuse') return '秒传复用中'
      return '整包直传中'
    case 'merging':
      return '服务端合并中'
    default:
      return ''
  }
})

/**
 * 「取消上传」按钮能不能点。
 *
 * 算指纹是纯本地读文件，秒传检查与分片上传各是一个独立请求，
 * 三者都能安全中断；唯独整包直传与合并请求在 api 层没有 signal 入口
 * （createAsset / uploadVersion / completeChunkUpload 都只收 onUploadProgress），
 * 中断不了——直传只发生在不足一个分片的小文件上，转瞬即完，
 * 所以这里把按钮置灰并给出说明，而不是摆一个点了没反应的按钮。
 */
const cancelable = computed(() => {
  if (!busy.value || cancelRequested.value) return false
  if (phase.value === 'hashing' || phase.value === 'checking') return true
  return phase.value === 'uploading' && uploadMode.value === 'chunked'
})

/** 进度区第二行的量词：算指纹阶段读的是本地文件，说「已传」会让用户以为在上传 */
const byteLabel = computed(() => (progress.value?.phase === 'hashing' ? '已读' : '已传'))

const submitText = computed(() => (isVersionMode.value ? '上传版本' : '创建资产'))

/** 整包直传的文件是否值得提示「走分片」由后端阈值决定，这里只是给用户看一眼体积 */
const filePlanText = computed(() => {
  const size = form.file?.size ?? 0
  return size > CHUNK_UPLOAD_THRESHOLD
    ? `超过 ${chunkThresholdText}，将自动分片上传`
    : '整包直传'
})

// ==================== 任务生命周期 ====================

/** 开一个新任务：先把上一个断掉（否则两次任务的进度会互相覆盖），再重置取消标记 */
function beginTask(): AbortController {
  taskController?.abort()
  const controller = new AbortController()
  taskController = controller
  taskStartedAt = Date.now()
  cancelRequested.value = false
  return controller
}

function endTask(controller: AbortController) {
  if (taskController === controller) {
    taskController = null
  }
}

/** 把任务收干净：中断在途请求 + 进度区归零。只关对话框或卸载时用，不碰表单内容 */
function stopTask() {
  taskController?.abort()
  taskController = null
  cancelRequested.value = false
  phase.value = null
  progress.value = null
  uploadMode.value = null
}

/**
 * 所有阶段的进度回调统一走这里。
 *
 * 顺带解决「取消要在什么时候落地」这个坑：用户点取消时如果正有分片请求在飞，
 * 直接 abort() 会把那个请求打断，axios 抛的是 CanceledError，
 * 而 api/request.ts 的响应拦截器把它当成网络异常弹一句「网络异常，请稍后重试」——
 * 用户明明是自己点的取消，却收到一条报错，这是不能接受的。
 * utils/upload.ts 的 report() 只在片间（此刻没有请求在飞）回调，
 * 在这里落地 abort，下一次循环顶部的 `signal.aborted` 检查就会抛
 * UploadCancelledError，全程不经过拦截器，也就不弹任何错误。
 */
function handleTaskProgress(next: UploadProgress) {
  progress.value = next
  if (cancelRequested.value) {
    taskController?.abort()
  }
}

/** 整包直传的进度：axios 只给百分比，其余字段按「无分片」补齐 */
function reportDirectProgress(file: File, percent: number) {
  const uploadedBytes = Math.round((file.size * percent) / 100)
  const elapsed = (Date.now() - taskStartedAt) / 1000
  progress.value = {
    phase: 'uploading',
    percent,
    uploadedBytes,
    totalBytes: file.size,
    currentChunk: 1,
    totalChunks: 1,
    // 与 utils/upload.ts 同一口径：样本不足 0.3s 不给速度，否则数字会剧烈跳动
    speedText: elapsed > 0.3 ? `${humanSize(uploadedBytes / elapsed)}/s` : ''
  }
}

// ==================== 选文件 ====================

function pickFile() {
  if (busy.value) return
  fileInputRef.value?.click()
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const picked = input.files?.[0] ?? null
  // 立刻清空 input 的 value：否则再次选中同一个文件时浏览器不触发 change，
  // 而「取消后重选同一文件继续传」正是分片断点续传最自然的用法
  input.value = ''
  if (!picked) return

  // 体积校验在选文件时就做：等算完指纹、传到一半才被后端打回，白等的是用户的带宽
  try {
    assertUploadable(picked)
  } catch (error) {
    // 本地校验的提示拦截器管不到，必须自己弹
    ElMessage.error((error as Error).message)
    return
  }

  form.file = picked
  md5.value = null
  dedupe.value = null
  void startFingerprint(picked)
}

function clearFile() {
  if (busy.value) return
  stopTask()
  form.file = null
  md5.value = null
  dedupe.value = null
  formRef.value?.clearValidate('file')
}

/**
 * 预检结果。
 *
 * 分四种而不是一个布尔值，是因为「要不要给用户提示」在四种情况下各不相同：
 *   - ready     可以继续；
 *   - cancelled 用户自己点的取消，静默；
 *   - failed    本地读文件失败——拦截器只认接口错误，这一种没人提示，必须自己说；
 *   - rejected  秒传检查的接口失败，拦截器已经弹过了，再补一条就是重复。
 */
type FingerprintResult = 'ready' | 'cancelled' | 'failed' | 'rejected'

/**
 * 算指纹 + 秒传检查。
 *
 * 为什么在「选完文件」就做而不是等提交：这两步加起来可能要跑十几秒（2GB 资产），
 * 放在提交之后用户会以为界面卡死；提前做完，秒传命中的提示也能在用户点提交前摆出来，
 * 让他自己决定是复用还是照传一份。
 */
async function prepareFingerprint(
  file: File,
  controller: AbortController
): Promise<FingerprintResult> {
  let stage: 'hashing' | 'checking' = 'hashing'
  try {
    phase.value = 'hashing'
    progress.value = {
      phase: 'hashing',
      percent: 0,
      uploadedBytes: 0,
      totalBytes: file.size,
      currentChunk: 0,
      totalChunks: 0,
      speedText: ''
    }
    // 算指纹的顺序不能颠倒：秒传的全部价值就是「有就别传」，
    // 若等文件传完再让服务端算，最贵的带宽已经花掉了
    const value = await computeFileMd5(
      file,
      (percent) => {
        handleTaskProgress({
          phase: 'hashing',
          percent,
          uploadedBytes: Math.round((file.size * percent) / 100),
          totalBytes: file.size,
          currentChunk: 0,
          totalChunks: 0,
          speedText: ''
        })
      },
      controller.signal
    )
    if (cancelRequested.value) {
      throw new UploadCancelledError()
    }
    md5.value = value

    stage = 'checking'
    phase.value = 'checking'
    // 秒传检查没有字节进度可报，进度条交给 indeterminate 跑，别显示一个假的 0%
    progress.value = null
    dedupe.value = await checkMd5({ md5: value, fileName: file.name, fileSize: file.size })
    // 检查期间点了取消：这个请求只读、无副作用，结果丢掉即可
    if (cancelRequested.value) {
      throw new UploadCancelledError()
    }
    return 'ready'
  } catch (error) {
    // 取消或失败，指纹都不可信
    md5.value = null
    if (isCancelled(error, controller)) {
      return 'cancelled'
    }
    return stage === 'hashing' ? 'failed' : 'rejected'
  } finally {
    phase.value = null
    progress.value = null
  }
}

/** 选完文件后的那次预检，提交时会复用它的结果，不重复算 */
async function startFingerprint(file: File) {
  const controller = beginTask()
  try {
    await prepareFingerprint(file, controller)
  } finally {
    endTask(controller)
    cancelRequested.value = false
  }
}

// ==================== 提交 ====================

/** 标签串归一化：中文输入法下很容易打出全角逗号，后端按半角拆分，这里统一掉 */
function normalizeTags(raw: string): string | null {
  const list = raw
    .split(/[,，]/)
    .map((tag) => tag.trim())
    .filter((tag) => tag.length > 0)
  return list.length ? Array.from(new Set(list)).join(',') : null
}

async function handleSubmit() {
  const formEl = formRef.value
  // 按钮在 busy 时已是 loading（不可点），这里是双保险
  if (!formEl || busy.value) return
  const valid = await formEl.validate().catch(() => false)
  if (!valid) return

  const file = form.file
  if (!file) return

  const controller = beginTask()
  try {
    // 走到这里指纹还是空的，只有一种可能：上一轮算指纹被用户取消了。补算一次
    if (!md5.value) {
      const outcome = await prepareFingerprint(file, controller)
      if (outcome !== 'ready') {
        // 只有本地读文件失败需要补一句：接口失败拦截器已提示，取消则是用户自己的意思
        if (outcome === 'failed') {
          ElMessage.error('文件指纹计算失败，请确认文件可正常读取后重新选择')
        }
        return
      }
      if (dedupe.value?.exists) {
        // 补算时才发现项目里已有同 MD5 文件，把选择权交回用户，
        // 不按默认值偷偷替他决定（他刚才取消的正是这次预检）
        ElMessage.info('项目里已有相同文件，请选择「直接复用」或「仍然上传一份」后再提交')
        return
      }
    }

    // 两种模式共用的版本元数据：UploadVersionDTO 与 AssetCreateDTO 的版本字段完全同形
    const versionMeta: UploadVersionDTO = {
      versionName: form.versionName.trim() || null,
      changeLog: form.changeLog.trim() || null,
      polyCount: form.polyCount ?? null,
      textureSize: form.textureSize.trim() || null,
      // md5 与文件名必须带上：秒传路径靠它复用文件，上传路径靠它做校验与展示
      fileMd5: md5.value,
      fileName: file.name
    }

    const reuse = dedupe.value?.exists === true && duplicateMode.value === 'reuse'

    if (props.asset) {
      const assetId = props.asset.id
      if (reuse) {
        // 复用：不带 file 也不带分片，后端按 meta.fileMd5 直接挂一条新版本
        uploadMode.value = 'reuse'
        phase.value = 'uploading'
        progress.value = null
        const version = await uploadVersion(assetId, versionMeta, null)
        finishWithVersion(version)
      } else if (file.size > CHUNK_UPLOAD_THRESHOLD) {
        const upload = await uploadFileChunks(file, controller)
        if (cancelRequested.value) {
          // 取消正好落在最后一片之后：uploadFileInChunks 的循环已经跑完会正常返回，
          // 不在这里拦一道就会把用户已经取消的任务继续送进合并
          throw new UploadCancelledError()
        }
        // 合并请求没有 signal 入口，进度条也就此定格，取消按钮同步失效
        uploadMode.value = null
        phase.value = 'merging'
        const version = await completeVersionUpload(assetId, upload, versionMeta)
        finishWithVersion(version)
      } else {
        uploadMode.value = 'direct'
        phase.value = 'uploading'
        // 浏览器拿不到 total 时 axios 不会回调进度，先摆一个不确定态比钉死在 0% 诚实
        progress.value = null
        const version = await uploadVersion(assetId, versionMeta, file, {
          onUploadProgress: (percent) => reportDirectProgress(file, percent)
        })
        finishWithVersion(version)
      }
    } else {
      const createMeta: AssetCreateDTO = {
        // 新建模式下分类是必填项，校验已经拦过；转换只为把 number | null 收窄成 number
        categoryId: form.categoryId as number,
        name: form.name.trim(),
        description: form.description.trim() || null,
        priority: form.priority,
        tags: normalizeTags(form.tags),
        ...versionMeta
      }

      if (reuse) {
        uploadMode.value = 'reuse'
        phase.value = 'uploading'
        progress.value = null
        const created = await createAsset(props.projectId, createMeta, {})
        finishWithAsset(created)
      } else if (file.size > CHUNK_UPLOAD_THRESHOLD) {
        const upload = await uploadFileChunks(file, controller)
        if (cancelRequested.value) {
          throw new UploadCancelledError()
        }
        uploadMode.value = null
        phase.value = 'merging'
        // 分片已落在服务端，这里交给后端的合并流程（它同时负责建首个版本）
        const created = await createAsset(props.projectId, createMeta, {
          uploadId: upload.uploadId,
          totalChunks: upload.totalChunks
        })
        finishWithAsset(created)
      } else {
        uploadMode.value = 'direct'
        phase.value = 'uploading'
        progress.value = null
        const created = await createAsset(props.projectId, createMeta, { file }, {
          onUploadProgress: (percent) => reportDirectProgress(file, percent)
        })
        finishWithAsset(created)
      }
    }
  } catch (error) {
    if (isCancelled(error, controller)) {
      // 静默：取消是用户的主动意图，不是失败，也不该弹错误
      return
    }
    // 其余错误（4xx / 5xx / 超时 / 断网）拦截器已统一提示。
    // 这里刻意不关对话框、不清文件：用户改完元数据或换个网络可以直接重试，
    // 已算好的指纹与已上传的分片都还在。
  } finally {
    endTask(controller)
    cancelRequested.value = false
    phase.value = null
    progress.value = null
    uploadMode.value = null
  }
}

/**
 * 分片上传的共用入口，两种模式只差后面调哪个接口。
 *
 * 切片、断点续传、速度统计都在 utils/upload.ts 里做，尤其是
 * 「按服务端 init 下发的 chunkSize 切片」：前后端分片大小不一致时，
 * 合并出来的文件必然是坏的，而症状是「上传成功但模型打不开」，排查方向很容易跑偏，
 * 所以这里不自己算分片数，一切以服务端返回为准。
 */
async function uploadFileChunks(file: File, controller: AbortController) {
  uploadMode.value = 'chunked'
  phase.value = 'uploading'
  progress.value = null
  // 调用前已保证指纹就绪（handleSubmit 里补算过），这里的转换只为收窄类型
  return uploadFileInChunks(file, md5.value as string, {
    onProgress: handleTaskProgress,
    signal: controller.signal
  })
}

/**
 * 是否属于「用户主动取消」。
 *
 * 三种形态都要认：算指纹/片间检查抛的 UploadCancelledError、
 * 由 cancelRequested 提前退出的步骤、以及 signal 已 abort。
 * 只认异常类型会漏掉后两种（它们根本没抛到这一层）。
 */
function isCancelled(error: unknown, controller: AbortController): boolean {
  return error instanceof UploadCancelledError || cancelRequested.value || controller.signal.aborted
}

function finishWithAsset(created: AssetVO) {
  ElMessage.success(`资产 ${created.assetNo} 创建成功，首个版本已入库`)
  emit('saved', { asset: created, version: null })
  resetForm()
  emit('update:modelValue', false)
}

function finishWithVersion(version: AssetVersionVO) {
  ElMessage.success(`已上传 V${version.versionNo}（${version.fileSizeText}）`)
  emit('saved', { asset: null, version })
  resetForm()
  emit('update:modelValue', false)
}

// ==================== 取消 / 关闭 / 重置 ====================

function cancelUpload() {
  if (!cancelable.value) return
  cancelRequested.value = true
  // 算指纹是纯本地读文件、当前没有请求在飞，可以立刻断，用户点了就有反应；
  // 上传阶段要等分片间隙，见 handleTaskProgress 的注释
  if (phase.value === 'hashing') {
    taskController?.abort()
  }
  ElMessage.info('已取消上传。已传的分片留在服务端，重新选择同一个文件会跳过它们继续传')
}

function closeDialog() {
  if (busy.value) return
  emit('update:modelValue', false)
}

/**
 * 三个「顺手关掉」的入口（右上角 X / 点遮罩 / ESC）都已显式关掉，
 * 正常路径下这个守卫不会被触发；留着是防止父组件哪天改回默认 props，
 * 把传到一半的对话框关掉——那样请求还挂着，状态却已经被清了。
 */
function handleBeforeClose(done: () => void) {
  if (busy.value) {
    ElMessage.warning('上传进行中，请先取消或等待完成')
    return
  }
  done()
}

/** 清空全部状态，包括 AbortController、进度与秒传提示，否则第二次打开会看到上一次的残留 */
function resetForm() {
  stopTask()
  form.categoryId = null
  form.name = ''
  form.description = ''
  form.priority = 'P2'
  form.tags = ''
  form.versionName = ''
  form.changeLog = ''
  form.polyCount = undefined
  form.textureSize = ''
  form.file = null
  md5.value = null
  dedupe.value = null
  duplicateMode.value = 'reuse'
  formRef.value?.clearValidate()
}

// 父组件收起对话框（成功关闭是我们自己发的，其余是外力）：
// 兜底中断在途请求并清状态，避免请求悬挂
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      resetForm()
    }
  }
)

onBeforeUnmount(stopTask)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="640px"
    top="6vh"
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    :before-close="handleBeforeClose"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" :disabled="busy">
      <!-- 仅新建模式：新版本只往已有资产上挂文件，分类与名称由资产本身决定 -->
      <template v-if="!isVersionMode">
        <el-form-item label="所属分类" prop="categoryId">
          <el-tree-select
            v-model="form.categoryId"
            :data="categories"
            :props="CATEGORY_TREE_PROPS"
            :check-strictly="true"
            filterable
            clearable
            placeholder="选择一级或二级分类"
            style="width: 100%"
          />
          <div class="field-hint muted">
            <template v-if="selectedCategory">
              资产编号 = 分类编码 + 序号，将按「{{ selectedCategory.code }}_序号」生成。
            </template>
            <template v-else>资产编号由后端按「分类编码 + 序号」生成，例如 CHR_SM_0001。</template>
          </div>
        </el-form-item>

        <el-form-item label="资产名称" prop="name">
          <el-input v-model="form.name" maxlength="60" show-word-limit placeholder="如：主角_高模" />
        </el-form-item>

        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
            placeholder="用途、注意事项、关联需求等（选填）"
          />
        </el-form-item>

        <el-form-item label="优先级" prop="priority">
          <el-radio-group v-model="form.priority">
            <el-radio-button v-for="option in priorityOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="标签" prop="tags">
          <el-input v-model="form.tags" placeholder="多个标签用逗号分隔，如：角色,高模,待绑定" />
        </el-form-item>
      </template>

      <el-divider content-position="left">
        <span class="divider">{{ isVersionMode ? '新版本信息' : '首个版本信息' }}</span>
      </el-divider>

      <el-form-item label="版本名" prop="versionName">
        <el-input v-model="form.versionName" maxlength="60" placeholder="如：初版 / 优化拓扑（选填）" />
      </el-form-item>

      <el-form-item label="变更说明" prop="changeLog">
        <el-input
          v-model="form.changeLog"
          type="textarea"
          :rows="2"
          maxlength="255"
          show-word-limit
          placeholder="这一版改了什么（选填，审核时最有用）"
        />
      </el-form-item>

      <el-form-item label="面数" prop="polyCount">
        <el-input-number
          v-model="form.polyCount"
          :min="0"
          :max="100000000"
          :step="1000"
          controls-position="right"
          placeholder="如 32000"
          style="width: 200px"
        />
      </el-form-item>

      <el-form-item label="贴图尺寸" prop="textureSize">
        <el-input v-model="form.textureSize" placeholder="如 2048x2048（选填）" style="width: 200px" />
      </el-form-item>

      <el-form-item label="版本文件" prop="file">
        <div class="picker">
          <input ref="fileInputRef" class="picker__input" type="file" @change="handleFileChange" />
          <div class="picker__row">
            <el-button :icon="FolderOpened" :disabled="busy" @click="pickFile">选择文件</el-button>
            <span class="muted picker__limit">
              单文件不超过 2GB；超过 {{ chunkThresholdText }} 会自动改走分片上传
            </span>
          </div>

          <div v-if="form.file" class="picker__file">
            <el-icon class="picker__icon"><Document /></el-icon>
            <div class="picker__meta">
              <span class="picker__name mono">{{ form.file.name }}</span>
              <span class="muted picker__sub">
                {{ humanSize(form.file.size) }} · {{ filePlanText }}
              </span>
            </div>
            <el-button v-if="!busy" text size="small" @click="clearFile">重新选择</el-button>
          </div>

          <ul class="picker__formats muted">
            <li v-for="hint in ASSET_FILE_HINTS" :key="hint">{{ hint }}</li>
          </ul>
        </div>
      </el-form-item>
    </el-form>

    <!-- 秒传命中：说明清楚「已有的是哪一份」，并把复用 / 照传的选择交给用户 -->
    <div v-if="dedupe?.exists" class="dedupe">
      <el-alert type="success" :closable="false" show-icon>
        <template #title>
          项目内已有相同文件：<span class="mono">{{ dedupe.assetNo }}</span>
          <span class="muted"> / {{ dedupe.fileName }}</span>
        </template>
        <template #default>{{ dedupe.message }}</template>
      </el-alert>

      <div class="dedupe__body">
        <el-radio-group v-model="duplicateMode" :disabled="busy" size="small">
          <el-radio-button value="reuse">直接复用（秒传，不传文件）</el-radio-button>
          <el-radio-button value="upload">仍然上传一份</el-radio-button>
        </el-radio-group>
        <p class="dedupe__tip muted">
          复用只新增一条记录，不再占用带宽；选「仍然上传」会把这
          {{ form.file ? humanSize(form.file.size) : '' }} 重新传一遍。
        </p>
      </div>
    </div>

    <!-- 进度区：阶段 + 百分比 + 已传/总量 + 速度 + 分片位置 -->
    <div v-if="busy" class="progress">
      <div class="progress__head">
        <span class="progress__phase">{{ phaseText }}</span>
        <span v-if="progress" class="progress__percent tabular">{{ progress.percent }}%</span>
      </div>
      <el-progress
        :percentage="progress?.percent ?? 0"
        :indeterminate="!progress"
        :duration="2"
        :show-text="false"
        :stroke-width="6"
      />
      <div class="progress__meta muted">
        <span v-if="progress">
          {{ byteLabel }} {{ humanSize(progress.uploadedBytes) }} / {{ humanSize(progress.totalBytes) }}
        </span>
        <span v-if="progress && progress.totalChunks > 1">
          第 {{ progress.currentChunk }} / {{ progress.totalChunks }} 片
        </span>
        <span v-if="progress && progress.speedText">{{ progress.speedText }}</span>
        <span v-if="!progress">等待服务端响应…</span>
      </div>
    </div>

    <template #footer>
      <div class="footer">
        <span class="footer__hint muted">
          {{ busy ? '上传进行中，请先取消或等待完成' : '' }}
        </span>
        <div class="footer__actions">
          <el-button v-if="busy" :disabled="!cancelable" @click="cancelUpload">取消上传</el-button>
          <el-button v-else @click="closeDialog">取消</el-button>
          <el-button type="primary" :loading="busy" @click="handleSubmit">{{ submitText }}</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 表单内的说明文字：独占一行，别和控件挤在同一个 flex 行里 */
.field-hint {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
}

.divider {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-muted);
}

/* ---------- 文件选择 ---------- */

.picker {
  width: 100%;
}

.picker__input {
  display: none;
}

.picker__row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.picker__limit,
.picker__formats {
  font-size: 12px;
}

.picker__formats {
  margin: 6px 0 0;
  padding-left: 18px;
  line-height: 1.7;
}

.picker__file {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 8px;
  padding: 8px 11px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-panel);
}

.picker__icon {
  color: var(--accent);
  flex: none;
}

.picker__meta {
  flex: 1;
  min-width: 0;
}

.picker__name {
  display: block;
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picker__sub {
  font-size: 11.5px;
}

/* ---------- 秒传提示 ---------- */

.dedupe {
  margin-top: 4px;
}

.dedupe__body {
  padding: 10px 12px 12px;
  border: 1px dashed var(--accent);
  border-radius: var(--radius);
  background: var(--accent-soft);
}

.dedupe__tip {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

/* ---------- 进度 ---------- */

.progress {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-panel);
}

.progress__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.progress__phase {
  font-size: 13px;
  font-weight: 600;
}

.progress__percent {
  font-size: 13px;
  color: var(--text-muted);
}

.progress__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 7px;
  font-size: 12px;
}

/* ---------- 底部 ---------- */

.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.footer__hint {
  font-size: 12px;
  text-align: left;
}

.footer__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
</style>
