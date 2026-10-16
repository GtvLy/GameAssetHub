<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search, Setting } from '@element-plus/icons-vue'
import { deleteAsset, fetchAssets } from '@/api/asset'
import { fetchMembers } from '@/api/project'
import { useCategoryTree } from '@/composables/useCategoryTree'
import {
  ASSET_PRIORITIES,
  ASSET_STATUSES,
  canManageCategories,
  canWriteAssets
} from '@/constants/asset'
import { useProjectStore } from '@/stores/project'
import { useUserStore } from '@/stores/user'
import AssetCard from '@/components/asset/AssetCard.vue'
import AssetTable from '@/components/asset/AssetTable.vue'
import AssetUploadDialog from '@/components/asset/AssetUploadDialog.vue'
import CategoryManageDrawer from '@/components/asset/CategoryManageDrawer.vue'
import type { ProjectMemberVO } from '@/types'
import type { AssetPriority, AssetQuery, AssetStatus, AssetVO, AssetVersionVO } from '@/types/asset'

/**
 * 资产列表页（模块2 的入口）。
 *
 * 权限说明：按钮的显示与否只是体验优化——真值在后端 RolePermissionMap，
 * 每个接口都会各自校验一遍，前端隐藏按钮不作为安全边界。
 */
const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const userStore = useUserStore()

const projectId = computed(() => Number(route.params.projectId))
const role = computed(() => projectStore.myRole)
const canWrite = computed(() => canWriteAssets(role.value))
const canManageCategory = computed(() => canManageCategories(role.value))

const { tree, flat: flatCategories, load: loadCategories, reload: reloadCategories } =
  useCategoryTree(projectId)

/** 字段映射常量放脚本里：写成模板内联对象的话每次渲染都是新引用，el-tree 会重建 */
const TREE_PROPS = { label: 'name', value: 'id', children: 'children' }

// ==================== 列表状态 ====================

const viewMode = ref<'card' | 'table'>('card')
const loading = ref(false)
const assets = ref<AssetVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(12)

const filters = reactive<{
  categoryId: number | null
  categoryWithChildren: boolean
  status: AssetStatus | null
  priority: AssetPriority | null
  ownerId: number | null
  keyword: string
}>({
  categoryId: null,
  // 传一级分类时默认带上子分类：美术的直觉是「角色」包含「主角模型」。
  // 不勾选时点一级分类只剩零星几条，很容易被当成数据丢了
  categoryWithChildren: true,
  status: null,
  priority: null,
  ownerId: null,
  keyword: ''
})

const hasFilter = computed(
  () =>
    filters.categoryId != null ||
    filters.status != null ||
    filters.priority != null ||
    filters.ownerId != null ||
    filters.keyword.trim() !== ''
)

/** 空态文案要区分「一条都没建」和「筛没了」——前者引导新建，后者引导放宽条件 */
const emptyText = computed(() => {
  if (hasFilter.value) return '没有符合条件的资产'
  return canWrite.value ? '还没有资产，点右上角新建' : '这个项目还没有资产'
})

function buildQuery(): AssetQuery {
  return {
    page: page.value,
    size: size.value,
    categoryId: filters.categoryId,
    categoryWithChildren: filters.categoryWithChildren,
    status: filters.status,
    priority: filters.priority,
    ownerId: filters.ownerId,
    // 空串会被后端当成一个必须匹配的 LIKE 条件，归一成 null 才能真正「不过滤」
    keyword: filters.keyword.trim() || null
  }
}

