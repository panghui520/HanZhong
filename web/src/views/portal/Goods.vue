<script setup lang="ts">
/**
 * 乡村好物 · 离境复购（M6）
 *
 * ============================================================
 * 这一页为什么不是"商城首页"
 * ============================================================
 * 本项目的红线之一是「核心不是电商」。所以这一页从标题、筛选维度到
 * 每张卡上的信息，都在回答同一个问题：**这件东西来自哪次体验、哪个村**。
 *
 * 具体做法（都是刻意的取舍，不是没做）：
 *
 *   - **筛选维度是产地，不是品类。** 品类（茶叶 / 粮油 / 腊味）是货架语言，
 *     按品类逛会把产地信息抹平 —— 而"洋县的米"和"镇巴的腊肉"之所以值得买，
 *     正因为它们各自挂在一次具体的乡村体验上。
 *   - **不做店铺、不做商家、不做搜索。** 没有"某某旗舰店"这种概念，
 *     供货方就是那个乡村点本身。
 *   - **每张卡必须显示产地与体验锚点，产地可点回详情。** 让"消费链延伸"
 *     这句话可核对：点进去就能看到那次体验、那个村。
 *
 * 下单流程本身是标准的：挑 → 加购 → 填收货信息 → 提交 → 运营发货。
 * 简化掉的是支付与物流（见 db/V5__m6_order.sql 顶部说明）。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import SceneArt from '@/components/SceneArt.vue'
import SectionHead from '@/components/SectionHead.vue'
import { getProducts } from '@/api/citypack'
import { addToCart } from '@/api/order'
import { ApiError } from '@/api/http'
import { isEmpty, useAsync } from '@/composables/useAsync'
import { useReveal } from '@/composables/useReveal'
import { useCartStore } from '@/stores/cart'
import { useSessionStore } from '@/stores/session'
import { sceneVariant } from '@/utils/scene'
import type { Product } from '@/types'

const router = useRouter()
const session = useSessionStore()
const cart = useCartStore()

const { data, loading, error, reload } = useAsync(getProducts)
const root = ref<HTMLElement | null>(null)

const all = computed<Product[]>(() => data.value ?? [])

/* ============================================================
 * 产地锚点：按挂靠的乡村点分组
 *
 * 分组键用 `poi_id` 而不是村名 —— 村名是展示文案，将来改一个字
 * 分组就会散架。名称只用于显示，且一律取服务端补全的 `poi_name`。
 * ============================================================ */
interface Origin {
  poiId: string
  name: string
  count: number
}

const origins = computed<Origin[]>(() => {
  const map = new Map<string, Origin>()
  for (const p of all.value) {
    const id = p.poi_id
    if (!id) continue
    const hit = map.get(id)
    if (hit) {
      hit.count += 1
    } else {
      map.set(id, { poiId: id, name: p.poi_name || id, count: 1 })
    }
  }
  // 按款数降序、同款数按名称排：让"货最全的产地"排在前面，而不是随机
  return [...map.values()].sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
})

const activeOrigin = ref<string>('')

const list = computed<Product[]>(() =>
  activeOrigin.value ? all.value.filter((p) => p.poi_id === activeOrigin.value) : all.value
)

/* ---------- 加购 ---------- */

/**
 * 正在提交的商品 id 列表（不是单个 id）。
 *
 * 用列表而不是单个 id，是因为单个 id 会带来一个静默的边界问题：
 * 加购 A 的请求还在飞的时候点 B，B 会被直接丢掉 —— 用户看到的是
 * "点了第二件没反应"。按商品分别加锁，A 在飞不影响 B，
 * 而同一件商品连点两下仍然只加一次（后端是数量累加，连点就真的加两次）。
 */
const pendingIds = ref<string[]>([])
/**
 * 正在"提示 + 跳登录页"的过程中。
 *
 * 单独一个标记而不是复用 pendingIds：未登录时按钮文案不该变成「加入中…」，
 * 那会让人以为真的加进去了。这里只是把按钮禁掉，防止这 0.8 秒里被连点。
 */
const leaving = ref(false)
const notice = ref<{ type: 'ok' | 'err'; text: string } | null>(null)
let noticeTimer: number | undefined

