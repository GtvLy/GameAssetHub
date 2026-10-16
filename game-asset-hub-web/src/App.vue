<script setup lang="ts">
/**
 * 应用根组件。
 *
 * 只负责渲染路由出口。主题初始化、Element Plus 注册都在 main.ts 完成，
 * 这里保持"薄"，避免根组件承担过多职责后难以测试。
 */
</script>

<template>
  <router-view v-slot="{ Component }">
    <component :is="Component" />
  </router-view>
</template>

<style>
/* 全局样式在 styles/index.scss 里，这里不重复定义 */
</style>
