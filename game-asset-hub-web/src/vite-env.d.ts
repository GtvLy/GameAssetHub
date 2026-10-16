/// <reference types="vite/client" />

/**
 * .vue 单文件组件的类型声明。
 * 没有它 TS 无法 import .vue 文件（vue-tsc 会接管，但 tsc 单独跑会报错）。
 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
