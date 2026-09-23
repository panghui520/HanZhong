<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

const scrolled = ref(false)
function onScroll() {
  scrolled.value = window.scrollY > 24
}
onMounted(() => {
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
})
onUnmounted(() => window.removeEventListener('scroll', onScroll))

const navs = [
  { label: '首页', to: '/' },
  { label: '探索汉中', to: '/explore' },
]
</script>

<template>
  <div class="portal">
    <header class="nav" :class="{ 'nav--solid': scrolled }">
      <div class="container nav__inner">
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
          <router-link to="/admin/dashboard" class="btn btn-ghost btn-sm">
            管理驾驶舱
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
            <router-link to="/admin/dashboard">管理驾驶舱</router-link>
          </div>
          <div class="footer__col">
            <div class="footer__title">数据来源</div>
            <span>汉中市文化和旅游局公开资料</span>
            <span>客流 / 订单为规则仿真数据</span>
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

@media (max-width: 720px) {
  .nav__links {
    gap: var(--sp-4);
    margin-left: 0;
  }
  .brand__sub {
    display: none;
  }
  .footer__cols {
    gap: var(--sp-6);
  }
}
</style>
