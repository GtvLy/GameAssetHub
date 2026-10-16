<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Delete, Edit, Picture, Refresh, Right, Switch, Upload, User } from '@element-plus/icons-vue'
import {
  deleteAsset,
  downloadVersion,
  fetchAsset,
  fetchStatusLogs,
  fetchVersions,
  setCurrentVersion
} from '@/api/asset'
import AssetEditDialog from '@/components/asset/AssetEditDialog.vue'
import AssetStatusDialog from '@/components/asset/AssetStatusDialog.vue'
import AssetStatusTag from '@/components/asset/AssetStatusTag.vue'
import AssetUploadDialog from '@/components/asset/AssetUploadDialog.vue'
import VersionCompareDialog from '@/components/asset/VersionCompareDialog.vue'
import VersionTimeline from '@/components/asset/VersionTimeline.vue'
import {
  assetStatusMeta,
  canDeleteAssets,
  canReviewAssets,
  canWriteAssets,
  transitionButtonType,
  transitionLabel,
  transitionNeedsReview
} from '@/constants/asset'
import { categoryFullName, useCategoryTree } from '@/composables/useCategoryTree'
import { taskPriorityMeta } from '@/constants/task'
import { useProjectStore } from '@/stores/project'
import type { AssetStatus, AssetStatusLogVO, AssetVersionVO, AssetVO } from '@/types/asset'

/**
 * 资产详情页。
 *
 * 页面只做两件事：把后端下发的状态**如实**呈现出来，以及把用户的动作
 * 转成一次请求、然后重新拉取。所有「接下来能做什么」都来自
 * `asset.nextStatuses`，所有「现在是什么状态」都来自重新请求的详情——
 * 前端手工推算状态在多人协作下必然会和后端的状态机分叉，
 * 而分叉的表现是「界面显示已通过、实际还在待审核」这种最难发现的错。
 */
const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()

const projectId = computed(() => Number(route.params.projectId))
const assetId = computed(() => Number(route.params.assetId))

const loading = ref(false)
const asset = ref<AssetVO | null>(null)
const versions = ref<AssetVersionVO[]>([])
const logs = ref<AssetStatusLogVO[]>([])
/** 资产不存在或无权访问：此时整页退到空态，而不是把异常抛进渲染阶段 */
const notFound = ref(false)
/** 正在下载 / 切换的版本ID，交给 VersionTimeline 做按钮 loading */
const busyVersionId = ref<number | null>(null)

const uploadVisible = ref(false)
const editVisible = ref(false)
const compareVisible = ref(false)
const statusVisible = ref(false)
/** 待流转到的目标状态：由 nextStatuses 里的某个按钮挑出来 */
const targetStatus = ref<AssetStatus | null>(null)

const role = computed(() => projectStore.myRole)
const canWrite = computed(() => canWriteAssets(role.value))
const canDelete = computed(() => canDeleteAssets(role.value))
const canReview = computed(() => canReviewAssets(role.value))

const statusMeta = computed(() => assetStatusMeta(asset.value?.status))

// 分类树只用于「改分类」的下拉与详情页的分类全路径名。它和资产详情是两个
// 独立的失败点：分类接口挂了不该让详情页也打不开，因此各自处理
const { tree: categories, load: loadCategories } = useCategoryTree(projectId)

const categoryText = computed(() => {
  const current = asset.value
  if (!current) return ''
  // 后端给的 categoryName 只是分类自身名字，二级分类要带上一级前缀才不会
  // 出现两个「角色」分不清是哪一个；树还没加载出来时回落到后端给的名字
  return categoryFullName(categories.value, current.categoryId) || current.categoryName || '未分类'
})

/** 缩略图优先用资产级的（列表页也用它），没有则回落到当前版本的 */
const thumbUrl = computed(() => asset.value?.thumbnailUrl || asset.value?.currentVersion?.thumbnailUrl || null)

/**
 * 相关任务（模块3 交付）：由资产详情接口的 relatedTasks 返回。
 * 列表接口刻意不带这个字段——一页 20 条资产每条都去查关联任务就是 20 次额外查询，
 * 而列表上并不展示这些信息。
 */
const relatedTasks = computed(() => asset.value?.relatedTasks ?? [])

/** 跳到看板并打开该任务的详情抽屉（与通知铃铛的跳转方式一致） */
function openTask(taskId: number) {
  router.push({ path: `/project/${projectId.value}/board`, query: { task: String(taskId) } })
}

