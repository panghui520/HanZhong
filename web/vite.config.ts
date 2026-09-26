import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  /**
   * 环境变量目录指向**仓库根**，而不是默认的 `web/`。
   *
   * 仓库里只有一份 `.env`（在根目录），Python 侧读的就是它。前端要用到高德
   * JS API 的 key 时，若沿用默认行为就得在 `web/` 下再放一份 ——
   * 两份 .env 的结果是"改了根的那份、前端没变"，一个只能靠现象猜的静默不一致。
   *
   * 安全性：Vite **只把 `VITE_` 前缀的变量内联进产物**，所以根 .env 里的
   * `LLM_API_KEY` / `MYSQL_PASSWORD` / `AMAP_KEY` 这些**不会**进前端包。
   * 这条规则是这里敢指向上层目录的前提。
   */
  envDir: '..',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    host: '127.0.0.1',
    proxy: {
      // 只代理 Java 业务后端。AI 能力走 /api/ai/**，由 Java 再转发给 Python，
      // 所以这里刻意没有 /ai -> 8000 这条规则：留着它等于给"绕过业务层直连
      // Python"开了条路，而 Python 只监听回环地址且要求内部令牌，
      // 前端直连既不安全也维护不了两套入口。
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    chunkSizeWarningLimit: 1200,
  },
})
