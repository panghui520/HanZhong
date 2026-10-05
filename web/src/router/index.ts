import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '@/stores/session'

/**
 * 路由表。
 *
 * `meta.requiresAuth` / `meta.requiresAdmin` 是给下面的守卫读的，
 * 写在路由上而不是在守卫里逐个 path 判断 —— 新增页面时"要不要登录"
 * 和"路由长什么样"写在同一处，不会漏。
 */
/**
 * 重定向函数拿到的 `to`。
 *
 * 用**结构化的最小类型**而不是 vue-router 的 `RouteLocationNormalized`：
 * 后者在不同小版本里叫法不同（`RouteLocationGeneric` / `RouteLocationNormalized`），
 * 而这里只用到 `query` 一个字段。写最小需求，升级路由库时不会因为类型改名而红。
 */
type RedirectFrom = { query: Record<string, unknown> }

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
      {
        // M6 到访消费链：我的足迹。
        // requiresAuth 与购物车/订单一致 —— 足迹是"我的数据"，
        // 而且它决定「乡村好物」先给你看什么，未登录时这个页面没有内容可给。
        // 页面里那个"登录后查看"的空态是**兜底**（在页面上退出登录时命中），
        // 正常从导航点进来会先被上面的守卫送去登录页。
        path: 'footprints',
        name: 'footprints',
        component: () => import('@/views/portal/Footprints.vue'),
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
        // M10 资源管理：景点 / 美食 / 农产品的列表、搜索、状态筛选与上下架维护。
        //
        // ★ 2026-10-04 起**不再是一级页面**：管理端按业务拆成了「乡村景点管理 /
        //   餐饮管理 / 农产品管理 / 住宿管理」四个职责单一的页面，这条路由只作为
        //   **兼容跳转**保留 —— 驾驶舱的旧链接、验收探针的期望值、答辩 PPT 的截图
        //   都还写着 `/admin/resources?kind=...`，直接删掉会让它们无谓变红。
        path: 'resources',
        name: 'admin-resources-legacy',
        redirect: (to: RedirectFrom) => legacyResourcesRedirect(to.query),
      },
      {
        // 农产品管理：销售分析 + 商品管理 + 订单处理（订单不再是独立一级页面）
        path: 'products',
        name: 'admin-products',
        component: () => import('@/views/admin/Products.vue'),
      },
      {
        // 乡村景点管理：核心景区 + 乡村景点（页面内分段，不是两个一级菜单）
        path: 'attractions',
        name: 'admin-attractions',
        component: () => import('@/views/admin/Attractions.vue'),
      },
      {
        // 餐饮管理：餐饮资源分析 + 资源维护。**只有 6 个点**，不做 Top10
        path: 'restaurants',
        name: 'admin-restaurants',
        component: () => import('@/views/admin/Restaurants.vue'),
      },
      {
        // 住宿管理：住宿资源分析 + 资源维护。**只有 4 个点**
        path: 'hotels',
        name: 'admin-hotels',
        component: () => import('@/views/admin/Hotels.vue'),
      },
      {
        // M9 图片管理：轮播图 + 每个景点的配图，上传/替换/删除/排序
        path: 'media',
        name: 'admin-media',
        component: () => import('@/views/admin/Media.vue'),
      },
      {
        // M5 风险与工单：规则引擎判定的风险事件 → 建单 → 处置闭环
        path: 'risks',
        name: 'admin-risks',
        component: () => import('@/views/admin/Risks.vue'),
      },
      {
        // M6 订单处理。★ 2026-10-04 起并入「农产品管理」的一个 tab，不再是
        // 一级导航。这条路由保留为兼容跳转（同上，旧链接要能用）。
        path: 'orders',
        name: 'admin-orders-legacy',
        redirect: (to: RedirectFrom) => ({
          path: '/admin/products',
          query: { ...to.query, tab: 'orders' },
        }),
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

/**
 * 旧资源路由 → 新业务页。
 *
 * `?kind=` 的四个取值映射到四个页面；`btype=RURAL_SPOT`（驾驶舱「乡村到访占比」
 * 卡曾经带的参数）翻译成景点页内部的 `seg=rural`。其余 query（`range` / `keyword` /
 * `status`）原样带过去 —— **跨页传参按参数全集对齐**，漏一个就会出现
 * "点进去筛选条件没了"这种静默不一致。
 */
function legacyResourcesRedirect(query: Record<string, unknown>) {
  const q: Record<string, string> = {}
  for (const [k, v] of Object.entries(query)) {
    if (typeof v === 'string' && k !== 'kind' && k !== 'btype') q[k] = v
  }
  const kind = String(query.kind ?? 'scenic')
  if (kind === 'food') return { path: '/admin/restaurants', query: q }
  if (kind === 'product') return { path: '/admin/products', query: q }
  // 评论管理现在**常驻**在景点页底部（评论只挂在 poi 上），
  // 所以不需要再传一个 tab 参数 —— 传了也没人读，那是个被忽略的参数
  if (kind === 'comment') return { path: '/admin/attractions', query: q }
  if (String(query.btype ?? '') === 'RURAL_SPOT') return { path: '/admin/attractions', query: { ...q, seg: 'rural' } }
  return { path: '/admin/attractions', query: q }
}

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
