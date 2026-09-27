<script setup lang="ts">
/**
 * 我的足迹（M6 到访消费链）
 *
 * ============================================================
 * 这一页回答的是"我去过哪儿"，而它同时是**复购推荐的输入**
 * ============================================================
 * 在此之前，用户买过一次东西之后系统对他一无所知：推荐只能按商品类目，
 * 那就退化成普通电商推荐了。足迹落库之后，"你体验过什么"终于是一个
 * 查得出来的事实 —— 这一页就是它的用户可见形态。
 *
 * 所以这一页的排序是**时间倒序**，与推荐真正取用的顺序一致。
 * 页面上看到的顺序和推荐拿到的顺序不一样时，用户没法理解推荐为什么推这个。
 *
 * ============================================================
 * 三条边界（都与"不做电商"的红线有关）
 * ============================================================
 * 1. **不能在这里下单**。足迹是"到访记忆"，不是购物车。要买东西去
 *    「乡村好物」—— 那是唯一的下单入口，农产品在那里挂着体验与产地。
 * 2. **不能删除足迹**。足迹是发生过的事实，而且订单的渠道归类
 *    （TRIP / REPURCHASE）依赖它。允许删足迹等于允许改写历史订单的归类。
 *    演示前要清数据走 `db/reset-runtime-data.sql`，不在这里开口子。
 * 3. **中文名不用前端映射**。`source_label` 由服务端给 ——
 *    否则界面上会冒出 `DIVERSION` 这种给机器看的字符串，
 *    而这一列恰恰是"分流引导"唯一的展示位。
 *
 * ============================================================
 * 未登录时
 * ============================================================
 * 路由上挂了 `requiresAuth`（与购物车/订单一致），正常从导航点进来
 * 会先被守卫送去登录页。页面里那个"登录后查看"的空态是**兜底** ——
 * 命中它的情况是"已经在这个页面上，然后退出了登录"。
 * 保留它而不是让页面空着，是因为那一瞬间不重画的话，屏幕上会留着
 * 上一个账号的足迹 —— 那是别人的数据。与 `Orders.vue` 同一处理。
 */
import { computed, onMounted, ref } from 'vue'
import { ApiError } from '@/api/http'
import { listMyCheckins } from '@/api/trips'
import { useSessionStore } from '@/stores/session'
import { when } from '@/utils/format'
import type { TripCheckin } from '@/types'

const session = useSessionStore()

const rows = ref<TripCheckin[]>([])
const loading = ref(false)
const error = ref('')

/** 按"到访过的点"归组，让用户看出"我去了几个地方、各去过几次" */
const byPoi = computed(() => {
  const map = new Map<string, { name: string; count: number; last: string }>()
  for (const r of rows.value) {
    const key = r.poi_id || r.experience_id || String(r.id)
    const name = r.poi_name || r.experience_name || '未命名'
    const hit = map.get(key)
    if (hit) {
      hit.count += 1
      if (r.checkin_at > hit.last) hit.last = r.checkin_at
    } else {
      map.set(key, { name, count: 1, last: r.checkin_at })
    }
  }
  return [...map.entries()].map(([id, v]) => ({ id, ...v })).sort((a, b) => (a.last < b.last ? 1 : -1))
})

