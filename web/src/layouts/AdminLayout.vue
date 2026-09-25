<script setup lang="ts">
import { ref } from 'vue'

const menus = [
  { key: 'dashboard', label: '管理驾驶舱', to: '/admin/dashboard', icon: '◎' },
  { key: 'orders', label: '订单处理', to: '/admin/orders', icon: '⇄' },
  { key: 'media', label: '图片管理', to: '/admin/media', icon: '▤' },
]

const collapsed = ref(false)
</script>

<template>
  <div class="admin theme-admin">
    <aside class="side" :class="{ 'side--mini': collapsed }">
      <div class="side__brand">
        <span class="side__seal">汉</span>
        <span v-show="!collapsed" class="side__name">
          汉游智脑
          <small>文旅运营中枢</small>
        </span>
      </div>

      <nav class="side__nav">
        <router-link
          v-for="m in menus"
          :key="m.key"
          :to="m.to"
          class="side__item"
          :title="m.label"
        >
          <span class="side__icon">{{ m.icon }}</span>
          <span v-show="!collapsed" class="side__label">{{ m.label }}</span>
        </router-link>
      </nav>

      <button class="side__toggle" @click="collapsed = !collapsed">
        {{ collapsed ? '»' : '«' }}
      </button>
    </aside>

    <div class="admin__main">
      <header class="topbar">
        <div class="topbar__left">
          <span class="topbar__city">汉中市</span>
          <span class="topbar__sep">/</span>
          <span class="topbar__scope">全市文旅资源与乡村业态</span>
        </div>
        <div class="topbar__right">
          <span class="badge-sim">仿真数据</span>
          <router-link to="/" class="btn btn-ghost btn-sm topbar__back">返回游客端</router-link>
        </div>
      </header>

      <main class="admin__content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin {
  display: flex;
  min-height: 100vh;
  background: var(--bg-grad);
  background-attachment: fixed;
  color: var(--text);
}

/* ---------- 侧栏 ---------- */
.side {
  width: 232px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: rgba(9, 26, 20, 0.72);
  border-right: 1px solid var(--line);
  padding: var(--sp-5) var(--sp-4);
  position: sticky;
  top: 0;
  height: 100vh;
  transition: width var(--dur-2) var(--ease);
}
.side--mini {
  width: 68px;
  padding-left: var(--sp-2);
  padding-right: var(--sp-2);
}
.side__brand {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 0 var(--sp-2) var(--sp-5);
  border-bottom: 1px solid var(--line);
}
.side__seal {
  width: 34px;
  height: 34px;
  flex: none;
  display: grid;
  place-items: center;
  background: var(--gold-500);
  color: #2b1e07;
  font-family: var(--font-display);
  font-weight: 700;
  font-size: 19px;
  border-radius: var(--r-sm);
}
.side__name {
  display: flex;
  flex-direction: column;
  font-family: var(--font-display);
  font-size: 16px;
  letter-spacing: 0.06em;
  color: #fff;
  line-height: 1.25;
}
.side__name small {
  font-family: var(--font-sans);
  font-size: 11px;
  letter-spacing: 0.12em;
  color: var(--text-3);
}

.side__nav {
  margin-top: var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}
.side__item {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-3);
  border-radius: var(--r-md);
  font-size: var(--fs-sm);
  color: var(--text-2);
  border-left: 2px solid transparent;
  transition: all var(--dur-1) var(--ease);
}
.side__item:hover {
  background: rgba(146, 178, 165, 0.1);
  color: #fff;
}
.side__item.router-link-active {
  background: rgba(46, 123, 196, 0.14);
  border-left-color: var(--tech-500);
  color: #fff;
  font-weight: 600;
}
.side__icon {
  width: 18px;
  text-align: center;
  flex: none;
  font-size: 15px;
}
.side__toggle {
  margin-top: auto;
  align-self: center;
  width: 34px;
  height: 34px;
  border-radius: var(--r-md);
  color: var(--text-3);
  border: 1px solid var(--line);
  transition: all var(--dur-1) var(--ease);
}
.side__toggle:hover {
  color: #fff;
  border-color: var(--line-strong);
}

/* ---------- 主区 ---------- */
.admin__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.topbar {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--sp-6);
  border-bottom: 1px solid var(--line);
  background: rgba(9, 26, 20, 0.5);
  backdrop-filter: blur(8px);
  position: sticky;
  top: 0;
  z-index: var(--z-nav);
}
.topbar__left {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-sm);
}
.topbar__city {
  font-family: var(--font-display);
  font-size: 17px;
  color: #fff;
  letter-spacing: 0.06em;
}
.topbar__sep {
  color: var(--text-3);
}
.topbar__scope {
  color: var(--text-2);
}
.topbar__right {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.badge-sim {
  height: 24px;
  padding: 0 10px;
  display: inline-flex;
  align-items: center;
  border-radius: var(--r-sm);
  font-size: var(--fs-cap);
  color: var(--gold-300);
  background: rgba(192, 154, 78, 0.14);
  border: 1px solid rgba(192, 154, 78, 0.32);
}
.topbar__back {
  color: var(--text-2);
  border-color: var(--line-strong);
}
.topbar__back:hover {
  color: #fff;
  border-color: #fff;
  background: rgba(255, 255, 255, 0.06);
}

.admin__content {
  padding: var(--sp-6);
}

@media (max-width: 720px) {
  .side {
    display: none;
  }
  .topbar {
    padding: 0 var(--sp-4);
  }
  .admin__content {
    padding: var(--sp-4);
  }
}
</style>
