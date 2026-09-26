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
        // M4 互动地图。与「探索汉中」**是两个页面**，不是同一页的两种视图：
        // 探索页是"按类别 → 主题 → 区县逐层收窄"的浏览（刻意不做"全部"档），
        // 这一页是"按真实经纬度看全貌 + 点标注看卡片"。
        // 两者读的是同一个 `/api/pois`，都没有自己的数据源。
        path: 'map',
        name: 'map',
        component: () => import('@/views/portal/InteractiveMap.vue'),
      },
      {
        path: 'assistant',
        name: 'assistant',
        component: () => import('@/views/portal/Assistant.vue'),
      },
      {
        // M4 AI 旅游助手。与 /assistant 是两个页面而不是一个页面的两个标签：
        // 知识问答的契约是"检索 + 出处"（meta.route / sources），
        // 助手的契约是"工具调度 + 卡片"（meta.tools / tool / cards）。
        // 两套协议、两套后端端点（/ai/qa 与 /ai/agent），页面也就该分开。
        path: 'agent',
        name: 'agent',
        component: () => import('@/views/portal/Agent.vue'),
      },
      {
        path: 'itinerary',
        name: 'itinerary',
        component: () => import('@/views/portal/Itinerary.vue'),
      },
      { path: 'poi/:id', name: 'poi', component: () => import('@/views/portal/PoiDetail.vue') },

      // ---------------- M6 消费与离境复购 ----------------
      // 乡村好物可以随便看（它就是一份"汉中的味道"清单），
      // 但从加购开始要登录：购物车与订单都是"我的数据"。
      // 后端 SecurityConfig 里 /api/cart/** 与 /api/orders/** 也是这么配的，
      // 两边保持一致 —— 前端这层只管体验，真正的拦截在后端。
      { path: 'goods', name: 'goods', component: () => import('@/views/portal/Goods.vue') },
      {
        path: 'cart',
        name: 'cart',
        component: () => import('@/views/portal/Cart.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'checkout',
        name: 'checkout',
        component: () => import('@/views/portal/Checkout.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/portal/Orders.vue'),
        meta: { requiresAuth: true },
      },
      {
        // M6 订单详情：状态时间线、物流、付款、退款、评价都在这一页。
        // 结算成功后会直接跳到这里（见 Checkout.vue），
        // 因为用户那一刻唯一想做的事就是付款。
        path: 'orders/:id(\\d+)',
        name: 'order-detail',
        component: () => import('@/views/portal/OrderDetail.vue'),
        meta: { requiresAuth: true },
      },
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
      {
        // M6 订单处理：待发货 → 已发货
        path: 'orders',
        name: 'admin-orders',
        component: () => import('@/views/admin/Orders.vue'),
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