async function load() {
  const id = projectId.value
  if (!Number.isFinite(id) || id <= 0) return

  loading.value = true
  try {
    const result = await fetchAssets(id, buildQuery())
    assets.value = result.records ?? []
    total.value = result.total ?? 0

    // 页码越界的兜底：删掉当前页最后一条（或别人同时删了几条）后，
    // 后端会返回「总量不为 0、当前页为空」，界面就是一张空白页。
    // 往前退一页重查——页码只减不增，不会来回震荡。
    // 注意这里不能改成「watch page → 查询」：那样退页会再打一次重复请求，
    // 而且退页到底再发几次请求取决于第几页，属于不可控的请求放大
    if (!assets.value.length && page.value > 1 && total.value > 0) {
      page.value -= 1
      await load()
    }
  } catch {
    // 拦截器已提示。清空列表：留着上一个筛选条件的结果比空列表更容易误导
    assets.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

// ==================== 筛选交互 ====================

/**
 * 筛选项变化 = 回第 1 页再查。
 * 不回第 1 页的话，在第 5 页筛出 2 条结果会直接看到空白页，
 * 而用户完全无从知道「是没数据还是页码超了」。
 *
 * 所有筛选控件都用显式的 @change 而不是 watch，是为了让「谁触发了查询」
 * 一目了然：resetFilters 里成批改字段不会误触发查询（程序化赋值不产生
 * 组件事件），切换项目时也就不会连发几次请求。
 */
function applyFilterChange() {
  page.value = 1
  void load()
}

/** 关键字防抖 300ms：每敲一个字发一次请求既浪费，也会让结果在打字过程中反复跳动 */
let keywordTimer: ReturnType<typeof setTimeout> | null = null

function onKeywordInput() {
  if (keywordTimer) clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => {
    keywordTimer = null
    applyFilterChange()
  }, 300)
}

// 离开页面时清掉挂起的防抖，否则会在组件卸载后发一个无人接收的请求
onBeforeUnmount(() => {
  if (keywordTimer) clearTimeout(keywordTimer)
})

function resetFilters() {
  filters.categoryId = null
  filters.categoryWithChildren = true
  filters.status = null
  filters.priority = null
  filters.ownerId = null
  filters.keyword = ''
  page.value = 1
  void load()
}

/** 「只看我的」：等价于把负责人筛成自己，省掉每次去下拉里找自己 */
const mine = computed<boolean>({
  get: () => filters.ownerId != null && filters.ownerId === userStore.user?.id,
  set: (next) => {
    // 取不到用户ID 时置空，宁可勾不上，也不要查出一个必然为空的列表
    filters.ownerId = next ? (userStore.user?.id ?? null) : null
  }
})

// ==================== 负责人下拉 ====================

const members = ref<ProjectMemberVO[]>([])
/** 只列已加入的成员：待加入的人还没有 userId 可指派，选了也查不到资产 */
const ownerOptions = computed(() => members.value.filter((m) => m.status === 'ACTIVE'))

async function loadMembers() {
  try {
    members.value = await fetchMembers(projectId.value)
  } catch {
    /* 拦截器已提示；少一个筛选下拉的选项不影响主流程 */
  }
}

// ==================== 分页 ====================

function goPage(next: number) {
  page.value = next
  void load()
}

function changeSize(next: number) {
  size.value = next
  // 每页容量变了，原来的页码大概率越界，统一回第 1 页
  page.value = 1
  void load()
}

// ==================== 操作 ====================

function openDetail(asset: AssetVO) {
  void router.push({
    name: 'project-asset-detail',
    params: { projectId: projectId.value, assetId: asset.id }
  })
}

const uploadVisible = ref(false)
/** 上传对话框的入参：null = 新建资产，有值 = 给该资产传新版本（同一组件两种模式） */
const uploadAsset = ref<AssetVO | null>(null)

function openCreate() {
  uploadAsset.value = null
  uploadVisible.value = true
}

function openUploadVersion(asset: AssetVO) {
  if (!canWrite.value) return
  uploadAsset.value = asset
  uploadVisible.value = true
}

/**
 * 上传/新建完成。
 *
 * 不做「往本地数组里插一条」这种局部更新：后端会算资产编号、版本号、
 * 分类计数，前端推算出来的副本迟早和服务端不一致。整页重拉最省心，
 * 也符合「写操作后以服务端为准」的约定。
 * 成功提示由对话框负责——只有它知道这次是新建还是新版本、是实传还是秒传。
 */
async function handleUploadSaved(_payload: { asset: AssetVO | null; version: AssetVersionVO | null }) {
  // 对话框自己也会发一次 update:modelValue=false 关掉自己；这里显式再关一次，
  // 是为了让「开没开」始终由持有状态的父页面说了算——
  // 一个打开了但已经空掉的对话框是最难排查的状态
  uploadVisible.value = false
  // 分类树也重取：新建资产会让所属分类的 assetCount 变化（抽屉里的「N 个资产」
  // 和列表筛选器都读它）。多一次很轻的请求，换掉「计数偶尔是旧的」
  await Promise.all([load(), reloadCategories()])
}

async function handleDelete(asset: AssetVO) {
  if (!canWrite.value) return

  try {
    await ElMessageBox.confirm(
      `确定要删除资产「${asset.name}」（编号 ${asset.assetNo}）吗？` +
        '该资产的版本文件与状态流转日志会保留、仍可追溯，只是不再出现在列表与看板中。',
      '删除资产',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await deleteAsset(asset.id)
    ElMessage.success('资产已删除')
    // 删的是当前页最后一条且有上一页时先退页：load() 内部的越界兜底也会处理，
    // 但提前退页可以少发一次注定为空的请求
    if (assets.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    await Promise.all([load(), reloadCategories()])
  } catch {
    /* 拦截器已提示（非本项目成员 / 无删除权限） */
  }
}

const drawerVisible = ref(false)

/**
 * 分类抽屉里改完分类后。
 *
 * 先 reload 树再刷列表：删掉的分类会让它名下的资产换分类、改名字会让
 * 列表里的分类名变旧，两者都要按新数据重查。
 */
async function handleCategoryChanged() {
  await reloadCategories()

  // 筛选里选中的分类可能刚被删掉：留着它查出来永远是空列表，
  // 用户只会看到「没有符合条件的资产」，却不知道是自己筛了一个不存在的分类
  if (filters.categoryId != null && !flatCategories.value.some((c) => c.id === filters.categoryId)) {
    filters.categoryId = null
  }

  page.value = 1
  await load()
}

// ==================== 生命周期 ====================

onMounted(() => {
  // 分类树是筛选项与上传表单的数据源，和列表并行拉，不串行等待
  void loadCategories()
  void loadMembers()
  void load()
})

watch(projectId, () => {
  // 切换项目必须清筛选：上一个项目的分类ID、负责人ID 在新项目里可能指向
  // 另一个对象，留着会查出一个「看起来没筛选、其实筛错了」的结果
  resetFilters()
  void loadMembers()
  void loadCategories()
})
</script>

<template>
  <div class="assets">
    <div class="assets__toolbar">
      <div>
        <h2 class="assets__title">资产</h2>
        <p class="muted assets__desc">
          共 <strong class="tabular">{{ total }}</strong> 个资产<template v-if="hasFilter">
            （按当前筛选）</template
          >。卡片墙看形态，表格视图横向比较版本与状态。
        </p>
      </div>

      <div class="assets__actions">
        <el-radio-group v-model="viewMode">
          <el-radio-button value="card">卡片墙</el-radio-button>
          <el-radio-button value="table">表格</el-radio-button>
        </el-radio-group>

        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>

        <el-button v-if="canManageCategory" :icon="Setting" @click="drawerVisible = true">
          分类管理
        </el-button>

        <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">
          新建资产
        </el-button>
        <el-tooltip v-else content="当前角色对资产只读，新建 / 上传版本 / 删除都不可用" placement="bottom">
          <el-tag type="info" effect="plain">只读视角</el-tag>
        </el-tooltip>
      </div>
    </div>

    <!-- 多维筛选 -->
    <div class="panel assets__filters">
      <div class="filters">
        <div class="filters__field">
          <span class="muted filters__label">分类</span>
          <el-tree-select
            v-model="filters.categoryId"
            :data="tree"
            :props="TREE_PROPS"
            node-key="id"
            check-strictly
            clearable
            filterable
            :render-after-expand="false"
            placeholder="全部分类"
            @change="applyFilterChange"
          />
        </div>

        <div class="filters__field">
          <span class="muted filters__label">状态</span>
          <el-select
            v-model="filters.status"
            clearable
            placeholder="全部状态"
            @change="applyFilterChange"
          >
            <el-option
              v-for="item in ASSET_STATUSES"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            >
              <div class="opt">
                <span>{{ item.label }}</span>
                <span class="muted opt__desc">{{ item.description }}</span>
              </div>
            </el-option>
          </el-select>
        </div>

        <div class="filters__field">
          <span class="muted filters__label">优先级</span>
          <el-select
            v-model="filters.priority"
            clearable
            placeholder="全部优先级"
            @change="applyFilterChange"
          >
            <el-option
              v-for="item in ASSET_PRIORITIES"
              :key="item.value"
              :label="`${item.value} ${item.label}`"
              :value="item.value"
            />
          </el-select>
        </div>

        <div class="filters__field">
          <span class="muted filters__label">负责人</span>
          <el-select
            v-model="filters.ownerId"
            clearable
            filterable
            placeholder="全部负责人"
            @change="applyFilterChange"
          >
            <el-option
              v-for="member in ownerOptions"
              :key="member.userId"
              :label="member.nickname || member.username || `#${member.userId}`"
              :value="member.userId"
            />
          </el-select>
        </div>

        <div class="filters__field">
          <span class="muted filters__label">关键字</span>
          <el-input
            v-model="filters.keyword"
            clearable
            placeholder="可搜资产名称或编号，如 CHR_SM_0001"
            :prefix-icon="Search"
            @input="onKeywordInput"
          />
        </div>
      </div>

      <div class="filters__foot">
        <el-checkbox v-model="filters.categoryWithChildren" @change="applyFilterChange">
          一级分类包含子分类
        </el-checkbox>
        <el-checkbox v-model="mine" @change="applyFilterChange">只看我的</el-checkbox>
        <span class="filters__spacer" />
        <el-button v-if="hasFilter" text type="primary" @click="resetFilters">清空筛选</el-button>
      </div>
    </div>

    <!-- 空态：区分「一条都没建」与「筛没了」 -->
    <div v-if="!loading && !assets.length" class="panel assets__empty">
      <el-empty :image-size="96" :description="emptyText">
        <el-button v-if="hasFilter" @click="resetFilters">清空筛选</el-button>
        <el-button v-else-if="canWrite" type="primary" :icon="Plus" @click="openCreate">
          新建资产
        </el-button>
      </el-empty>
    </div>

    <div
      v-else-if="viewMode === 'card'"
      v-loading="loading"
      class="asset-grid"
      :class="{ 'asset-grid--readonly': !canWrite }"
    >
      <AssetCard
        v-for="asset in assets"
        :key="asset.id"
        :asset="asset"
        @open="openDetail"
        @upload-version="openUploadVersion"
        @delete="handleDelete"
      />
    </div>

    <AssetTable
      v-else
      :assets="assets"
      :loading="loading"
      :can-write="canWrite"
      @open="openDetail"
      @upload-version="openUploadVersion"
      @delete="handleDelete"
    />

    <el-pagination
      v-if="total > 0"
      class="assets__pager"
      background
      layout="total, sizes, prev, pager, next"
      :total="total"
      :current-page="page"
      :page-size="size"
      :page-sizes="[12, 24, 48]"
      @current-change="goPage"
      @size-change="changeSize"
    />

    <!-- 新建资产 / 上传新版本共用一个对话框，靠 asset 是否为 null 区分模式 -->
    <AssetUploadDialog
      v-model="uploadVisible"
      :project-id="projectId"
      :asset="uploadAsset"
      :categories="tree"
      @saved="handleUploadSaved"
    />

    <CategoryManageDrawer
      v-model="drawerVisible"
      :project-id="projectId"
      :tree="tree"
      @changed="handleCategoryChanged"
    />
  </div>
</template>

<style scoped>
.assets {
  display: grid;
  gap: 18px;
}

/*
 * Grid 子项的 min-width 默认是 auto，也就是「不小于内容的最小宽度」。
 * 表格视图里那张 1260px 宽的表格会因此把整个页面撑出横向滚动条，
 * 内层容器上的 overflow-x: auto 反而永远不生效（它已经和内容一样宽了）。
 * 显式压成 0，才让横向滚动发生在表格自己身上。
 */
.assets > * {
  min-width: 0;
}

/* ---------- 工具条 ---------- */

.assets__toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.assets__title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
}

.assets__desc {
  margin: 6px 0 0;
  font-size: 13px;
}

.assets__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

/* ---------- 筛选 ---------- */

.assets__filters {
  display: grid;
  gap: 12px;
  padding: 14px 16px;
}

.filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 12px 14px;
}

.filters__field {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.filters__label {
  font-size: 12px;
}

.filters__foot {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
  padding-top: 11px;
  border-top: 1px dashed var(--border);
}

.filters__spacer {
  flex: 1;
}

.opt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.opt__desc {
  font-size: 11.5px;
}

/* ---------- 卡片墙 ---------- */

.asset-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

/* 卡片的三个操作按钮是组件固定的（AssetCard 的契约里没有权限参数）。
   只读角色下把悬停浮层整个藏掉：点下去必然 403 的按钮不该摆在眼前。
   表格视图不需要这层，那边由 canWrite 直接控制 */
.asset-grid--readonly :deep(.card__overlay) {
  display: none;
}

/* ---------- 其它 ---------- */

.assets__empty {
  padding: 26px 0;
}

.assets__pager {
  justify-content: flex-end;
}
</style>