function say(type: 'ok' | 'err', text: string) {
  notice.value = { type, text }
  if (noticeTimer !== undefined) window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => (notice.value = null), 3200)
}

const isPending = (id: string) => pendingIds.value.includes(id)

/**
 * 加购。
 *
 * 未登录时**不静默失败**，而是先给一句明确提示、再带去登录页。
 *
 * 提示必须比跳转早 0.8 秒：提示条是这一页的组件状态，页面一跳就没了。
 * 直接 `router.push` 的话用户看到的是"点了按钮，莫名其妙到了登录页"，
 * 完全不知道刚才那一按算不算数。0.8 秒足够读完这一句，
 * 又短到不显得卡顿。
 */
async function add(p: Product) {
  if (!session.isLoggedIn) {
    if (leaving.value) return
    leaving.value = true
    say('err', '请先登录，再把它带回家')
    window.setTimeout(() => {
      void router.push({ path: '/login', query: { redirect: '/goods' } })
    }, 800)
    return
  }
  if (isPending(p.id)) return
  pendingIds.value = [...pendingIds.value, p.id]
  try {
    await addToCart(p.id, 1)
    // 角标数字以服务端为准，不在本地 +1 猜
    await cart.refresh()
    say('ok', `已加入购物车：${p.name}`)
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '加入购物车失败，请重试')
  } finally {
    pendingIds.value = pendingIds.value.filter((x) => x !== p.id)
  }
}

const soldOut = (p: Product) => (p.stock ?? 0) <= 0

useReveal(root, loading, activeOrigin)
</script>

