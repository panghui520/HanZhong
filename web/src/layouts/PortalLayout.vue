<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useCartStore } from '@/stores/cart'
import { useOrderStore } from '@/stores/order'
import { useSessionStore } from '@/stores/session'

const scrolled = ref(false)
function onScroll() {
  scrolled.value = window.scrollY > 24
}
onMounted(() => {
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
})
onUnmounted(() => {
  window.removeEventListener('scroll', onScroll)
  document.removeEventListener('click', onDocClick)
})

const session = useSessionStore()
const cart = useCartStore()
const order = useOrderStore()
const router = useRouter()
const route = useRoute()

/**
 * 顶栏两个角标（M6）：购物车件数、待处理订单数。
 *
 * 两处要刷新：应用启动时拉一次、登录态变化时再拉一次。
 * 退出登录必须清掉 —— 否则下一个人登录时会看到上一个人的角标数字。
 * 加购 / 下单 / 付款这些动作之后由**触发方**主动调 `refresh()`
 * （Goods、Cart、Orders、OrderDetail 都调了），这里不再 watch 路由，
 * 避免每次跳转都打两个接口。
 */
watch(
  () => session.isLoggedIn,
  (v) => {
    if (v) {
      void cart.refresh()
      void order.refresh()
    } else {
      cart.reset()
      order.reset()
    }
  },
  { immediate: true }
)

/** 首页 Hero 是全屏大图，顶栏在未滚动前保持透明压在图上 */
const overHero = computed(() => route.name === 'home' && !scrolled.value)

const navs = [
  { label: '首页', to: '/' },
  { label: '探索汉中', to: '/explore' },
  // M4 互动地图。紧挨着「探索汉中」放：两页看的是同一批景点，
  // 只是"按分类浏览"与"按位置看全貌"两种视角，放在一起用户才会知道
  // 它们是同一件事的两种看法，而不是两个不同的景点库。
  { label: '互动地图', to: '/map' },
  { label: '行程规划', to: '/itinerary' },
  { label: '知识问答', to: '/assistant' },
  // M4 AI 旅游助手。与「知识问答」并排而不是替换它：
  // 两者回答的是不同的问题（城市公开知识 vs 我现在该去哪儿），
  // 用的数据源也不同（本地知识库 vs 高德地图），合并会两边都说不清。
  { label: 'AI 助手', to: '/agent' },
  { label: '乡村好物', to: '/goods' },
]

/* ---------- 用户菜单 ---------- */
const menuOpen = ref(false)
function toggleMenu() {
  menuOpen.value = !menuOpen.value
}
function onDocClick(e: MouseEvent) {
  const el = e.target as HTMLElement | null
  if (el && !el.closest('.usermenu')) menuOpen.value = false
}
document.addEventListener('click', onDocClick)

/**
 * 管理入口的落点。
 *
 * 三种情况要说清楚，免得用户点了一个"管理入口"却毫无反应：
 *   - 已登录的运营：进驾驶舱
 *   - 未登录：去登录页
 *   - 已登录的游客：**不显示这个入口** —— 他点了也进不去（后端 4003），
 *     给他一个必然失败的按钮是误导。所以下面用 v-if 挡掉。
 */
const adminEntry = computed(() => (
  session.isAdmin
    ? { to: '/admin/dashboard', label: '运营驾驶舱' }
    : { to: '/login', label: '管理入口' }
))

/**
 * 退出登录（M8）。
 *
 * 必须 await：旧版是本地清一下就完事，现在要先调 /api/me/logout
 * 让服务端把 token_version +1，否则本地登出了、令牌在服务端还有效，
 * 拿抓包工具复制出来的旧令牌仍然能用 —— 那是"看起来退出了"。
 * store 内部保证接口失败也会清本地，所以这里不用包 try/catch。
 */
async function logout() {
  menuOpen.value = false
  await session.logout()
  router.push('/')
}
</script>

