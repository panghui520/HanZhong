import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '@/stores/session'

/**
 * 路由表。
 *
 * `meta.requiresAuth` / `meta.requiresAdmin` 是给下面的守卫读的，
 * 写在路由上而不是在守卫里逐个 path 判断 —— 新增页面时"要不要登录"
 * 和"路由长什么样"写在同一处，不会漏。
 */
const routes = [
  {
    path: '/',
    component: () => import('@/layouts/PortalLayout.vue'),
    children: [
      { path: '', name: 'home', component: () => import('@/views/portal/Home.vue') },
      { path: 'explore', name: 'explore', component: () => import('@/views/portal/Explore.vue') },
      {
        path: 'assistant',
        name: 'assistant',
        component: () => import('@/views/portal/Assistant.vue'),
      },
      {
        path: 'itinerary',
        name: 'itinerary',
        component: () => import('@/views/portal/Itinerary.vue'),
      },
      { path: 'poi/:id', name: 'poi', component: () => import('@/views/portal/PoiDetail.vue') },
    ],
  },
  // 登录页独立于 PortalLayout：不显示顶栏与页脚，保持沉浸感
  { path: '/login', name: 'login', component: () => import('@/views/portal/Login.vue') },
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
    children: [
      { path: '', redirect: '/admin/dashboard' },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/admin/Dashboard.vue'),
      },
      {
        // M9 图片管理：轮播图 + 每个景点的配图，上传/替换/删除/排序
        path: 'media',
        name: 'admin-media',
        component: () => import('@/views/admin/Media.vue'),
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/**
 * 全局前置守卫。
 *
 * 这里做的事：把用户送到"他该在的地方"，**不是安全边界**。
 * 真正的权限判定在后端（`SecurityConfig` 里 `/api/admin/**` 要求 OPERATOR）。
 * 前端守卫的意义是体验——未登录时不该先渲染一个空壳再让他看一堆 4001。
 *
 * 三条规则，顺序有讲究：
 *   1. 已登录的人不该再看到登录页（除非是主动切换账号，见下）
 *   2. 需要登录的页面：未登录 → 去登录页，并把原地址放进 redirect
 *   3. 需要管理员的页面：登录了但不是 OPERATOR → 回首页，不去登录页
 */
router.beforeEach(async (to) => {
  const session = useSessionStore()

  // 每个路由守卫都会跑，但 init 只该跑一次（它读 localStorage，幂等但没必要反复做）
  if (!session.ready) {
    session.init()
    // 有令牌就向服务端确认一次。只有服务端认了才放行，
    // 否则会出现"本地以为登录着、每个请求都 4001"的迷惑状态。
    if (session.token) await session.restore()
  }

  // 1. 已登录访问登录页 → 送走。
  //    带 ?switch=1 时例外：用户主动点"切换账号"要能回到登录页。
  if (to.name === 'login') {
    if (session.isLoggedIn && to.query.switch !== '1') {
      return session.isAdmin ? '/admin/dashboard' : '/'
    }
    return true
  }

  // 2. 需要登录
  if (to.meta.requiresAuth && !session.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 3. 需要管理员。注意：已登录但角色不够时**不跳登录页**——
  //    让他重新登录一次也不会变成管理员，只会更困惑。直接回首页。
  if (to.meta.requiresAdmin && !session.isAdmin) {
    return '/'
  }

  return true
})

export default router
