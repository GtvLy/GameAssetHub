<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, SwitchButton, User } from '@element-plus/icons-vue'
import ProjectSwitcher from './ProjectSwitcher.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import { useProjectStore } from '@/stores/project'
import { useUserStore } from '@/stores/user'
import { fetchProject } from '@/api/project'
import { logout as logoutApi, fetchCurrentUser } from '@/api/auth'
import { roleMeta } from '@/constants'

/**
 * 项目内布局：顶栏（品牌 / 项目切换器 / 用户）+ 左侧导航 + 内容区。
 *
 * 导航项覆盖四个模块的全部页面（概览/资产/看板/统计/成员），
 * 每一项都指向已实现的真实页面，不再有占位入口。
 */
const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const userStore = useUserStore()

/**
 * 导航项定义。
 *
 * 模块四把原来那个「统计（待接入）」的占位入口拆成了三个真实页面：
 * 燃尽图 / 工作量 / 周报。模块一~三的入口保持原样不动。
 */
const navItems = [
  { name: 'project-overview', path: 'overview', label: '概览', icon: 'DataBoard' },
  { name: 'project-assets', path: 'assets', label: '资产', icon: 'Box' },
  { name: 'project-board', path: 'board', label: '看板', icon: 'Grid' },
  { name: 'project-burndown', path: 'burndown', label: '燃尽图', icon: 'TrendCharts' },
  { name: 'project-workload', path: 'workload', label: '工作量', icon: 'Histogram' },
  { name: 'project-report', path: 'report', label: '周报', icon: 'Document' },
  { name: 'project-members', path: 'members', label: '成员', icon: 'User' }
]

const projectId = computed(() => Number(route.params.projectId))
// 默认取路径最后一段；详情页这类「最后一段不是导航项」的路由
// 由路由 meta.activePath 指定要高亮哪一项
const activePath = computed(
  () => (route.meta.activePath as string) || route.path.split('/').pop() || 'overview'
)
const myRoleMeta = computed(() => roleMeta(projectStore.myRole))
const loading = ref(false)

/**
 * 进入某个项目时把 URL 里的 projectId 同步进 store。
 *
 * 这一步是「URL 是唯一事实来源」的落地：用户直接贴一个 /project/7/members 进来，
 * 也要把 currentId 纠正为 7，否则请求头会带着上一次的项目ID，出现跨项目取数。
 */
async function syncProjectContext(id: number) {
  if (!Number.isFinite(id)) return
  if (projectStore.currentId !== id) {
    projectStore.setCurrent(id)
  }

  // 首次进入或项目不在列表里（例如被邀请后直接打开链接），补一次数据。
  // /auth/me 同时带回用户信息与我的项目列表，一次请求即可，不必串行两次
  if (!projectStore.projects.length) {
    loading.value = true
    try {
      const me = await fetchCurrentUser()
      userStore.setFromCurrentUser(me)
      projectStore.setProjects(me.projects)
    } catch {
      // 拦截器已提示，这里保持页面可渲染
    } finally {
      loading.value = false
    }
  }
}

async function refreshCurrentProject() {
  // 详情接口会顺手校验一次成员身份：非成员会拿到 403，用户能立刻得到明确反馈
  try {
    const detail = await fetchProject(projectId.value)
    const index = projectStore.projects.findIndex((p) => p.id === detail.id)
    if (index >= 0) {
      projectStore.projects.splice(index, 1, detail)
    }
  } catch {
    /* 非成员或项目已归档，交由拦截器提示 */
  }
}

onMounted(async () => {
  await syncProjectContext(projectId.value)
  await refreshCurrentProject()
})

watch(projectId, async (id) => {
  await syncProjectContext(id)
  await refreshCurrentProject()
})

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
    // 先通知后端把 jti 拉黑，再清本地；即使接口失败也要清本地，否则用户退不出去
    await logoutApi()
  } catch {
    /* 令牌已过期等原因失败，忽略 */
  } finally {
    userStore.clear()
    projectStore.clear()
    ElMessage.success('已退出登录')
    router.push('/login')
  }
}
</script>