<template>
  <div class="portal">
    <header
      class="nav"
      :class="{ 'nav--solid': scrolled || !overHero, 'nav--over': overHero }"
    >
      <div class="container container-wide nav__inner">
        <router-link to="/" class="brand">
          <span class="brand__seal">汉</span>
          <span class="brand__text">
            <span class="brand__name">汉游智脑</span>
            <span class="brand__sub">HANYOU BRAIN</span>
          </span>
        </router-link>

        <nav class="nav__links">
          <router-link v-for="n in navs" :key="n.to" :to="n.to" class="nav__link">
            {{ n.label }}
          </router-link>
        </nav>

        <div class="nav__actions">
          <!--
            我的订单入口（M6）。提到一级导航而不是只放在用户下拉菜单里：
            下单后用户最需要的是"去哪付款 / 我的货到哪了"，那两件事都要
            先进订单页。埋在下拉菜单里等于让他先猜一次。

            **有需要处理的订单时变金色实心**（待付款 / 待收货），
            没有时是普通描边 —— 常态下不抢视觉，有事时一眼能看到。
            数字来自服务端 /api/orders/count，不在本地猜。
          -->
          <router-link
            v-if="session.isLoggedIn"
            to="/orders"
            class="nav__orders"
            :class="{ 'nav__orders--todo': order.pending > 0 }"
            :title="
              order.pending > 0
                ? `我的订单 · ${order.pending} 单待处理`
                : `我的订单 · 共 ${order.total} 单`
            "
          >
            <svg viewBox="0 0 24 24" width="17" height="17" aria-hidden="true">
              <path
                d="M6 3.2h12a1 1 0 0 1 1 1v15.6a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4.2a1 1 0 0 1 1-1Z"
                fill="none"
                stroke="currentColor"
                stroke-width="1.7"
                stroke-linejoin="round"
              />
              <path
                d="M8.4 8h7.2M8.4 12h7.2M8.4 16h4"
                fill="none"
                stroke="currentColor"
                stroke-width="1.7"
                stroke-linecap="round"
              />
            </svg>
            <span class="nav__orders-label">我的订单</span>
            <span v-if="order.pending > 0" class="nav__orders-n num">{{ order.pending }}</span>
          </router-link>

          <!--
            我的足迹（M6 到访消费链）。只在登录后显示，与订单同理。
            **刻意不带角标**：足迹不像待付款订单那样"有事要办"，
            给它一个数字会让人以为"还有 X 条没处理"，而它只是记录。
            没有数字也就不需要额外一次请求。
          -->
          <router-link
            v-if="session.isLoggedIn"
            to="/footprints"
            class="nav__orders"
            title="我的足迹"
          >
            <svg viewBox="0 0 24 24" width="17" height="17" aria-hidden="true">
              <path
                d="M12 21s6.5-5.6 6.5-10.3A6.5 6.5 0 0 0 5.5 10.7C5.5 15.4 12 21 12 21Z"
                fill="none"
                stroke="currentColor"
                stroke-width="1.7"
                stroke-linejoin="round"
              />
              <circle
                cx="12"
                cy="10.4"
                r="2.4"
                fill="none"
                stroke="currentColor"
                stroke-width="1.7"
              />
            </svg>
            <span class="nav__orders-label">我的足迹</span>
          </router-link>

          <!--
            购物车入口（M6）。只在登录后显示：
            未登录时点进去也只是一个"请登录"的空壳，不如先让他看到登录入口。
            角标数字来自服务端 /api/cart/count，不在本地猜。
          -->
          <router-link
            v-if="session.isLoggedIn"
            to="/cart"
            class="nav__cart"
            :title="`购物车 ${cart.count} 件`"
          >
            <svg viewBox="0 0 24 24" width="17" height="17" aria-hidden="true">
              <path
                d="M3 5h2.2l2.1 10.2h10.4l1.9-7.4H6.1"
                fill="none"
                stroke="currentColor"
                stroke-width="1.7"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
              <circle cx="9.4" cy="19" r="1.5" fill="currentColor" />
              <circle cx="16.6" cy="19" r="1.5" fill="currentColor" />
            </svg>
            <span class="nav__cart-label">购物车</span>
            <span v-if="cart.count > 0" class="nav__cart-n num">{{ cart.count }}</span>
          </router-link>

          <!-- 未登录：明确给出「登录 / 注册」入口 -->
          <router-link
            v-if="!session.isLoggedIn"
            to="/login"
            class="nav__login"
          >
            登录 / 注册
          </router-link>

          <!-- 已登录：展示身份 + 下拉 -->
          <div v-else class="usermenu">
            <button class="usermenu__trigger" @click.stop="toggleMenu">
              <span class="usermenu__avatar">{{ session.displayName.slice(0, 1) }}</span>
              <span class="usermenu__name">{{ session.displayName }}</span>
              <span v-if="session.isAdmin" class="tag tag-gold usermenu__role">运营</span>
              <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
                <path
                  d="M6 9 L12 15 L18 9"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.8"
                  stroke-linecap="round"
                />
              </svg>
            </button>
            <div v-if="menuOpen" class="usermenu__panel">
              <router-link v-if="session.isAdmin" to="/admin/dashboard" class="usermenu__item">
                运营驾驶舱
              </router-link>
              <router-link v-else to="/itinerary" class="usermenu__item">
                我的行程
              </router-link>
              <!-- 我的订单：游客与运营都能下单，所以不分角色，登录即可见 -->
              <router-link to="/orders" class="usermenu__item">我的订单</router-link>
              <router-link v-if="!session.isAdmin" to="/assistant" class="usermenu__item">
                知识问答
              </router-link>
              <button class="usermenu__item usermenu__item--danger" @click="logout">
                退出登录
              </button>
            </div>
          </div>

          <!--
            管理入口（M8）。旧版会跳 `/login?role=admin` 让用户"选身份"，
            现在角色由后端按账号判定，登录时选不了 —— 所以这个入口只做导航：
            是 OPERATOR 直接进驾驶舱，否则去登录页（未登录）或留在原地（已登录的普通游客）。
            已登录的游客看不到它：给他一个点了必然 4003 的按钮是误导。
          -->
          <router-link
            v-if="!session.isLoggedIn || session.isAdmin"
            :to="adminEntry.to"
            class="btn btn-ghost btn-sm nav__admin"
          >
            {{ adminEntry.label }}
          </router-link>
        </div>
      </div>
    </header>

    <main>
      <router-view />
    </main>

    <footer class="footer">
      <div class="container footer__inner">
        <div class="footer__brand">
          <span class="brand__seal brand__seal--lg">汉</span>
          <div>
            <div class="footer__name">汉游智脑</div>
            <p class="footer__slogan">把游客的一次到访，变成一条持续的乡村消费链</p>
          </div>
        </div>
        <div class="footer__cols">
          <div class="footer__col">
            <div class="footer__title">平台</div>
            <router-link to="/explore">探索汉中</router-link>
            <router-link to="/itinerary">行程规划</router-link>
            <router-link to="/assistant">知识问答</router-link>
            <router-link to="/login">登录 / 注册</router-link>
          </div>
          <div class="footer__col">
            <div class="footer__title">离境复购</div>
            <router-link to="/goods">乡村好物</router-link>
            <router-link to="/cart">购物车</router-link>
            <router-link to="/orders">我的订单</router-link>
          </div>
          <div class="footer__col">
            <div class="footer__title">运营方</div>
            <router-link to="/login?role=admin">运营管理登录</router-link>
            <router-link to="/admin/dashboard">管理驾驶舱</router-link>
            <router-link to="/admin/orders">订单处理</router-link>
          </div>
          <div class="footer__col">
            <div class="footer__title">数据来源</div>
            <span>汉中市文化和旅游局公开资料</span>
            <span>客流与订单为演示用仿真数据</span>
          </div>
        </div>
      </div>
      <div class="container footer__bottom">
        <span>第十二届中国研究生智慧城市技术与创意设计大赛 · 智慧文旅与乡村振兴</span>
        <span class="muted">演示系统，数据非真实统计口径</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.portal {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

/* ---------- 顶栏 ---------- */
.nav {
  position: sticky;
  top: 0;
  z-index: var(--z-nav);
  height: var(--nav-h);
  background: transparent;
  border-bottom: 1px solid transparent;
  transition: background var(--dur-2) var(--ease), border-color var(--dur-2) var(--ease),
    box-shadow var(--dur-2) var(--ease);
}
.nav--solid {
  background: rgba(250, 248, 243, 0.88);
  backdrop-filter: saturate(150%) blur(12px);
  border-bottom-color: var(--line-soft);
}

/* 首页压在超大 Hero 上：透明底 + 反白，滚下去再变实底 */
.nav--over {
  background: linear-gradient(180deg, rgba(11, 33, 25, 0.42) 0%, rgba(11, 33, 25, 0) 100%);
}
.nav--over .brand__name {
  color: #fff;
}
.nav--over .brand__sub {
  color: rgba(255, 255, 255, 0.58);
}
.nav--over .brand__seal {
  background: rgba(255, 255, 255, 0.12);
  color: var(--gold-300);
  box-shadow: inset 0 0 0 1px rgba(226, 202, 145, 0.6);
}
.nav--over .nav__link {
  color: rgba(255, 255, 255, 0.86);
}
.nav--over .nav__link:hover,
.nav--over .nav__link.router-link-exact-active {
  color: #fff;
}
.nav--over .nav__login {
  color: #fff;
  border-color: rgba(255, 255, 255, 0.42);
}
.nav--over .nav__login:hover {
  background: rgba(255, 255, 255, 0.16);
  border-color: rgba(255, 255, 255, 0.82);
}
.nav--over .nav__admin {
  color: rgba(255, 255, 255, 0.9);
  border-color: rgba(255, 255, 255, 0.28);
}
.nav--over .nav__admin:hover {
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.6);
  color: #fff;
}
.nav--over .usermenu__trigger {
  color: #fff;
  border-color: rgba(255, 255, 255, 0.3);
}
.nav--over .usermenu__trigger:hover {
  background: rgba(255, 255, 255, 0.14);
}

