<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh, SwitchButton, Ticket, User } from '@element-plus/icons-vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { createProject, fetchMyProjects, joinProject } from '@/api/project'
import { fetchCurrentUser, logout as logoutApi } from '@/api/auth'
import { useProjectStore } from '@/stores/project'
import { useUserStore } from '@/stores/user'
import { ENGINE_OPTIONS, PROJECT_STATUSES, roleMeta } from '@/constants'
import type { ProjectCreateDTO, ProjectVO } from '@/types'

/**
 * 我的项目列表。
 *
 * 「我参与的」由后端从 project_member 出发查询（只返回 ACTIVE 成员关系），
 * 前端不再过滤——列表口径必须只有一个来源。
 */
const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const projectStore = useProjectStore()

const loading = ref(false)
const projects = ref<ProjectVO[]>([])

const createVisible = ref(false)
const joinVisible = ref(false)
const submitting = ref(false)
const createFormRef = ref<FormInstance>()
const joinFormRef = ref<FormInstance>()

const createForm = reactive<ProjectCreateDTO & { dateRange?: [string, string] | null }>({
  name: '',
  code: '',
  description: '',
  engine: 'UE5',
  startDate: null,
  planEndDate: null,
  dateRange: null
})

const joinForm = reactive({ inviteCode: '' })

const createRules: FormRules = {
  name: [
    { required: true, message: '请输入项目名称', trigger: 'blur' },
    { max: 100, message: '不能超过 100 个字符', trigger: 'blur' }
  ]
}

const joinRules: FormRules = {
  inviteCode: [{ required: true, message: '请输入邀请码', trigger: 'blur' }]
}

const statusMeta = (status: string) =>
  PROJECT_STATUSES.find((s) => s.value === status) ?? {
    value: 'ACTIVE' as const,
    label: status,
    tagType: 'info' as const
  }

const activeCount = computed(() => projects.value.filter((p) => p.status === 'ACTIVE').length)

async function load() {
  loading.value = true
  try {
    const me = await fetchCurrentUser()
    userStore.setFromCurrentUser(me)
    projects.value = me.projects
    // 同步给全局 store，让顶栏切换器与后续请求头拿到一致的上下文
    projectStore.setProjects(me.projects)
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  // 顶栏切换器的「新建项目」入口通过 query 参数回到这里并直接打开弹窗
  if (route.query.create === '1') {
    createVisible.value = true
    await router.replace({ path: '/projects' })
  }
})

function openProject(project: ProjectVO) {
  projectStore.setCurrent(project.id)
  router.push(`/project/${project.id}/overview`)
}

function openCreate() {
  createForm.name = ''
  createForm.code = ''
  createForm.description = ''
  createForm.engine = 'UE5'
  createForm.dateRange = null
  createForm.startDate = null
  createForm.planEndDate = null
  createVisible.value = true
}

async function submitCreate() {
  const form = createFormRef.value
  if (!form) return
  const valid = await form.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const [start, end] = createForm.dateRange || [null, null]
    const created = await createProject({
      name: createForm.name.trim(),
      code: createForm.code?.trim() || null,
      description: createForm.description || null,
      engine: createForm.engine || null,
      startDate: start,
      planEndDate: end
    })
    ElMessage.success('项目创建成功，你已成为该项目负责人')
    createVisible.value = false
    await load()
    // 直接进入新项目，省一步点击
    openProject(created)
  } catch {
    /* 拦截器已提示 */
  } finally {
    submitting.value = false
  }
}

async function submitJoin() {
  const form = joinFormRef.value
  if (!form) return
  const valid = await form.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const project = await joinProject(joinForm.inviteCode.trim())
    ElMessage.success(`已加入项目「${project.name}」`)
    joinVisible.value = false
    joinForm.inviteCode = ''
    await load()
    openProject(project)
  } catch {
    /* 拦截器已提示（邀请码无效 / 已被使用 / 你已是成员） */
  } finally {
    submitting.value = false
  }
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await logoutApi()
  } catch {
    /* 令牌可能已过期，忽略 */
  } finally {
    userStore.clear()
    projectStore.clear()
    ElMessage.success('已退出登录')
    router.push('/login')
  }
}
</script>