<template>
  <div ref="root" class="goods-page">
    <!-- ============ 1. 页头 ============ -->
    <header class="gh">
      <SceneArt variant="terrace" ratio="auto" class="gh__art" />
      <div class="gh__veil" />
      <div class="container gh__inner">
        <span class="eyebrow eyebrow--light">离境复购 · 乡村好物</span>
        <h1 class="display gh__title">体验过的，可以带走</h1>
        <p class="gh__desc">
          这里没有货架，只有产地。每一款都挂在一处具体的乡村点与一次具体的体验上 ——
          先认下那片茶园、那座橘园，再谈复购。这就是把一次到访变成持续消费链的落点。
        </p>
        <div class="gh__meta">
          <span class="gh__meta-i">
            <b class="num">{{ all.length }}</b> 款在售
          </span>
          <span class="gh__meta-i">
            <b class="num">{{ origins.length }}</b> 处产地
          </span>
          <span class="gh__meta-i gh__meta-i--hint">产地直发 · 运营统一处理</span>
        </div>
      </div>
    </header>

    <!-- ============ 2. 加载 / 错误 ============ -->
    <div v-if="loading" class="container section">
      <div class="grid grid-3">
        <div v-for="i in 6" :key="i" class="skeleton" style="height: 340px; border-radius: 10px" />
      </div>
    </div>

    <div v-else-if="error" class="container section">
      <div class="state-error">
        <p>{{ error }}</p>
        <button class="btn btn-ghost btn-sm" @click="reload">重新加载</button>
      </div>
    </div>

    <template v-else>
      <!-- ============ 3. 产地锚点 ============ -->
      <section class="container section">
        <SectionHead
          eyebrow="按产地逛"
          title="先选一个村子"
          desc="筛选维度是产地而不是品类：品类会把产地信息抹平，而这一页要讲的恰恰是「这件东西来自哪次体验」。"
          size="md"
        />

        <div class="origins">
          <button
            class="origin"
            :class="{ 'origin--on': activeOrigin === '' }"
            @click="activeOrigin = ''"
          >
            <span class="origin__name">全部产地</span>
            <span class="origin__n num">{{ all.length }}</span>
          </button>
          <button
            v-for="o in origins"
            :key="o.poiId"
            class="origin"
            :class="{ 'origin--on': activeOrigin === o.poiId }"
            @click="activeOrigin = activeOrigin === o.poiId ? '' : o.poiId"
          >
            <span class="origin__name">{{ o.name }}</span>
            <span class="origin__n num">{{ o.count }}</span>
          </button>
        </div>
      </section>

      <!-- ============ 4. 商品网格 ============ -->
      <section class="container section-0">
        <div v-if="isEmpty(list)" class="empty">
          <div class="empty__title">这个产地暂时没有在售好物</div>
          <div class="empty__desc">换一处产地看看，或清掉筛选条件</div>
          <button class="btn btn-ghost btn-sm" style="margin-top: 16px" @click="activeOrigin = ''">
            查看全部
          </button>
        </div>

        <div v-else class="grid grid-3">
          <article
            v-for="(p, i) in list"
            :key="p.id"
            class="gcard reveal"
            :style="{ transitionDelay: `${Math.min(i, 6) * 40}ms` }"
          >
            <div class="gcard__art-wrap">
              <!--
                用产品自带的 scene 而不是产地封面图：同一产地的两款茶
                会拿到同一张照片，看着像重复上架。scene 是数据包里
                每款产品各自声明的画面，也更适合离线演示。
              -->
              <SceneArt :variant="sceneVariant(p.scene, 'terrace')" ratio="16 / 10" class="gcard__art" />
              <span class="gcard__cat">{{ p.category }}</span>
              <span v-if="soldOut(p)" class="gcard__out">已售罄</span>
            </div>

            <div class="gcard__body">
              <h3 class="gcard__name">{{ p.name }}</h3>
              <p class="gcard__spec">
                {{ p.spec }}<template v-if="p.origin_village"> · {{ p.origin_village }}</template>
              </p>
              <p class="gcard__story">{{ p.story }}</p>

              <!-- 锚点：这是这一页的核心信息，不折叠、不缩成小字放在角落 -->
              <div class="anchor">
                <router-link
                  v-if="p.poi_id"
                  :to="`/poi/${p.poi_id}`"
                  class="anchor__row anchor__row--link"
                >
                  <span class="anchor__k">产地</span>
                  <span class="anchor__v">{{ p.poi_name || p.poi_id }}</span>
                  <span class="anchor__go">→</span>
                </router-link>
                <router-link
                  v-else
                  to="/explore"
                  class="anchor__row anchor__row--link"
                >
                  <span class="anchor__k">产地</span>
                  <span class="anchor__v">查看乡村体验</span>
                  <span class="anchor__go">→</span>
                </router-link>

                <p v-if="p.experience_name" class="anchor__row">
                  <span class="anchor__k">体验锚点</span>
                  <span class="anchor__v">{{ p.experience_name }}</span>
                </p>
              </div>

              <div class="gcard__foot">
                <span class="num gcard__price">¥{{ p.price }}</span>
                <div class="gcard__act">
                  <span v-if="!soldOut(p)" class="muted cap">余 {{ p.stock }}</span>
                  <button
                    class="btn btn-primary btn-sm"
                    :disabled="soldOut(p) || isPending(p.id) || leaving"
                    @click="add(p)"
                  >
                    {{ soldOut(p) ? '已售罄' : isPending(p.id) ? '加入中…' : '加入购物车' }}
                  </button>
                </div>
              </div>
            </div>
          </article>
        </div>
      </section>

      <!-- ============ 5. 流程说明 ============ -->
      <section class="container section">
        <div class="howto">
          <div class="howto__head">
            <span class="eyebrow">怎么拿到手</span>
            <h2 class="h3 howto__title">四步，没有多余的环节</h2>
          </div>
          <ol class="howto__steps">
            <li class="howto__step">
              <span class="howto__n num">01</span>
              <h3 class="howto__t">挑产地</h3>
              <p class="howto__d">按乡村点筛，看到它在哪、属于哪次体验。</p>
            </li>
            <li class="howto__step">
              <span class="howto__n num">02</span>
              <h3 class="howto__t">加入购物车</h3>
              <p class="howto__d">需要登录。购物车按「来自哪次体验」分组，不是按店铺。</p>
            </li>
            <li class="howto__step">
              <span class="howto__n num">03</span>
              <h3 class="howto__t">填收货信息</h3>
              <p class="howto__d">收货人、手机号、详细地址。地址在订单上做快照，以后改地址不影响历史订单。</p>
            </li>
            <li class="howto__step">
              <span class="howto__n num">04</span>
              <h3 class="howto__t">等运营发货</h3>
              <p class="howto__d">订单进入「待发货」，运营在管理端统一处理，发货后状态变「已发货」。</p>
            </li>
          </ol>
          <p class="howto__note">
            本演示系统不接入在线支付与物流通道 —— 状态只有「待发货 / 已发货」两态，
            不摆一个点不动的支付按钮。
          </p>
        </div>
      </section>
    </template>

    <!-- 操作反馈 -->
    <transition name="toast">
      <div v-if="notice" class="toast" :class="`toast--${notice.type}`">
        <router-link v-if="notice.type === 'ok'" to="/cart" class="toast__link">
          {{ notice.text }} · 去购物车 →
        </router-link>
        <template v-else>{{ notice.text }}</template>
      </div>
    </transition>
  </div>