async function loadVersions() {
  try {
    versions.value = await fetchVersions(assetId.value)
  } catch {
    // 提示已由拦截器统一给出，这里让时间线保持为空即可：
    // 版本接口失败不该把已经拿到的详情也一起藏起来
  }
}

async function loadLogs() {
  try {
    logs.value = await fetchStatusLogs(assetId.value)
  } catch {
    /* 同上 */
  }
}

async function load() {
  const id = assetId.value
  if (!Number.isFinite(id) || id <= 0) {
    // 路由参数不是数字时不要拼出 /assets/NaN 去请求，那只会换回一个没有信息的 500
    notFound.value = true
    asset.value = null
    return
  }

  loading.value = true
  notFound.value = false
  try {
    asset.value = await fetchAsset(id)
    await Promise.all([loadVersions(), loadLogs()])
  } catch {
    // 403 / 404 的具体文案由拦截器给出（无权访问 / 资源不存在），
    // 这里只负责把页面退到空态并给一条返回路径，不让用户卡在白屏上
    asset.value = null
    versions.value = []
    logs.value = []
    notFound.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadCategories()
  void load()
})

// 详情页之间互相跳转时组件会被复用（不会重新 mount），只靠 onMounted 会一直
// 停在上一个资产的数据上。projectId 一并监听：跨项目切换后 X-Project-Id 变了，
// 继续拿旧数据渲染等于展示了一份当前项目根本看不到的资产
watch(
  () => [route.params.assetId, route.params.projectId],
  () => {
    void load()
  }
)

function goList() {
  void router.push({ name: 'project-assets', params: { projectId: projectId.value } })
}

// ---------- 状态流转 ----------

/** 这一步是不是审核动作（通过 / 打回 / 废弃），且当前角色没有审核权 */
function needsReview(to: AssetStatus): boolean {
  return transitionNeedsReview(to) && !canReview.value
}

function openStatusDialog(to: AssetStatus) {
  targetStatus.value = to
  statusVisible.value = true
}

function handleStatusChanged() {
  // 不拿流转返回值去改本地状态：nextStatuses、审核人、审核时间都是后端算的，
  // 一次重新拉取比在前端推算一份更可靠
  void load()
}

// ---------- 版本 ----------

