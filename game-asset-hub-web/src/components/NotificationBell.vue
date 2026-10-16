<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, Check } from '@element-plus/icons-vue'
import {
  fetchNotifications,
  fetchUnreadCount,
  markAllNotificationsRead,
  markNotificationRead
} from '@/api/notification'
import { useProjectStore } from '@/stores/project'
import { NOTIFICATION_TYPE_LABELS } from '@/constants/task'
import type { NotificationVO } from '@/types/task'

/**
 * 顶栏通知铃铛（文档 5.8 / 7.2）。
 *
 * 每 30 秒轮询一次未读数，有未读时角标 + 抖动；点开列出通知，
 * 点条目**先标记已读再跳转**到对应业务页（TASK → 看板并打开该任务，
 * ASSET → 资产详情）。
 *
 * 未读数与列表口径一致（都是「当前用户 + 当前项目」），
 * 不会出现「角标 3、点开只有 1 条」的矛盾。
 */
const POLL_INTERVAL_MS = 30_000

const router = useRouter()
const projectStore = useProjectStore()

const unread = ref(0)
const items = ref<NotificationVO[]>([])
const loading = ref(false)
const unreadOnly = ref(true)
const opened = ref(false)

const projectId = computed(() => projectStore.currentId)
const shaking = computed(() => unread.value > 0)

async function refreshUnread() {
  if (!projectId.value) {
    unread.value = 0
    return
  }
  try {
    unread.value = await fetchUnreadCount()
  } catch {
    // 未读数拿不到不该弹提示：铃铛在顶栏，每 30 秒一次，报错会刷屏
  }
}

async function loadList() {
  if (!projectId.value) return
  loading.value = true
  try {
    const page = await fetchNotifications({ page: 1, size: 15, unreadOnly: unreadOnly.value })
    items.value = page.records ?? []
  } catch {
    items.value = []
  } finally {
    loading.value = false
  }
}

let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  void refreshUnread()
  timer = setInterval(() => {
    // 页面不可见时跳过：后台标签页里的轮询只是在白耗请求
    if (document.visibilityState === 'visible') void refreshUnread()
  }, POLL_INTERVAL_MS)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

// 切换项目后未读数与列表都要重取：它们都是「当前项目」维度的
watch(projectId, () => {
  items.value = []
  void refreshUnread()
})

watch(opened, (value) => {
  if (value) void loadList()
})

watch(unreadOnly, () => {
  if (opened.value) void loadList()
})

/** 点条目：先标记已读并刷新角标，再跳到对应业务页 */
async function handleClick(item: NotificationVO) {
  opened.value = false
  try {
    if (item.isRead === 0) {
      await markNotificationRead(item.id)
      await refreshUnread()
    }
  } catch {
    // 通知可能已被别处标记已读，跳转不该被这一步挡住
  }
  goTo(item)
}

function goTo(item: NotificationVO) {
  const pid = projectId.value
  if (!pid || !item.bizType || !item.bizId) {
    router.push(`/project/${pid}/overview`)
    return
  }
  if (item.bizType === 'TASK') {
    // 带着 task 参数进看板，看板会自动打开这条任务的详情抽屉
    router.push({ path: `/project/${pid}/board`, query: { task: String(item.bizId) } })
  } else if (item.bizType === 'ASSET') {
    router.push(`/project/${pid}/assets/${item.bizId}`)
  } else {
    router.push(`/project/${pid}/overview`)
  }
}

async function handleReadAll() {
  try {
    await markAllNotificationsRead()
    await refreshUnread()
    await loadList()
    ElMessage.success('已全部标记为已读')
  } catch {
    /* 拦截器已提示 */
  }
}
</script>

<template>
  <el-popover v-model:visible="opened" :width="368" trigger="click" placement="bottom-end">
    <template #reference>
      <el-badge :value="unread" :hidden="unread === 0" :max="99" class="bell">
        <el-button text circle :class="{ 'bell--shake': shaking }" title="通知">
          <el-icon :size="18"><Bell /></el-icon>
        </el-button>
      </el-badge>
    </template>

    <div class="notify">
      <div class="notify__head">
        <span class="notify__title">通知</span>
        <span class="spacer" />
        <el-switch v-model="unreadOnly" size="small" active-text="只看未读" />
        <el-button text size="small" :icon="Check" @click="handleReadAll">全部已读</el-button>
      </div>

      <div v-loading="loading" class="notify__list">
        <el-empty v-if="!loading && !items.length" :image-size="52" description="没有通知" />

        <div
          v-for="item in items"
          :key="item.id"
          class="notify__item"
          :class="{ 'is-unread': item.isRead === 0 }"
          @click="handleClick(item)"
        >
          <div class="notify__row">
            <el-tag size="small" effect="plain" type="info">
              {{ NOTIFICATION_TYPE_LABELS[item.type] || item.typeLabel }}
            </el-tag>
            <span class="notify__heading">{{ item.title }}</span>
          </div>
          <div v-if="item.content" class="notify__content">{{ item.content }}</div>
          <div class="notify__meta">
            <span>{{ item.senderName || '系统' }}</span>
            <span>·</span>
            <span>{{ item.createTime }}</span>
            <span class="spacer" />
            <span v-if="item.isRead === 0" class="notify__dot" />
          </div>
        </div>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.spacer {
  flex: 1;
}

.bell {
  margin-right: 4px;
}

/* 有未读时轻微抖动，让铃铛在顶栏里能被注意到（文档 7.2） */
.bell--shake :deep(.el-icon) {
  animation: bell-shake 2.4s ease-in-out infinite;
  transform-origin: top center;
}

@keyframes bell-shake {
  0%,
  84%,
  100% {
    transform: rotate(0deg);
  }
  88% {
    transform: rotate(12deg);
  }
  92% {
    transform: rotate(-10deg);
  }
  96% {
    transform: rotate(6deg);
  }
}

.notify {
  display: grid;
  gap: 8px;
}

.notify__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.notify__title {
  font-weight: 600;
  font-size: 13.5px;
}

.notify__list {
  max-height: 380px;
  overflow-y: auto;
  display: grid;
  gap: 2px;
}

.notify__item {
  padding: 8px 9px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.14s ease;
}

.notify__item:hover {
  background: var(--bg-hover);
}

.notify__item.is-unread {
  background: var(--accent-soft);
}

.notify__row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.notify__heading {
  flex: 1;
  font-size: 12.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notify__content {
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.notify__meta {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-muted);
}

.notify__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--danger);
}
</style>