</template>

<style scoped>
/* ============ 1. 页头 ============ */
.gh {
  position: relative;
  overflow: hidden;
  background: var(--brand-900);
  min-height: min(46vh, 400px);
  display: flex;
  align-items: center;
  padding: var(--sp-9) 0 var(--sp-8);
}
.gh__art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
  opacity: 0.55;
}
.gh__veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    100deg,
    rgba(11, 33, 25, 0.92) 0%,
    rgba(11, 33, 25, 0.78) 50%,
    rgba(11, 33, 25, 0.52) 100%
  );
}
.gh__inner {
  position: relative;
  max-width: 860px;
}
.gh__title {
  margin-top: var(--sp-4);
  color: #fff;
  font-size: var(--fs-mega);
  line-height: 1.18;
}
.gh__desc {
  margin-top: var(--sp-5);
  color: rgba(219, 233, 227, 0.86);
  max-width: 44em;
  font-size: var(--fs-hero-sub);
  line-height: 1.85;
}
.gh__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--sp-7);
  margin-top: var(--sp-7);
  padding-top: var(--sp-5);
  border-top: 1px solid rgba(219, 233, 227, 0.18);
  font-size: var(--fs-sm);
  color: rgba(255, 255, 255, 0.72);
}
.gh__meta-i b {
  font-size: 28px;
  font-weight: 600;
  color: var(--gold-300);
  margin-right: 6px;
}
.gh__meta-i--hint {
  color: rgba(255, 255, 255, 0.5);
}

/* ============ 3. 产地锚点 ============ */
.origins {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
}
.origin {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 9px var(--sp-4);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  cursor: pointer;
  transition: all var(--dur-1) var(--ease);
}
.origin:hover {
  border-color: var(--brand-300);
  background: var(--brand-50);
}
.origin--on {
  border-color: var(--brand-600);
  background: var(--brand-50);
  box-shadow: var(--sh-1);
}
.origin__name {
  font-size: var(--fs-sm);
  color: var(--ink-700);
}
.origin--on .origin__name {
  color: var(--brand-800);
  font-weight: 600;
}
.origin__n {
  font-size: var(--fs-cap);
  color: var(--warm-500);
  padding: 1px 7px;
  background: var(--paper-2);
  border-radius: var(--r-pill);
}
.origin--on .origin__n {
  background: var(--brand-100);
  color: var(--brand-700);
}

