import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
// Element Plus 官方深色变量。我们的 dark-theme.scss 会覆盖其中关键几项，
// 所以这里必须先引官方样式，再引自己的
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@/styles/index.scss'

import App from './App.vue'
import router from './router'
import { useThemeStore } from './stores/theme'

const app = createApp(App)

app.use(createPinia())
app.use(router)

// 全量引入 Element Plus 并指定中文 locale：
// 分页、日期选择、表单校验提示等内置文案默认是英文，必须显式换掉。
app.use(ElementPlus, { locale: zhCn })

// 图标全量注册：Element Plus 的图标是独立包，不注册模板里写 <el-icon><User /></el-icon> 会渲染不出来
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

// 主题在挂载前初始化，避免先渲染一帧再切色
useThemeStore().init()

app.mount('#app')
