import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/http'
import { useSessionStore } from './stores/session'
import './styles/tokens.css'
import './styles/base.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

/**
 * 把"令牌失效了"这件事接到会话与路由上。
 *
 * 为什么在这里注册而不是在 http.ts 里直接 import session store：
 * http.ts → (若 import) session.ts → api/auth.ts → http.ts 会成环，
 * 且 Pinia store 在模块顶层被引用时会撞上"store 尚未安装"的报错。
 * 这里注册回调，依赖方向就是单向的。
 *
 * 注意必须传 `pinia` 给 useSessionStore：注册发生在 app.mount() 之前，
 * 此刻还没有活跃的 Pinia 实例，不显式指定会抛
 * "getActivePinia() was called but there was no active Pinia"。
 */
setUnauthorizedHandler(() => {
  const session = useSessionStore(pinia)
  session.clear()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    router.replace({ path: '/login', query: { redirect: current.fullPath } })
  }
})

app.mount('#app')
