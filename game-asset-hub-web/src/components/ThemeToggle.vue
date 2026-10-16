<script setup lang="ts">
import { computed } from 'vue'
import { Moon, Sunny, Monitor } from '@element-plus/icons-vue'
import { useThemeStore, type ThemeMode } from '@/stores/theme'

/**
 * 主题切换：浅色 / 深色 / 跟随系统 三态。
 *
 * 做成三态而不是两态，是因为文档 6 明确要求「跟随系统」；
 * 两态开关一旦点过就会永久固化用户选择，失去跟随系统的能力。
 */
const themeStore = useThemeStore()

const options: Array<{ value: ThemeMode; label: string; icon: unknown }> = [
  { value: 'light', label: '浅色', icon: Sunny },
  { value: 'dark', label: '深色', icon: Moon },
  { value: 'auto', label: '跟随系统', icon: Monitor }
]

const current = computed(() => themeStore.mode)
</script>

<template>
  <el-dropdown trigger="click" @command="themeStore.setMode($event as ThemeMode)">
    <el-button text circle :title="`主题：${options.find((o) => o.value === current)?.label}`">
      <el-icon :size="18">
        <Moon v-if="themeStore.isDark" />
        <Sunny v-else />
      </el-icon>
    </el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="option in options"
          :key="option.value"
          :command="option.value"
          :class="{ 'is-active-theme': current === option.value }"
        >
          <el-icon><component :is="option.icon" /></el-icon>
          <span style="margin-left: 8px">{{ option.label }}</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
:deep(.is-active-theme) {
  color: var(--accent);
  font-weight: 600;
}
</style>
