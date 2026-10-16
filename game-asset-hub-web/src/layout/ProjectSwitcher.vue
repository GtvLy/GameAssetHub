<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Plus } from '@element-plus/icons-vue'
import { useProjectStore } from '@/stores/project'
import { roleMeta } from '@/constants'

/**
 * 顶栏项目切换器。
 *
 * 切换时必须同步做两件事：
 *   1. 更新 store.currentId —— 请求拦截器据此注入 X-Project-Id；
 *   2. 跳转到新项目的对应页面 —— 停留在旧项目的子路由上会 403（后端按路径解析 projectId）。
 * 只做第 1 步不做第 2 步，就会出现「地址栏还是旧项目、数据是新项目」的错乱。
 */
const projectStore = useProjectStore()
const router = useRouter()
const route = useRoute()

const currentName = computed(() => projectStore.currentProject?.name || '选择项目')
const currentMeta = computed(() => roleMeta(projectStore.currentProject?.myRole))

async function handleSelect(projectId: number) {
  if (projectId === projectStore.currentId) return
  projectStore.setCurrent(projectId)
  // 保留当前所在的子页面（概览/成员/…），只换项目ID
  const childPath = route.path.split('/')[3] || 'overview'
  await router.push(`/project/${projectId}/${childPath}`)
}

function goCreate() {
  router.push({ path: '/projects', query: { create: '1' } })
}
</script>

<template>
  <el-dropdown trigger="click" @command="handleSelect">
    <div class="switcher" :title="currentName">
      <span class="switcher__badge">{{ currentName.charAt(0) }}</span>
      <div class="switcher__text">
        <div class="switcher__name">{{ currentName }}</div>
        <div class="switcher__role">
          <template v-if="projectStore.currentProject">
            {{ currentMeta.label }} · {{ projectStore.currentProject.statusLabel }}
          </template>
          <template v-else>尚未选择项目</template>
        </div>
      </div>
      <el-icon class="switcher__arrow"><ArrowDown /></el-icon>
    </div>

    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="project in projectStore.projects"
          :key="project.id"
          :command="project.id"
          :disabled="project.id === projectStore.currentId"
        >
          <div class="switcher__item">
            <span class="switcher__item-name">{{ project.name }}</span>
            <el-tag :type="roleMeta(project.myRole).tagType" size="small" effect="plain">
              {{ roleMeta(project.myRole).label }}
            </el-tag>
          </div>
        </el-dropdown-item>

        <el-dropdown-item v-if="!projectStore.projects.length" disabled>
          还没有参与任何项目
        </el-dropdown-item>

        <el-dropdown-item divided :command="-1" @click="goCreate">
          <el-icon><Plus /></el-icon>
          <span style="margin-left: 6px">新建项目</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.switcher {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 190px;
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-panel);
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.switcher:hover {
  border-color: var(--accent);
}

.switcher__badge {
  flex: none;
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  font-weight: 700;
  font-size: 13px;
  color: #fff;
  background: linear-gradient(135deg, var(--accent), #8f6fff);
}

.switcher__text {
  flex: 1;
  min-width: 0;
}

.switcher__name {
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.switcher__role {
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.3;
}

.switcher__arrow {
  flex: none;
  color: var(--text-muted);
}

.switcher__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  min-width: 200px;
}

.switcher__item-name {
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