/* ============ 4. 商品卡 ============ */
.gcard {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
  transition: box-shadow var(--dur-2) var(--ease), transform var(--dur-2) var(--ease),
    border-color var(--dur-2) var(--ease);
}
.gcard:hover {
  box-shadow: var(--sh-3);
  transform: translateY(-4px);
  border-color: var(--line);
}
.gcard__art-wrap {
  position: relative;
  overflow: hidden;
}
.gcard__art {
  border-radius: 0;
  transition: transform 900ms var(--ease);
}
.gcard:hover .gcard__art {
  transform: scale(1.04);
}
.gcard__cat {
  position: absolute;
  left: var(--sp-4);
  top: var(--sp-4);
  padding: 4px 10px;
  font-size: var(--fs-cap);
  font-weight: 600;
  color: #fff;
  background: rgba(11, 33, 25, 0.62);
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: var(--r-sm);
  backdrop-filter: blur(3px);
}
.gcard__out {
  position: absolute;
  right: var(--sp-4);
  top: var(--sp-4);
  padding: 4px 10px;
  font-size: var(--fs-cap);
  font-weight: 600;
  color: #fdeeea;
  background: rgba(168, 64, 43, 0.82);
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: var(--r-sm);
}
.gcard__body {
  padding: var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  flex: 1;
}
.gcard__name {
  font-family: var(--font-display);
  font-size: 20px;
  line-height: 1.4;
  color: var(--ink-900);
}
.gcard__spec {
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.gcard__story {
  font-size: var(--fs-sm);
  line-height: 1.75;
  color: var(--ink-500);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 锚点块：用左侧竖线做视觉标记，与"商品参数"区分开 */
.anchor {
  margin-top: auto;
  padding: var(--sp-3) 0 0 var(--sp-4);
  border-left: 2px solid var(--gold-500);
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.anchor__row {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  font-size: var(--fs-xs);
  line-height: 1.6;
}
.anchor__k {
  flex: none;
  font-size: var(--fs-cap);
  color: var(--warm-500);
  letter-spacing: 0.06em;
}
.anchor__v {
  color: var(--brand-700);
  font-weight: 500;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.anchor__go {
  color: var(--brand-400);
  transition: transform var(--dur-1) var(--ease);
}
.anchor__row--link:hover .anchor__v {
  color: var(--brand-500);
  text-decoration: underline;
}
.anchor__row--link:hover .anchor__go {
  transform: translateX(3px);
}

.gcard__foot {
  margin-top: var(--sp-2);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--line-soft);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.gcard__price {
  font-size: 22px;
  font-weight: 700;
  color: var(--gold-600);
}
.gcard__act {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}

/* ============ 5. 流程说明 ============ */
.howto {
  padding: var(--sp-7) var(--sp-7) var(--sp-6);
  background: var(--paper-2);
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
}
.howto__head {
  max-width: 620px;
}
.howto__title {
  margin-top: var(--sp-3);
  color: var(--ink-900);
}
.howto__steps {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--sp-5);
  margin-top: var(--sp-6);
  list-style: none;
  padding: 0;
}
.howto__step {
  padding-top: var(--sp-4);
  border-top: 1px solid var(--line);
}
.howto__n {
  font-size: var(--fs-cap);
  font-weight: 700;
  letter-spacing: 0.1em;
  color: var(--gold-600);
}
.howto__t {
  margin-top: var(--sp-2);
  font-family: var(--font-display);
  font-size: 17px;
  color: var(--ink-900);
}
.howto__d {
  margin-top: var(--sp-2);
  font-size: var(--fs-xs);
  line-height: 1.75;
  color: var(--ink-500);
}
.howto__note {
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
  border-top: 1px dashed var(--line);
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--warm-500);
}

.state-error {
  padding: var(--sp-6);
  text-align: center;
  border: 1px dashed var(--line);
  border-radius: var(--r-lg);
  color: var(--danger);
  font-size: var(--fs-sm);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-3);
}

/* ============ 反馈条 ============ */
.toast {
  position: fixed;
  left: 50%;
  bottom: var(--sp-7);
  transform: translateX(-50%);
  z-index: var(--z-pop);
  max-width: min(92vw, 520px);
  padding: 12px var(--sp-5);
  font-size: var(--fs-sm);
  border-radius: var(--r-md);
  box-shadow: var(--sh-3);
}
.toast--ok {
  color: var(--brand-800);
  background: #fff;
  border: 1px solid var(--brand-100);
}
.toast--err {
  color: var(--danger);
  background: var(--danger-50);
  border: 1px solid rgba(168, 64, 43, 0.28);
}
.toast__link {
  color: inherit;
  font-weight: 600;
}
.toast-enter-active,
.toast-leave-active {
  transition: opacity var(--dur-2) var(--ease), transform var(--dur-2) var(--ease);
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translate(-50%, 8px);
}

/* ============ 响应式 ============ */
@media (max-width: 1080px) {
  .howto__steps {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 720px) {
  .gh {
    min-height: auto;
    padding: var(--sp-8) 0 var(--sp-7);
  }
  .gh__meta {
    gap: var(--sp-4) var(--sp-6);
  }
  .howto {
    padding: var(--sp-5);
  }
  .howto__steps {
    grid-template-columns: 1fr;
  }
}
</style>