.nav__inner {
  height: 100%;
  display: flex;
  align-items: center;
  gap: var(--sp-6);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.brand__seal {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  background: var(--brand-700);
  color: var(--gold-300);
  font-family: var(--font-display);
  font-size: 19px;
  font-weight: 700;
  border-radius: var(--r-sm);
  box-shadow: inset 0 0 0 1px rgba(226, 202, 145, 0.45);
  flex: none;
}
.brand__seal--lg {
  width: 46px;
  height: 46px;
  font-size: 25px;
}
.brand__text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}
.brand__name {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--brand-800);
}
.brand__sub {
  font-size: 10px;
  letter-spacing: 0.24em;
  color: var(--warm-500);
}

.nav__links {
  display: flex;
  gap: var(--sp-6);
  margin-left: var(--sp-4);
}
.nav__link {
  position: relative;
  font-size: var(--fs-sm);
  color: var(--ink-700);
  padding: var(--sp-2) 0;
  transition: color var(--dur-1) var(--ease);
}
.nav__link::after {
  content: '';
  position: absolute;
  left: 0;
  right: 100%;
  bottom: 0;
  height: 1.5px;
  background: var(--gold-500);
  transition: right var(--dur-2) var(--ease);
}
.nav__link:hover,
.nav__link.router-link-exact-active {
  color: var(--brand-700);
}
.nav__link:hover::after,
.nav__link.router-link-exact-active::after {
  right: 0;
}

