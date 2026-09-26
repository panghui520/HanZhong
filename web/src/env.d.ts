/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

/**
 * 前端能读到的环境变量。
 *
 * 只有 `VITE_` 前缀的变量会被 Vite 内联进产物，所以这里列出的就是
 * **全部**能从 `.env` 流到浏览器的值。没有列在这里的（`LLM_API_KEY`、
 * `MYSQL_PASSWORD`、`AMAP_KEY`…）前端读不到，也**不该**读到。
 * 变量目录配在 `vite.config.ts` 的 `envDir: '..'`（读仓库根那一份 .env）。
 *
 * 显式写出来而不是靠 vite/client 那个 `[key: string]: any` 索引签名：
 * 索引签名下拼错一个键名（写成 `VITE_AMAP_JS_KE`）拿到的是 `any`，
 * 编译不报错、运行时是 undefined，最后表现为"地图白屏但看不出原因"。
 */
interface ImportMetaEnv {
  /** 高德 Web 端(JS API) key。与 Python 侧那个 AMAP_KEY 不是同一个 key */
  readonly VITE_AMAP_JS_KEY?: string
  /** JS API 2.0 的安全密钥。必须在加载脚本前设置，否则地图鉴权失败 */
  readonly VITE_AMAP_JS_SECURITY_CODE?: string
}