async function handleDownload(version: AssetVersionVO) {
  busyVersionId.value = version.id
  try {
    const blob = await downloadVersion(assetId.value, version.id)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = version.fileName
    link.style.display = 'none'
    document.body.appendChild(link)
    link.click()
    link.remove()
    // 必须释放：几百 MB 的 blob 不 revoke 会一直占着内存直到页面卸载。
    // 但不能在 click() 之后同步 revoke——Firefox 是异步去读这个 blob URL 的，
    // 同步释放会把还没真正开始的下载掐断，表现为「点了没反应」
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch {
    /* 拦截器已提示（文件已被清理、越权等） */
  } finally {
    busyVersionId.value = null
  }
}

async function handleSetCurrent(version: AssetVersionVO) {
  try {
    await ElMessageBox.confirm(
      `确定把「V${version.versionNo}」设为当前版本吗？` +
        '这只会切换「当前版本」这个标记，历史版本一个都不会删，需要的话随时可以再切回来。',
      '切换当前版本',
      { confirmButtonText: '切换', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  busyVersionId.value = version.id
  try {
    await setCurrentVersion(assetId.value, version.id)
    ElMessage.success(`当前版本已切换为 V${version.versionNo}`)
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    busyVersionId.value = null
  }
}

function handleUploaded() {
  // 成功提示由上传对话框自己给出，这里不重复弹一条一样的 toast，
  // 只负责把详情与版本列表拉回与后端一致的状态
  void load()
}

// ---------- 删除 ----------

async function handleDelete() {
  const current = asset.value
  if (!current) return

  try {
    await ElMessageBox.confirm(
      `确定删除「${current.name}」及其 ${current.versionCount} 个版本吗？` +
        '文件与版本历史会一并清理，无法恢复。' +
        '如果只是不再使用它，建议改为「废弃」——废弃后历史版本仍然可以下载和追溯。',
      '删除资产',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await deleteAsset(current.id)
    ElMessage.success('资产已删除')
    goList()
  } catch {
    /* 拦截器已提示 */
  }
}
</script>

<template>
  <div v-loading="loading" class="detail">
    <!-- 空态：资产被删除、或者当前账号无权访问这个资产。
         两种情况下后端分别返回 404 / 403，拦截器已经提示过原因，
         页面只需要给出一条走得出去的路 -->
    <div v-if="notFound" class="panel empty">
      <el-empty description="资产不存在或无权访问">
        <p class="muted empty__hint">
          它可能已经被删除，也可能是当前账号在该项目里没有查看这份资产的权限。
        </p>
        <el-button type="primary" :icon="ArrowLeft" @click="goList">返回资产列表</el-button>
      </el-empty>
    </div>

    <template v-else-if="asset">
      <!-- 头部：这是什么资产、现在什么状态、我能做什么 -->
      <div class="panel head">
        <div class="head__main">
          <div class="head__title">
            <h2 class="head__name">{{ asset.name }}</h2>
            <AssetStatusTag :status="asset.status" :priority="asset.priority" show-status show-priority />
          </div>

          <div class="head__meta">
            <span class="mono head__no">{{ asset.assetNo }}</span>
            <span class="head__dot" />
            <span>{{ categoryText }}</span>
            <span class="head__dot" />
            <span class="head__owner">
              <el-icon :size="12"><User /></el-icon>
              {{ asset.ownerName || '未指派' }}
            </span>
            <span class="head__dot" />
            <span class="muted">创建 {{ asset.createTime || '—' }}</span>
            <span class="head__dot" />
            <span class="muted">更新 {{ asset.updateTime || '—' }}</span>
          </div>

          <p v-if="asset.description" class="head__desc">{{ asset.description }}</p>

          <div v-if="asset.tagList.length" class="head__tags">
            <el-tag v-for="tag in asset.tagList" :key="tag" size="small" effect="plain" type="info">
              {{ tag }}
            </el-tag>
          </div>

          <!-- 最近一次审核意见：美术回到这个页面第一件事就是看要改什么，
               因此不折叠、不藏进弹窗 -->
          <div v-if="asset.reviewRemark" class="head__review">
            <span class="head__review-label muted">
              最近审核意见<template v-if="asset.reviewerName"> · {{ asset.reviewerName }}</template>
            </span>
            <span class="head__review-text">{{ asset.reviewRemark }}</span>
          </div>
        </div>

        <div class="head__actions">
          <el-button :icon="ArrowLeft" @click="goList">返回列表</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          <el-button v-if="canWrite" type="primary" :icon="Upload" @click="uploadVisible = true">
            上传新版本
          </el-button>
          <el-button v-if="canWrite" :icon="Edit" @click="editVisible = true">编辑</el-button>
          <el-button v-if="canDelete" type="danger" plain :icon="Delete" @click="handleDelete">
            删除
          </el-button>
        </div>
      </div>

      <!-- 状态流转：按钮完全由后端的 nextStatuses 渲染 -->
      <div class="panel">
        <div class="panel-title">
          <span>状态流转</span>
          <span class="muted transitions__hint">可执行的动作由后端状态机下发，前端不另存一份</span>
        </div>
        <div class="panel-body">
          <div class="transitions">
            <template v-if="asset.nextStatuses.length">
              <!-- 需要审核权的动作禁掉而不是隐藏：隐藏会让美术不知道还有审核这一步，
                   禁掉加说明才能让他知道该去找谁 -->
              <el-tooltip
                v-for="to in asset.nextStatuses"
                :key="to"
                :disabled="!needsReview(to)"
                content="这一步属于审核动作，需要项目负责人（OWNER）执行"
                placement="top"
              >
                <span>
                  <el-button
                    :type="transitionButtonType(to)"
                    :disabled="needsReview(to)"
                    @click="openStatusDialog(to)"
                  >
                    {{ transitionLabel(asset.status, to) }}
                  </el-button>
                </span>
              </el-tooltip>
            </template>
            <span v-else class="muted">当前状态下没有可执行的流转动作</span>
          </div>

          <p class="transitions__note muted">
            当前状态：<span :style="{ color: statusMeta.color }">{{ statusMeta.label }}</span>
            —— {{ statusMeta.description }}。每次流转都会记入下方历史，打回意见不会被覆盖。
          </p>
        </div>
      </div>

      <div class="detail__grid">
        <!-- 版本时间线 -->
        <div class="panel">
          <div class="panel-title">
            <span>版本时间线（{{ versions.length }} 个版本）</span>
            <el-tooltip :disabled="versions.length >= 2" content="至少要有两个版本才能对比" placement="top">
              <span>
                <el-button
                  size="small"
                  :icon="Switch"
                  :disabled="versions.length < 2"
                  @click="compareVisible = true"
                >
                  版本对比
                </el-button>
              </span>
            </el-tooltip>
          </div>
          <div class="panel-body">
            <VersionTimeline
              :versions="versions"
              :asset-status="asset.status"
              :can-write="canWrite"
              :busy-version-id="busyVersionId"
              @download="handleDownload"
              @set-current="handleSetCurrent"
            />
          </div>
        </div>

        <div class="detail__side">
          <!-- 当前版本：生产上真正会被引用的就是这一版，因此把它的元数据摊平展示 -->
          <div class="panel">
            <div class="panel-title">
              <span>当前版本</span>
              <span v-if="asset.currentVersion" class="mono muted">
                V{{ asset.currentVersion.versionNo }}
              </span>
            </div>
            <div class="panel-body">
              <template v-if="asset.currentVersion">
                <el-image
                  v-if="thumbUrl"
                  :src="thumbUrl"
                  :preview-src-list="[thumbUrl]"
                  :initial-index="0"
                  fit="cover"
                  preview-teleported
                  class="current__thumb"
                />
                <div v-else class="current__thumb current__thumb--empty">
                  <el-icon :size="26"><Picture /></el-icon>
                  <span class="muted">暂无预览图</span>
                </div>

                <div class="current__grid">
                  <div class="field field--wide">
                    <span class="muted">文件名</span>
                    <span class="mono">{{ asset.currentVersion.fileName }}</span>
                  </div>
                  <div class="field">
                    <span class="muted">体积</span>
                    <span class="tabular">{{ asset.currentVersion.fileSizeText }}</span>
                  </div>
                  <div class="field">
                    <span class="muted">格式</span>
                    <span class="mono">{{ asset.currentVersion.fileExt || '—' }}</span>
                  </div>
                  <div class="field">
                    <span class="muted">面数</span>
                    <span class="tabular">{{ asset.currentVersion.polyCount ?? '—' }}</span>
                  </div>
                  <div class="field">
                    <span class="muted">贴图尺寸</span>
                    <span class="mono">{{ asset.currentVersion.textureSize || '—' }}</span>
                  </div>
                  <div class="field field--wide">
                    <span class="muted">文件 MD5</span>
                    <span class="mono field__md5">{{ asset.currentVersion.fileMd5 || '—' }}</span>
                  </div>
                  <div class="field">
                    <span class="muted">上传人</span>
                    <span>{{ asset.currentVersion.uploaderName || '未知' }}</span>
                  </div>
                  <div class="field">
                    <span class="muted">上传时间</span>
                    <span>{{ asset.currentVersion.createTime || '—' }}</span>
                  </div>
                </div>

                <p v-if="asset.currentVersion.changeLog" class="current__log muted">
                  {{ asset.currentVersion.changeLog }}
                </p>
              </template>

              <el-empty v-else :image-size="60" description="还没有上传任何版本" />
            </div>
          </div>

          <!-- 流转历史：审核意见的留痕处 -->
          <div class="panel">
            <div class="panel-title">
              <span>流转历史</span>
              <span class="muted history__hint">谁在什么时候把状态改成了什么</span>
            </div>
            <div class="panel-body">
              <el-empty v-if="!logs.length" :image-size="60" description="还没有流转记录" />

              <el-timeline v-else>
                <el-timeline-item
                  v-for="log in logs"
                  :key="log.id"
                  :color="assetStatusMeta(log.toStatus).color"
                  :timestamp="log.createTime || ''"
                  placement="top"
                >
                  <div class="log">
                    <div class="log__head">
                      <span class="log__operator">{{ log.operatorName || '未知' }}</span>
                      <span class="muted log__flow">
                        {{ log.fromStatusLabel || '新建' }}
                        <el-icon :size="12"><Right /></el-icon>
                        {{ log.toStatusLabel }}
                      </span>
                    </div>

                    <p v-if="log.remark" class="log__remark">{{ log.remark }}</p>
                    <p v-else class="log__remark muted">未填写说明</p>
                  </div>
                </el-timeline-item>
              </el-timeline>
            </div>
          </div>

          <!--
            相关任务（模块3 交付，文档 5.5 的双向联动）。
            数据来自资产详情接口的 relatedTasks：从资产侧看「这个资源还有哪些待办」，
            点一条直接跳到看板并打开它的详情。
          -->
          <div class="panel">
            <div class="panel-title">
              <span>相关任务（{{ relatedTasks.length }}）</span>
              <span class="muted history__hint">关联到本资产的任务</span>
            </div>
            <div class="panel-body">
              <el-empty v-if="!relatedTasks.length" :image-size="60" description="还没有任务关联到这个资产" />

              <ul v-else class="related">
                <li
                  v-for="t in relatedTasks"
                  :key="t.id"
                  class="related__item"
                  @click="openTask(t.id)"
                >
                  <span class="related__bar" :style="{ background: taskPriorityMeta(t.priority, t.priorityColor).color }" />
                  <div class="related__main">
                    <div class="related__title" :title="t.title">{{ t.title }}</div>
                    <div class="related__meta">
                      <el-tag size="small" effect="plain" type="info">{{ t.columnLabel }}</el-tag>
                      <span>{{ t.assigneeName || '未指派' }}</span>
                      <span v-if="t.dueDate" :class="{ danger: t.overdue }">
                        {{ t.overdue ? '已逾期' : t.dueDate }}
                      </span>
                    </div>
                  </div>
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>

  <!-- 上传新版本（也用于新建：由上传对话框自己判断 asset 是否为空） -->
  <AssetUploadDialog
    v-if="asset"
    v-model="uploadVisible"
    :project-id="projectId"
    :asset="asset"
    :categories="categories"
    @saved="handleUploaded"
  />

  <AssetEditDialog v-if="asset" v-model="editVisible" :asset="asset" :categories="categories" @saved="handleUploaded" />

  <AssetStatusDialog
    v-model="statusVisible"
    :asset="asset"
    :to-status="targetStatus"
    @changed="handleStatusChanged"
  />

  <VersionCompareDialog v-model="compareVisible" :asset-id="assetId" :versions="versions" />
</template>

<style scoped>
.detail {
  display: grid;
  gap: 16px;
}

.empty {
  padding: 30px 20px;
}

.empty__hint {
  margin: -6px 0 18px;
  font-size: 12.5px;
}

/* ---------- 头部 ---------- */

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 22px;
  padding: 18px;
  flex-wrap: wrap;
}

.head__main {
  min-width: 0;
  flex: 1;
}

.head__title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.head__name {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
}

.head__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 12.5px;
  color: var(--text-muted);
  flex-wrap: wrap;
}

.head__no {
  letter-spacing: 0.3px;
}

.head__dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.6;
}

.head__owner {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.head__desc {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-muted);
  max-width: 70ch;
}

.head__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.head__review {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-top: 12px;
  padding: 10px 12px;
  border-left: 3px solid var(--danger);
  border-radius: 0 var(--radius) var(--radius) 0;
  background: var(--bg-panel);
}

.head__review-label {
  font-size: 11.5px;
}

.head__review-text {
  font-size: 13px;
  line-height: 1.7;
}

.head__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

/* ---------- 状态流转 ---------- */

.transitions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.transitions__hint {
  font-size: 12px;
  font-weight: 400;
}

.transitions__note {
  margin: 12px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
}

/* ---------- 主体两栏 ---------- */

.detail__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.detail__side {
  display: grid;
  gap: 16px;
}

@media (max-width: 1080px) {
  .detail__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* ---------- 当前版本 ---------- */

.current__thumb {
  width: 100%;
  height: 150px;
  display: block;
  border-radius: var(--radius);
  border: 1px solid var(--border);
  background: var(--bg-panel);
  cursor: zoom-in;
}

.current__thumb--empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: 12px;
  cursor: default;
}

.current__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px 14px;
  margin-top: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: 12.5px;
}

.field--wide {
  grid-column: 1 / -1;
}

.field > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* MD5 是 32 位定长串，容器窄时会溢出，允许断开而不是撑破面板 */
.field__md5 {
  word-break: break-all;
  white-space: normal !important;
}

.current__log {
  margin: 14px 0 0;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
  font-size: 12.5px;
  line-height: 1.7;
}

/* ---------- 流转历史 ---------- */

.history__hint {
  font-size: 12px;
  font-weight: 400;
}

.log__head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  flex-wrap: wrap;
}

.log__operator {
  font-weight: 600;
}

.log__flow {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
}

.log__remark {
  margin: 6px 0 0;
  padding: 8px 11px;
  border-radius: 8px;
  background: var(--bg-panel);
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
}

/* 相关任务（模块3 交付） */
.related {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.related__item {
  position: relative;
  display: flex;
  gap: 9px;
  padding: 8px 10px 8px 13px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-panel);
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.related__item:hover {
  border-color: var(--accent);
}

/* 左侧优先级色条：与看板卡片同一套视觉语言 */
.related__bar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  border-radius: var(--radius) 0 0 var(--radius);
}

.related__main {
  flex: 1;
  min-width: 0;
}

.related__title {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.related__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--text-muted);
}

.related__meta .danger {
  color: var(--danger);
  font-weight: 600;
}
</style>