.nav__actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}

/* ---------- 购物车入口（M6） ---------- */
.nav__cart {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--ink-700);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  transition: all var(--dur-1) var(--ease);
}
.nav__cart:hover {
  color: var(--brand-700);
  border-color: var(--brand-300);
  background: var(--brand-50);
}
.nav__cart-label {
  font-weight: 500;
}
/* 角标用金色：与"离境复购"这条支线呼应，也不至于像未读消息那样刺眼 */
.nav__cart-n {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  display: inline-grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
  color: #3a2a0c;
  background: var(--gold-300);
  border-radius: var(--r-pill);
  line-height: 1;
}
.nav--over .nav__cart {
  color: rgba(255, 255, 255, 0.9);
  border-color: rgba(255, 255, 255, 0.28);
}
.nav--over .nav__cart:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.6);
}

/* ---------- 我的订单入口（M6） ---------- */
.nav__orders {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--ink-700);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  transition: all var(--dur-1) var(--ease);
}
.nav__orders:hover {
  color: var(--brand-700);
  border-color: var(--brand-300);
  background: var(--brand-50);
}
.nav__orders-label {
  font-weight: 500;
}
/*
  有需要处理的订单时才变金色实心。
  这样它平时不抢购物车的视觉，而一旦有待付款 / 待收货的单，
  在整条顶栏里是唯一的实心块 —— 用户扫一眼就知道有事要做。
*/
.nav__orders--todo {
  color: #3a2a0c;
  background: var(--gold-300);
  border-color: var(--gold-500);
  font-weight: 600;
}
.nav__orders--todo:hover {
  color: #2b1f09;
  background: var(--gold-500);
  border-color: var(--gold-500);
}
/* 金色底上的角标要反过来用深色，否则金压金看不出边界 */
.nav__orders-n {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  display: inline-grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
  color: var(--gold-300);
  background: var(--brand-800);
  border-radius: var(--r-pill);
  line-height: 1;
}
.nav--over .nav__orders {
  color: rgba(255, 255, 255, 0.9);
  border-color: rgba(255, 255, 255, 0.28);
}
.nav--over .nav__orders:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.6);
}
/* 压在 Hero 上时保持金色实心：那时顶栏是透明的，描边款几乎看不见 */
.nav--over .nav__orders--todo {
  color: #3a2a0c;
  background: var(--gold-300);
  border-color: var(--gold-500);
}
.nav--over .nav__orders--todo:hover {
  background: var(--gold-500);
}