<template>
  <div class="page">
    <header class="page__header">
      <div class="page__brand">
        <span class="page__logo">GA</span>
        <div>
          <div class="page__title">GameAsset Hub</div>
          <div class="page__subtitle muted">游戏项目资产与协作管理系统</div>
        </div>
      </div>

      <div class="page__spacer" />

      <ThemeToggle />

      <el-dropdown trigger="click">
        <div class="page__user">
          <el-avatar :size="28" class="page__avatar">{{ userStore.avatarText }}</el-avatar>
          <span>{{ userStore.displayName }}</span>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled>
              <el-icon><User /></el-icon>
              <span style="margin-left: 6px">{{ userStore.user?.username }}</span>
            </el-dropdown-item>
            <el-dropdown-item divided @click="handleLogout">
              <el-icon><SwitchButton /></el-icon>
              <span style="margin-left: 6px">退出登录</span>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <main class="page__body">
      <div class="page__toolbar">
        <div>
          <h2 class="page__section-title">我的项目</h2>
          <p class="muted page__section-desc">
            共 {{ projects.length }} 个项目，其中 {{ activeCount }} 个进行中。列表仅包含你已加入的项目。
          </p>
        </div>

        <div class="page__actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          <el-button :icon="Ticket" @click="joinVisible = true">凭邀请码加入</el-button>
          <el-button type="primary" :icon="Plus" @click="openCreate">新建项目</el-button>
        </div>
      </div>

      <div v-loading="loading" class="page__grid">
        <el-empty
          v-if="!loading && !projects.length"
          description="你还没有参与任何项目"
          class="page__empty"
        >
          <el-button type="primary" :icon="Plus" @click="openCreate">创建第一个项目</el-button>
          <el-button :icon="Ticket" @click="joinVisible = true">我有邀请码</el-button>
        </el-empty>

        <article
          v-for="project in projects"
          :key="project.id"
          class="card"
          @click="openProject(project)"
        >
          <div class="card__cover">
            <span class="card__cover-text">{{ project.name.charAt(0) }}</span>
            <el-tag
              :type="statusMeta(project.status).tagType"
              size="small"
              effect="dark"
              class="card__status"
            >
              {{ project.statusLabel }}
            </el-tag>
          </div>

          <div class="card__body">
            <div class="card__title-row">
              <h3 class="card__title">{{ project.name }}</h3>
              <el-tag :type="roleMeta(project.myRole).tagType" size="small" effect="plain">
                {{ roleMeta(project.myRole).label }}
              </el-tag>
            </div>

            <p class="card__desc muted">{{ project.description || '暂无项目简介' }}</p>

            <div class="card__meta">
              <span v-if="project.engine" class="card__chip mono">{{ project.engine }}</span>
              <span v-if="project.code" class="card__chip mono">#{{ project.code }}</span>
              <span class="card__chip">{{ project.memberCount }} 名成员</span>
            </div>

            <div class="card__dates muted">
              <span>{{ project.startDate || '未定' }}</span>
              <span class="card__arrow">→</span>
              <span>{{ project.planEndDate || '未定' }}</span>
            </div>
          </div>
        </article>
      </div>
    </main>

    <!-- 新建项目 -->
    <el-dialog v-model="createVisible" title="新建项目" width="560px" append-to-body>
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="92px">
        <el-form-item label="项目名称" prop="name">
          <el-input v-model="createForm.name" placeholder="例如：星港计划" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="项目代号" prop="code">
          <el-input v-model="createForm.code" placeholder="用于资产编号前缀，如 XG" maxlength="50" />
        </el-form-item>
        <el-form-item label="引擎" prop="engine">
          <el-select v-model="createForm.engine" placeholder="请选择引擎" style="width: 100%">
            <el-option v-for="engine in ENGINE_OPTIONS" :key="engine" :label="engine" :value="engine" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目周期" prop="dateRange">
          <el-date-picker
            v-model="createForm.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="计划结束日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="项目简介" prop="description">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            placeholder="简单描述项目题材与当前阶段"
          />
        </el-form-item>
      </el-form>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="创建后你将自动成为该项目的负责人（OWNER），拥有成员管理与资产审核权限。"
      />

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 凭邀请码加入 -->
    <el-dialog v-model="joinVisible" title="凭邀请码加入项目" width="460px" append-to-body>
      <el-form ref="joinFormRef" :model="joinForm" :rules="joinRules" label-position="top">
        <el-form-item label="邀请码" prop="inviteCode">
          <el-input
            v-model="joinForm.inviteCode"
            placeholder="请粘贴项目负责人发给你的邀请码"
            :prefix-icon="Ticket"
            @keyup.enter="submitJoin"
          />
        </el-form-item>
      </el-form>

      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="邀请码是一次性的，使用后立即失效。"
        description="若提示「邀请码无效或已被使用」，请让项目负责人重新邀请生成新码。"
      />

      <template #footer>
        <el-button @click="joinVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitJoin">加入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--bg-base);
}

