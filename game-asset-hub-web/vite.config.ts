import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 配置
 *
 * dev 端口固定 5175；/api 代理到后端 8082，开发期同源，
 * 因此浏览器不会触发 CORS 预检，JWT 与 X-Project-Id 头都能正常带上。
 *
 * 代理目标可用环境变量 VITE_PROXY_TARGET 覆盖（默认仍是 8082）。
 * 留这个口子是为了「不占用 8082 也能做前端渲染冒烟」：
 * 用临时库在别的端口起一个后端，再把 dev server 指过去即可，
 * 既不用改这个文件，也不会误伤别人跑在 8082 上的实例。
 */
const apiTarget = process.env.VITE_PROXY_TARGET || 'http://localhost:8082'

export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },

  server: {
    port: 5175,
    // 端口被占用时直接失败，而不是悄悄换到 5176——否则联调时前端连的不是你以为的那个
    strictPort: true,
    open: false,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true
      }
    }
  },

  build: {
    outDir: 'dist',
    // 生产环境不生成 sourcemap，避免源码泄露与体积膨胀
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // 把体积最大的依赖单独切块：Element Plus 与 Vue 运行时很少变动，
        // 独立分包后业务代码更新不会让用户重新下载整包。
        //
        // 注意写法：Vite 8 底层换成了 Rolldown，manualChunks 只接受<b>函数</b>形式，
        // 传对象会直接报 "manualChunks is not a function" 并中断构建。
        manualChunks(id: string) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('element-plus') || id.includes('@element-plus')) return 'element-plus'
          if (id.includes('/vue/') || id.includes('vue-router') || id.includes('pinia')) return 'vue'
          return undefined
        }
      }
    }
  }
})