/* ---------- 登录入口 ---------- */
.nav__login {
  display: inline-flex;
  align-items: center;
  height: 34px;
  padding: 0 var(--sp-4);
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--brand-700);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  transition: all var(--dur-1) var(--ease);
}
.nav__login:hover {
  border-color: var(--brand-500);
  background: var(--brand-50);
}

/* ---------- 用户菜单 ---------- */
.usermenu {
  position: relative;
}
.usermenu__trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  height: 34px;
  padding: 0 var(--sp-3) 0 4px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  color: var(--ink-700);
  transition: all var(--dur-1) var(--ease);
}
.usermenu__trigger:hover {
  border-color: var(--brand-300);
  background: var(--brand-50);
}
.usermenu__avatar {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  background: var(--brand-700);
  color: var(--gold-300);
  font-size: 13px;
  font-weight: 600;
  border-radius: var(--r-sm);
  flex: none;
}
.usermenu__name {
  font-size: var(--fs-xs);
  font-weight: 600;
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.usermenu__role {
  height: 20px;
  padding: 0 7px;
  font-size: 10px;
}
.usermenu__panel {
  position: absolute;
  right: 0;
  top: calc(100% + 8px);
  z-index: var(--z-pop);
  min-width: 172px;
  padding: var(--sp-2);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-md);
  box-shadow: var(--sh-3);
  animation: fadeUp var(--dur-1) var(--ease) both;
}
.usermenu__item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 8px var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--ink-700);
  border-radius: var(--r-sm);
  transition: background var(--dur-1) var(--ease);
}
.usermenu__item:hover {
  background: var(--brand-50);
  color: var(--brand-700);
}
.usermenu__item--danger:hover {
  background: var(--danger-50);
  color: var(--danger);
}