<template>
  <div class="layout">
    <header class="layout__header">
      <div class="layout__brand" @click="router.push('/projects')">
        <span class="layout__logo">GA</span>
        <span class="layout__brand-text">GameAsset Hub</span>
      </div>

      <ProjectSwitcher />

      <el-tag
        v-if="projectStore.currentProject"
        :type="myRoleMeta.tagType"
        effect="plain"
        size="small"
        class="layout__role"
      >
        我的角色：{{ myRoleMeta.label }}
      </el-tag>

      <div class="layout__spacer" />

      <NotificationBell />

      <ThemeToggle />

      <el-tooltip content="返回我的项目" placement="bottom">
        <el-button text circle @click="router.push('/projects')">
          <el-icon :size="18"><ArrowLeft /></el-icon>
        </el-button>
      </el-tooltip>

      <el-dropdown trigger="click">
        <div class="layout__user">
          <el-avatar :size="28" class="layout__avatar">{{ userStore.avatarText }}</el-avatar>
          <span class="layout__username">{{ userStore.displayName }}</span>
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

    <div class="layout__body">
      <aside class="layout__sider">
        <nav class="layout__nav">
          <router-link
            v-for="item in navItems"
            :key="item.name"
            :to="`/project/${projectId}/${item.path}`"
            class="layout__nav-item"
            :class="{ 'is-active': activePath === item.path }"
          >
            <el-icon :size="17"><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </router-link>
        </nav>

        <div v-if="projectStore.currentProject" class="layout__meta">
          <div class="layout__meta-row">
            <span class="muted">引擎</span>
            <span>{{ projectStore.currentProject.engine || '未设置' }}</span>
          </div>
          <div class="layout__meta-row">
            <span class="muted">代号</span>
            <span class="mono">{{ projectStore.currentProject.code || '—' }}</span>
          </div>
          <div class="layout__meta-row">
            <span class="muted">成员</span>
            <span>{{ projectStore.currentProject.memberCount }} 人</span>
          </div>
          <div class="layout__meta-row">
            <span class="muted">计划结束</span>
            <span>{{ projectStore.currentProject.planEndDate || '未设置' }}</span>
          </div>
        </div>
      </aside>

      <main v-loading="loading" class="layout__content">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-base);
}

.layout__header {
  flex: none;
  display: flex;
  align-items: center;
  gap: 14px;
  height: 58px;
  padding: 0 18px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-panel);
}

.layout__brand {
  display: flex;
  align-items: center;
  gap: 9px;
  cursor: pointer;
  user-select: none;
}

.layout__logo {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: -0.5px;
  color: #fff;
  background: linear-gradient(135deg, var(--accent), #8f6fff);
}

.layout__brand-text {
  font-weight: 700;
  font-size: 15px;
  letter-spacing: 0.2px;
}

.layout__role {
  margin-left: 2px;
}

.layout__spacer {
  flex: 1;
}

.layout__user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 8px;
  cursor: pointer;
}

.layout__user:hover {
  background: var(--bg-hover);
}

.layout__avatar {
  background: var(--accent);
  color: #fff;
  font-weight: 600;
}

.layout__username {
  font-size: 13px;
}

.layout__body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.layout__sider {
  flex: none;
  width: 208px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border);
  background: var(--bg-panel);
  padding: 14px 10px;
}

.layout__nav {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.layout__nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: 8px;
  color: var(--text-primary);
  text-decoration: none;
  font-size: 13.5px;
  transition: background-color 0.15s ease;
}

.layout__nav-item > span:first-of-type {
  flex: 1;
}

.layout__nav-item:hover {
  background: var(--bg-hover);
}

.layout__nav-item.is-active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}

.layout__meta {
  margin-top: auto;
  padding: 12px 12px 4px;
  border-top: 1px dashed var(--border);
  display: grid;
  gap: 6px;
  font-size: 12px;
}

.layout__meta-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.layout__content {
  flex: 1;
  min-width: 0;
  overflow: auto;
  padding: 22px 26px;
}
</style>
