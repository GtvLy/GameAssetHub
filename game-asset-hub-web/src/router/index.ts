import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由表
 *
 * 结构上分三层：
 *   /login                     免登录
 *   /projects                  我的项目（顶层，用自己的顶栏）
 *   /project/:projectId/*      项目内，共用 ProjectLayout（概览/资产/看板/统计/成员）
 *
 * 四个模块的页面都已实现并挂在真实组件上；模块四把原来的统计占位页
 * 拆成了燃尽图 / 工作量 / 周报三个页面，旧地址 /stats 重定向到燃尽图。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    redirect: '/projects'
  },
  {
    path: '/projects',
    name: 'project-list',
    component: () => import('@/views/project/ProjectListView.vue'),
    meta: { title: '我的项目' }
  },
  {
    path: '/project/:projectId',
    component: () => import('@/layout/ProjectLayout.vue'),
    props: true,
    children: [
      {
        path: '',
        redirect: (to) => ({ name: 'project-overview', params: to.params })
      },
      {
        path: 'overview',
        name: 'project-overview',
        component: () => import('@/views/project/ProjectOverviewView.vue'),
        meta: { title: '概览', icon: 'DataBoard' }
      },
      {
        path: 'assets',
        name: 'project-assets',
        component: () => import('@/views/asset/AssetListView.vue'),
        meta: { title: '资产', icon: 'Box' }
      },
      {
        // 资产详情。路径里带 assetId，天然可分享、可刷新——
        // 状态放在组件里而不是 query 上，正是为了这一点
        path: 'assets/:assetId',
        name: 'project-asset-detail',
        component: () => import('@/views/asset/AssetDetailView.vue'),
        // activePath 让侧栏「资产」在详情页保持高亮：默认按路径最后一段匹配，
        // 而这里最后一段是资产ID，会落空
        meta: { title: '资产详情', icon: 'Box', activePath: 'assets' }
      },
      {
        path: 'board',
        name: 'project-board',
        component: () => import('@/views/board/KanbanView.vue'),
        // 支持 ?task=305 直接打开某条任务的详情：通知铃铛点进来就落在这里
        meta: { title: '看板', icon: 'Grid' }
      },
      {
        // 模块四把原来的占位页拆成三个真实页面。旧地址 /project/:id/stats
        // 保留并重定向到燃尽图，避免别人收藏过的链接 404
        path: 'stats',
        redirect: (to) => ({ name: 'project-burndown', params: to.params })
      },
      {
        path: 'burndown',
        name: 'project-burndown',
        component: () => import('@/views/stat/BurndownView.vue'),
        meta: { title: '燃尽图', icon: 'TrendCharts' }
      },
      {
        path: 'workload',
        name: 'project-workload',
        component: () => import('@/views/stat/WorkloadView.vue'),
        meta: { title: '工作量', icon: 'Histogram' }
      },
      {
        path: 'report',
        name: 'project-report',
        component: () => import('@/views/stat/ReportView.vue'),
        meta: { title: '周报', icon: 'Document' }
      },
      {
        path: 'members',
        name: 'project-members',
        component: () => import('@/views/project/MemberView.vue'),
        meta: { title: '成员', icon: 'User' }
      }
    ]
  },
  {
    // 兜底：未匹配的地址回项目列表（已登录）或登录页（未登录，由守卫处理）
    path: '/:pathMatch(.*)*',
    redirect: '/projects'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/**
 * 全局前置守卫
 *
 * 只做「有没有登录」这一件事；「有没有权限看这个项目」由后端 RBAC 判定
 * （前端隐藏按钮只是体验优化，不能当安全边界）。
 */
router.beforeEach((to) => {
  const token = localStorage.getItem('gah_token')

  if (!token && !to.meta.public) {
    return { path: '/login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  if (token && to.path === '/login') {
    // 已登录还去登录页，直接送回项目列表
    return { path: '/projects' }
  }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string) || ''
  document.title = title ? `${title} · GameAsset Hub` : 'GameAsset Hub'
})

export default router