/* ---------- 页脚 ---------- */
.footer {
  margin-top: auto;
  background: var(--brand-800);
  color: var(--brand-100);
  padding: var(--sp-7) 0 var(--sp-5);
}
.footer__inner {
  display: flex;
  gap: var(--sp-8);
  flex-wrap: wrap;
  justify-content: space-between;
}
.footer__brand {
  display: flex;
  gap: var(--sp-4);
  max-width: 380px;
}
.footer__name {
  font-family: var(--font-display);
  font-size: 18px;
  letter-spacing: 0.08em;
  color: #fff;
}
.footer__slogan {
  margin-top: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--brand-300);
  line-height: 1.7;
}
.footer__cols {
  display: flex;
  gap: var(--sp-8);
}
.footer__col {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--brand-300);
}
.footer__title {
  color: #fff;
  font-weight: 600;
  margin-bottom: var(--sp-1);
  letter-spacing: 0.04em;
}
.footer__col a:hover {
  color: var(--gold-300);
}
.footer__bottom {
  margin-top: var(--sp-6);
  padding-top: var(--sp-4);
  border-top: 1px solid rgba(219, 233, 227, 0.14);
  display: flex;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
  font-size: var(--fs-cap);
  color: var(--brand-300);
}
.footer__bottom .muted {
  color: rgba(219, 233, 227, 0.6);
}

@media (max-width: 1080px) {
  /* 顶栏塞不下这么多字了，购物车与我的订单只留图标 + 角标 */
  .nav__cart-label,
  .nav__orders-label {
    display: none;
  }
}

@media (max-width: 720px) {
  /* 顶栏在手机上改两行。
   *
   * 为什么不是"让链接区横向滚动"就够了：390px 视口实测整条 nav 宽 487px
   * （多出 97px → 整页出现横向滚动条）。而 .nav__inner 自身还有 32px×2 的 gap、
   * 右侧操作区自然宽 197px —— 把链接区压到 0 宽也仍然差 11px。所以**光滚动不够**，
   * 必须换行。
   *
   * 也不选择"隐藏导航项"：这个仓库的一级导航有 7 项
   * （首页 / 探索汉中 / 互动地图 / 行程规划 / 知识问答 / AI 助手 / 乡村好物），
   * 手机上删掉它们等于删掉功能。换行则一项不少。
   *
   * 高度由 --nav-h 给出（base.css 的 720px 断点里已同步改成 92px）。
   *
   * ★ 但手机上**不能真的锁死 height**：窄机型（360px）一行放不下 7 项，链接要折成
   *   两行，锁死就会溢出到 Hero 上。所以这里改成"内容驱动 + min-height 兜底"：
   *   height: auto 让它自然长高，min-height: var(--nav-h) 保证不比设计值矮。
   *   为什么敢这么做：移动端 --nav-h 的**唯一真实消费者就是 .nav 自己** ——
   *   Assistant 的 .rail、PoiDetail 的 .dside、Checkout 的 .summary 在 720px 断点里
   *   都已经被置为 position: static（逐个 grep 确认过），不再用 top: calc(--nav-h + …)。
   *   而 Hero 用的是 --hero-h（固定 520px）、且 .nav 是 sticky 在流内的，
   *   顶栏长高只会把下面的内容整体推下去，不会压住谁。 */
  .nav {
    height: auto;
    min-height: var(--nav-h);
  }
  .nav__inner {
    flex-wrap: wrap;
    align-content: center;
    row-gap: var(--sp-2);
    column-gap: var(--sp-4);
  }
  /* 第一行：品牌在左、操作区在右（.nav__actions 的 margin-left:auto 已有） */
  .brand,
  .nav__actions {
    flex: none;
  }
  .nav__actions {
    gap: var(--sp-2);
  }
  /* 第二行：导航整行下沉。flex-basis 100% 是"独占一行"的关键 */
  .nav__links {
    order: 3;
    flex: 1 1 100%;
    margin-left: 0;
    gap: var(--sp-1);
    justify-content: space-between;
    /* 兜底：7 项在极窄机型上仍可能差几像素，让它自己滑，不要撑破页面 */
    min-width: 0;
    overflow-x: auto;
    scrollbar-width: none;
    -ms-overflow-style: none;
  }
  /*
   * ★ 这两行是必须的，少了任意一行链接就会**文字换行**（"首页"竖成"首/页"）。
   *   原因：`.nav__links` 变成 flex 行之后，`.nav__link` 默认 flex-shrink:1 会被压缩，
   *   文字在窄盒子里就断行了 —— 宽度量着"刚好放得下"，看着却是两行乱码。
   *   `flex: none` 让每个链接取自然宽，`white-space: nowrap` 禁止盒内断行。
   *
   * ★ 字号也必须降一档（--fs-sm 14px → --fs-xs 13px）。
   *   7 项在 14px 下自然宽 352px + 6×4px gap = 376px，而视口可用只有 358px
   *   （390 - 两侧 16px）→ 溢出 18px，最后一项"乡村好物"被裁。
   *   降到 13px 后约 327 + 24 = 351px，留 7px 余量。
   *   gap 取 --sp-1 而非 --sp-2：space-between 下 gap 是**最小**间距，
   *   留小一点才有余量，字体渲染略宽时不会直接溢出。
   */
  .nav__link {
    flex: none;
    white-space: nowrap;
    font-size: var(--fs-xs);
  }
  .nav__links::-webkit-scrollbar {
    display: none;
  }
  .brand__sub {
    display: none;
  }
  .footer__cols {
    gap: var(--sp-6);
    flex-wrap: wrap;
  }
}