async function load() {
  if (!session.isLoggedIn) return
  loading.value = true
  error.value = ''
  try {
    rows.value = await listMyCheckins()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '足迹加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="container section">
    <div v-if="!session.isLoggedIn" class="empty empty--tall">
      <div class="empty__title">登录后查看我的足迹</div>
      <div class="empty__desc">
        足迹挂在账号上，换台设备登录也能看到 —— 它也决定「乡村好物」会先给你看什么。
      </div>
      <router-link
        :to="{ path: '/login', query: { redirect: '/footprints' } }"
        class="btn btn-gold btn-sm"
        style="margin-top: 16px"
      >
        去登录
      </router-link>
    </div>

    <template v-else>
      <div class="fhead">
        <h1 class="h1">我的足迹</h1>
        <p class="fhead__sub">
          到过的地方会留在这里。它不只是记录 —— 「乡村好物」会先给你看
          <strong>你体验过的</strong>，而不是"你可能想买的"。
        </p>
      </div>

      <div v-if="loading" class="state-loading">正在加载足迹…</div>
      <div v-else-if="error" class="state-error">
        {{ error }}
        <button class="btn btn-ghost btn-sm" style="margin-left: 12px" @click="load">重试</button>
      </div>

      <div v-else-if="!rows.length" class="empty empty--tall">
        <div class="empty__title">还没有足迹</div>
        <div class="empty__desc">
          去「探索」找一个乡村点，进详情页点一下「我到过这里」——足迹就从那里开始。
        </div>
        <router-link to="/explore" class="btn btn-ghost btn-sm" style="margin-top: 16px">
          去探索
        </router-link>
      </div>

      <template v-else>
        <!-- 汇总：去了几个地方。数字来自服务端返回的行，不在前端猜 -->
        <div class="fsum">
          <div class="fsum__item">
            <span class="num fsum__num">{{ rows.length }}</span>
            <span class="fsum__label">次到访</span>
          </div>
          <span class="fsum__sep" />
          <div class="fsum__item">
            <span class="num fsum__num">{{ byPoi.length }}</span>
            <span class="fsum__label">个地方</span>
          </div>
        </div>

        <ul class="flist">
          <li v-for="r in rows" :key="r.id" class="fitem">
            <div class="fitem__main">
              <router-link
                v-if="r.poi_id"
                :to="`/poi/${r.poi_id}`"
                class="fitem__name"
              >
                {{ r.poi_name || r.poi_id }}
              </router-link>
              <span v-else class="fitem__name">{{ r.poi_name || '—' }}</span>

              <span v-if="r.experience_name" class="fitem__exp">
                {{ r.experience_name }}
              </span>
            </div>
            <div class="fitem__meta">
              <span class="fitem__src" :class="`fitem__src--${r.source.toLowerCase()}`">
                {{ r.source_label }}
              </span>
              <span class="fitem__time">{{ when(r.checkin_at) }}</span>
            </div>
            <p v-if="r.note" class="fitem__note">{{ r.note }}</p>
          </li>
        </ul>
      </template>
    </template>
  </div>
</template>

<style scoped>
.fhead {
  margin-bottom: 28px;
}
.fhead__sub {
  margin: 10px 0 0;
  max-width: 46em;
  color: var(--ink-2, #5b6470);
  line-height: 1.75;
}
.fsum {
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 18px 24px;
  margin-bottom: 22px;
  border-radius: 14px;
  background: var(--surface-2, #f7f8fa);
}
.fsum__item {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.fsum__num {
  font-size: 26px;
  font-weight: 600;
}
.fsum__label {
  color: var(--ink-2, #5b6470);
  font-size: 14px;
}
.fsum__sep {
  width: 1px;
  height: 22px;
  background: var(--line, #e3e6ea);
}
.flist {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 12px;
}
.fitem {
  padding: 16px 20px;
  border: 1px solid var(--line, #e3e6ea);
  border-radius: 12px;
  background: var(--surface, #fff);
}
.fitem__main {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}
.fitem__name {
  font-size: 16px;
  font-weight: 600;
  color: inherit;
  text-decoration: none;
}
a.fitem__name:hover {
  text-decoration: underline;
}
.fitem__exp {
  font-size: 13px;
  color: var(--ink-2, #5b6470);
}
.fitem__meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  font-size: 13px;
  color: var(--ink-2, #5b6470);
}
.fitem__src {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--surface-2, #f0f2f5);
}
/* 分流到访用金色：它是"我们把他引导过来"的证据，与用户自己打卡区分开 */
.fitem__src--diversion {
  background: rgba(198, 154, 63, 0.14);
  color: #8a6a1f;
}
.fitem__note {
  margin: 10px 0 0;
  font-size: 14px;
  color: var(--ink-2, #5b6470);
  line-height: 1.7;
}
.state-loading,
.state-error {
  padding: 40px 0;
  text-align: center;
  color: var(--ink-2, #5b6470);
}
</style>