.page__header {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 62px;
  padding: 0 24px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-panel);
}

.page__brand {
  display: flex;
  align-items: center;
  gap: 11px;
}

.page__logo {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 800;
  color: #fff;
  background: linear-gradient(135deg, var(--accent), #8f6fff);
}

.page__title {
  font-weight: 700;
  font-size: 15px;
  line-height: 1.2;
}

.page__subtitle {
  font-size: 11.5px;
  line-height: 1.4;
}

.page__spacer {
  flex: 1;
}

.page__user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
}

.page__user:hover {
  background: var(--bg-hover);
}

.page__avatar {
  background: var(--accent);
  color: #fff;
  font-weight: 600;
}

.page__body {
  max-width: 1220px;
  margin: 0 auto;
  padding: 30px 24px 60px;
}

.page__toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: 22px;
}

.page__section-title {
  margin: 0;
  font-size: 21px;
  font-weight: 700;
  letter-spacing: -0.2px;
}

.page__section-desc {
  margin: 6px 0 0;
  font-size: 13px;
}

.page__actions {
  display: flex;
  gap: 8px;
}

.page__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(272px, 1fr));
  gap: 18px;
  min-height: 180px;
}

.page__empty {
  grid-column: 1 / -1;
}

/* ---------- 项目卡片 ---------- */

.card {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-card);
  box-shadow: var(--shadow-card);
  overflow: hidden;
  cursor: pointer;
  transition:
    transform 0.16s ease,
    border-color 0.16s ease;
}

.card:hover {
  transform: translateY(-3px);
  border-color: var(--accent);
}

.card__cover {
  position: relative;
  height: 92px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--accent-soft), transparent 70%), var(--bg-panel);
  border-bottom: 1px solid var(--border);
}

.card__cover-text {
  font-size: 34px;
  font-weight: 800;
  color: var(--accent);
  opacity: 0.5;
}

.card__status {
  position: absolute;
  top: 10px;
  right: 10px;
}

.card__body {
  flex: 1;
  padding: 15px 16px 16px;
}

.card__title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.card__title {
  margin: 0;
  font-size: 15.5px;
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card__desc {
  margin: 9px 0 0;
  font-size: 12.5px;
  line-height: 1.65;
  min-height: 41px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.card__chip {
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--bg-panel);
  font-size: 11.5px;
  color: var(--text-muted);
}

.card__dates {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 12px;
  padding-top: 11px;
  border-top: 1px dashed var(--border);
  font-size: 12px;
}

.card__arrow {
  color: var(--accent);
}
</style>