/*
 * ≤400px（360 这类窄屏安卓机）：一行真的放不下，必须同时收窄"横向"三个量。
 *
 * 量过的事实（360px 视口）：
 *   - 7 个链接在 13px 下自然宽 327px + 6×4px gap = 351px，而可用只有 328px
 *     （360 - 两侧 16px）→ 差 23px。硬撑一行的话最后一项"乡村好物"会被压进
 *     overflow-x: auto 的滚动区 —— **滚动条是隐藏的，用户根本不知道右边还有一项**，
 *     等于悄悄删掉一个入口。
 *   - 更麻烦的是第一行：品牌约 126px + 操作区约 198px + .nav__inner 的 24px gap
 *     = 348px > 328px，于是**操作区也被挤到第二行**，顶栏一度长到 164px
 *     （实测），sticky 顶栏吃掉 1/5 屏。
 *
 * 所以这里三件事一起做：
 *   ① 容器内边距 16 → 12px、行内间距 24 → 8px（给第一行腾出 20px）
 *   ② 链接字号 13 → 12px（--fs-cap）、链接间距 4px
 *   ③ 仍保留 flex-wrap 作为**最后兜底**：比 360 更窄的机器（如 320px）会自然折成
 *      两行而不是溢出 —— 折行不会撑破页面，因为 .nav 在 720px 断点里已是 height: auto。
 *      这就是"参数收紧 + 结构性兜底"两条腿走路，不用为每个机型写死断点。
 */
@media (max-width: 400px) {
  .nav__inner {
    padding-left: var(--sp-3);
    padding-right: var(--sp-3);
    column-gap: var(--sp-2);
  }
  .nav__links {
    flex-wrap: wrap;
    justify-content: space-between;
    column-gap: var(--sp-1);
    row-gap: var(--sp-1);
    overflow-x: visible;
  }
  .nav__link {
    font-size: var(--fs-cap);
  }
  .nav__actions {
    gap: var(--sp-1);
  }
}
</style>
