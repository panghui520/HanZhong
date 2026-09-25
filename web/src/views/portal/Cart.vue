<script setup lang="ts">
/**
 * 购物车（M6）
 *
 * ============================================================
 * 分组维度：按「来自哪次体验」，不是按店铺
 * ============================================================
 * 普通电商的购物车按店铺分组，因为结算要拆单、运费要按商家算。
 * 这里没有商家 —— 供货方就是乡村点本身，所以分组按**体验锚点**：
 * 一眼能看出"这车东西分别来自哪几次乡村体验"。这是本项目
 * 「消费链离境延伸」在购物车这一屏上的可见形态。
 *
 * 几个刻意的处理：
 *
 * 1. **失效行不静默删掉。** 商品下架后后端返回 `available: false`。
 *    直接隐藏会让用户以为"我明明加过"，然后反复加购反复失败。
 *    这里显式显示成灰色一行 + 移除按钮。
 *
 * 2. **数量加减直接落库**，不做本地暂存再批量提交。
 *    本地暂存看起来"响应快"，但一旦提交失败，界面上的数量和
 *    服务端就对不上了，而用户已经离开这一页 —— 那种不一致没人能发现。
 *
 * 3. **合计只用于展示。** 下单时前端不传金额，服务端按库里价格重算。
 *    所以这里数字差一分钱也不影响成交金额。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SceneArt from '@/components/SceneArt.vue'
import { clearCart, getCart, removeCartItem, updateCartQuantity } from '@/api/order'
import { ApiError } from '@/api/http'
import { useCartStore } from '@/stores/cart'
import { useSessionStore } from '@/stores/session'
import { sceneVariant } from '@/utils/scene'
import type { CartItem } from '@/types'

const router = useRouter()
const session = useSessionStore()
const cart = useCartStore()

const items = ref<CartItem[]>([])
const loading = ref(true)
const error = ref('')
/** 正在写的那一行（改数量/删除）。避免连点产生并发写 */
const busyId = ref<number | null>(null)
const notice = ref('')

async function load() {
  if (!session.isLoggedIn) {
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    items.value = await getCart()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '购物车加载失败'
  } finally {
    loading.value = false
  }
}
onMounted(load)

/* ---------- 分组 ---------- */

interface Group {
  /** 分组键：体验 id，没有体验锚点的归到 "ORIGIN:<poiId>" */
  key: string
  title: string
  poiId?: string
  poiName?: string
  rows: CartItem[]
}

const groups = computed<Group[]>(() => {
  const map = new Map<string, Group>()
  for (const it of items.value) {
    const key = it.experience_id
      ? `EXP:${it.experience_id}`
      : it.poi_id
        ? `ORIGIN:${it.poi_id}`
        : 'UNKNOWN'
    let g = map.get(key)
    if (!g) {
      g = {
        key,
        title: it.experience_name || (it.poi_name ? `${it.poi_name} · 产地直供` : '未标注来源'),
        poiId: it.poi_id,
        poiName: it.poi_name,
        rows: [],
      }
      map.set(key, g)
    }
    g.rows.push(it)
  }
  return [...map.values()]
})

/** 只统计可用行。失效行不该参与合计，否则用户会看到一个买了也付不出的数 */
const usable = computed(() => items.value.filter((i) => i.available))
const totalQty = computed(() => usable.value.reduce((s, i) => s + i.quantity, 0))
const totalAmount = computed(() =>
  usable.value.reduce((s, i) => s + (i.subtotal ?? 0), 0)
)
/** 有没有失效行 —— 有就禁用去结算，并提示先移除 */
const hasInvalid = computed(() => items.value.some((i) => !i.available))

function flash(text: string) {
  notice.value = text
  window.setTimeout(() => {
    if (notice.value === text) notice.value = ''
  }, 2600)
}

/* ---------- 写操作 ---------- */

async function changeQty(it: CartItem, delta: number) {
  if (busyId.value !== null) return
  const next = it.quantity + delta
  if (next < 1) {
    await remove(it)
    return
  }
  const stock = it.stock ?? 0
  if (next > stock) {
    flash(`「${it.name}」库存只剩 ${stock} 件`)
    return
  }
  busyId.value = it.id
  try {
    const saved = await updateCartQuantity(it.id, next)
    // 用服务端返回的整行覆盖本地，避免本地算的 subtotal 和服务端漂移
    const idx = items.value.findIndex((x) => x.id === it.id)
    if (idx >= 0) items.value[idx] = saved
    await cart.refresh()
  } catch (e) {
    flash(e instanceof ApiError ? e.message : '修改数量失败')
  } finally {
    busyId.value = null
  }
}

async function remove(it: CartItem) {
  if (busyId.value !== null) return
  busyId.value = it.id
  try {
    await removeCartItem(it.id)
    items.value = items.value.filter((x) => x.id !== it.id)
    await cart.refresh()
  } catch (e) {
    flash(e instanceof ApiError ? e.message : '移除失败')
  } finally {
    busyId.value = null
  }
}

async function clearAll() {
  if (busyId.value !== null) return
  busyId.value = -1
  try {
    await clearCart()
    items.value = []
    await cart.refresh()
  } catch (e) {
    flash(e instanceof ApiError ? e.message : '清空失败')
  } finally {
    busyId.value = null
  }
}

function checkout() {
  if (!usable.value.length) {
    flash('购物车还是空的')
    return
  }
  if (hasInvalid.value) {
    flash('请先移除已下架的商品')
    return
  }
  void router.push('/checkout')
}
</script>

<template>
  <div class="cart-page">
    <div class="container section">
      <!-- 未登录：直接给登录入口，不发请求 -->
      <div v-if="!session.isLoggedIn" class="empty empty--tall">
        <div class="empty__title">登录后才能看到购物车</div>
        <div class="empty__desc">购物车跟着账号走，换台设备登录也还在。</div>
        <router-link
          :to="{ path: '/login', query: { redirect: '/cart' } }"
          class="btn btn-primary"
          style="margin-top: 20px"
        >
          去登录
        </router-link>
      </div>

      <template v-else>
        <!-- 页头 -->
        <header class="chead">
          <div>
            <span class="eyebrow">离境复购</span>
            <h1 class="h1 chead__title">购物车</h1>
            <p class="chead__desc">
              按「来自哪次体验」分组。这里没有店铺，只有那几次你到访过的乡村。
            </p>
          </div>
          <router-link to="/goods" class="btn btn-ghost btn-sm">继续挑好物</router-link>
        </header>

        <div v-if="loading" class="stack-4">
          <div class="skeleton" style="height: 120px; border-radius: 10px" />
          <div class="skeleton" style="height: 120px; border-radius: 10px" />
        </div>

        <div v-else-if="error" class="state-error">
          <p>{{ error }}</p>
          <button class="btn btn-ghost btn-sm" @click="load">重新加载</button>
        </div>

        <div v-else-if="!items.length" class="empty empty--tall">
          <div class="empty__title">购物车是空的</div>
          <div class="empty__desc">去「乡村好物」挑几样，从产地直接寄回家。</div>
          <router-link to="/goods" class="btn btn-primary" style="margin-top: 20px">
            去挑好物
          </router-link>
        </div>

        <template v-else>
          <!-- 失效提示：先于列表出现，让用户知道为什么结算按钮是灰的 -->
          <div v-if="hasInvalid" class="warnbar">
            购物车里有已下架的商品，请先移除后再结算。
          </div>

          <div class="cgroups">
            <section v-for="g in groups" :key="g.key" class="cgroup">
              <header class="cgroup__head">
                <div class="cgroup__left">
                  <span class="cgroup__k">体验锚点</span>
                  <span class="cgroup__title">{{ g.title }}</span>
                </div>
                <router-link v-if="g.poiId" :to="`/poi/${g.poiId}`" class="cgroup__poi">
                  产地 · {{ g.poiName || g.poiId }} →
                </router-link>
                <span v-else class="cgroup__poi cgroup__poi--muted">未标注产地</span>
              </header>

              <ul class="crows">
                <li
                  v-for="it in g.rows"
                  :key="it.id"
                  class="crow"
                  :class="{ 'crow--gone': !it.available }"
                >
                  <div class="crow__art">
                    <SceneArt :variant="sceneVariant(it.scene, 'terrace')" ratio="1 / 1" />
                  </div>

                  <div class="crow__main">
                    <template v-if="it.available">
                      <h3 class="crow__name">{{ it.name }}</h3>
                      <p class="crow__spec">{{ it.spec }}</p>
                      <p class="crow__stock muted cap">库存 {{ it.stock }}</p>
                    </template>
                    <template v-else>
                      <h3 class="crow__name crow__name--gone">商品已下架</h3>
                      <p class="crow__spec">
                        编号 {{ it.product_id }} 已不在当前在售清单里，请移除后重新挑选。
                      </p>
                    </template>
                  </div>

                  <div v-if="it.available" class="crow__price">
                    <span class="num crow__unit">¥{{ it.unit_price }}</span>
                    <span class="muted cap">单价</span>
                  </div>

                  <div v-if="it.available" class="qty">
                    <button
                      class="qty__btn"
                      :disabled="busyId !== null"
                      aria-label="减少"
                      @click="changeQty(it, -1)"
                    >
                      −
                    </button>
                    <span class="qty__n num">{{ it.quantity }}</span>
                    <button
                      class="qty__btn"
                      :disabled="busyId !== null || it.quantity >= (it.stock ?? 0)"
                      aria-label="增加"
                      @click="changeQty(it, 1)"
                    >
                      +
                    </button>
                  </div>

                  <div class="crow__sum">
                    <span v-if="it.available" class="num crow__sub">¥{{ it.subtotal }}</span>
                    <button class="crow__del" :disabled="busyId !== null" @click="remove(it)">
                      移除
                    </button>
                  </div>
                </li>
              </ul>
            </section>
          </div>

          <!-- 结算条 -->
          <div class="paybar">
            <button class="btn btn-ghost btn-sm paybar__clear" :disabled="busyId !== null" @click="clearAll">
              清空购物车
            </button>
            <div class="paybar__right">
              <div class="paybar__sum">
                <span class="muted small">共 {{ totalQty }} 件，合计</span>
                <span class="num paybar__amount">¥{{ totalAmount.toFixed(2) }}</span>
              </div>
              <button class="btn btn-primary btn-lg" :disabled="hasInvalid || !usable.length" @click="checkout">
                去结算
              </button>
            </div>
          </div>
          <p class="paybar__note">
            合计仅用于展示，实际成交金额由服务端按库里价格重新计算。
          </p>
        </template>
      </template>
    </div>

    <transition name="toast">
      <div v-if="notice" class="toast">{{ notice }}</div>
    </transition>
  </div>
</template>

<style scoped>
.chead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-6);
  padding-bottom: var(--sp-5);
  border-bottom: 1px solid var(--line-soft);
  margin-bottom: var(--sp-6);
}
.chead__title {
  margin-top: var(--sp-3);
  color: var(--ink-900);
}
.chead__desc {
  margin-top: var(--sp-3);
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: var(--ink-500);
  max-width: 52em;
}

.empty--tall {
  padding: var(--sp-9) var(--sp-5);
}

.warnbar {
  padding: 12px var(--sp-4);
  margin-bottom: var(--sp-5);
  font-size: var(--fs-sm);
  color: var(--warn);
  background: var(--warn-50);
  border: 1px solid rgba(168, 121, 29, 0.28);
  border-radius: var(--r-md);
}

/* ---------- 分组 ---------- */
.cgroups {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
}
.cgroup {
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  overflow: hidden;
}
.cgroup__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: var(--sp-4) var(--sp-5);
  background: var(--paper-2);
  border-bottom: 1px solid var(--line-soft);
  flex-wrap: wrap;
}
.cgroup__left {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  min-width: 0;
}
.cgroup__k {
  flex: none;
  font-size: var(--fs-cap);
  letter-spacing: 0.1em;
  color: var(--warm-500);
  padding: 2px 8px;
  background: var(--gold-50);
  border: 1px solid var(--gold-300);
  border-radius: var(--r-sm);
}
.cgroup__title {
  font-family: var(--font-display);
  font-size: 17px;
  color: var(--ink-900);
}
.cgroup__poi {
  font-size: var(--fs-xs);
  color: var(--brand-600);
  transition: color var(--dur-1) var(--ease);
}
.cgroup__poi:hover {
  color: var(--brand-800);
  text-decoration: underline;
}
.cgroup__poi--muted {
  color: var(--warm-400);
}

.crows {
  list-style: none;
  margin: 0;
  padding: 0;
}
.crow {
  display: grid;
  grid-template-columns: 84px 1fr 110px auto 130px;
  align-items: center;
  gap: var(--sp-5);
  padding: var(--sp-4) var(--sp-5);
}
.crow + .crow {
  border-top: 1px solid var(--line-soft);
}
.crow--gone {
  background: var(--paper-2);
}
.crow__art {
  border-radius: var(--r-md);
  overflow: hidden;
}
.crow--gone .crow__art {
  filter: grayscale(1);
  opacity: 0.5;
}
.crow__main {
  min-width: 0;
}
.crow__name {
  font-family: var(--font-display);
  font-size: 17px;
  color: var(--ink-900);
}
.crow__name--gone {
  color: var(--ink-500);
}
.crow__spec {
  margin-top: 4px;
  font-size: var(--fs-xs);
  color: var(--ink-500);
  line-height: 1.6;
}
.crow__stock {
  margin-top: 4px;
}
.crow__price {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}
.crow__unit {
  font-size: var(--fs-sm);
  color: var(--ink-700);
}
.qty {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
  background: #fff;
}
.qty__btn {
  width: 32px;
  height: 32px;
  font-size: 16px;
  color: var(--ink-600);
  transition: background var(--dur-1) var(--ease);
}
.qty__btn:hover:not(:disabled) {
  background: var(--brand-50);
  color: var(--brand-700);
}
.qty__btn:disabled {
  color: var(--warm-400);
  cursor: not-allowed;
}
.qty__n {
  min-width: 38px;
  text-align: center;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-900);
  border-left: 1px solid var(--line-soft);
  border-right: 1px solid var(--line-soft);
  line-height: 32px;
}
.crow__sum {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}
.crow__sub {
  font-size: 18px;
  font-weight: 700;
  color: var(--gold-600);
}
.crow__del {
  font-size: var(--fs-cap);
  color: var(--warm-500);
  transition: color var(--dur-1) var(--ease);
}
.crow__del:hover:not(:disabled) {
  color: var(--danger);
  text-decoration: underline;
}

/* ---------- 结算条 ---------- */
.paybar {
  position: sticky;
  bottom: 0;
  margin-top: var(--sp-6);
  padding: var(--sp-4) var(--sp-5);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  background: rgba(250, 248, 243, 0.94);
  backdrop-filter: saturate(150%) blur(10px);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-2);
  flex-wrap: wrap;
}
.paybar__right {
  display: flex;
  align-items: center;
  gap: var(--sp-5);
  margin-left: auto;
}
.paybar__sum {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}
.paybar__amount {
  font-size: 26px;
  font-weight: 700;
  color: var(--gold-600);
  line-height: 1;
}
.paybar__note {
  margin-top: var(--sp-3);
  font-size: var(--fs-cap);
  color: var(--warm-500);
  text-align: right;
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

.toast {
  position: fixed;
  left: 50%;
  bottom: var(--sp-7);
  transform: translateX(-50%);
  z-index: var(--z-pop);
  padding: 12px var(--sp-5);
  font-size: var(--fs-sm);
  color: var(--brand-800);
  background: #fff;
  border: 1px solid var(--brand-100);
  border-radius: var(--r-md);
  box-shadow: var(--sh-3);
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

@media (max-width: 1080px) {
  .crow {
    grid-template-columns: 72px 1fr auto;
    grid-template-areas:
      'art main sum'
      'art qty sum';
    row-gap: var(--sp-3);
  }
  .crow__art {
    grid-area: art;
  }
  .crow__main {
    grid-area: main;
  }
  .crow__price {
    display: none;
  }
  .qty {
    grid-area: qty;
    justify-self: start;
  }
  .crow__sum {
    grid-area: sum;
  }
}
@media (max-width: 720px) {
  .chead {
    flex-direction: column;
    align-items: flex-start;
  }
  .crow {
    grid-template-columns: 64px 1fr;
    grid-template-areas:
      'art main'
      'qty sum';
    padding: var(--sp-4);
  }
  .cgroup__head {
    padding: var(--sp-3) var(--sp-4);
  }
  .paybar {
    padding: var(--sp-4);
  }
  .paybar__right {
    width: 100%;
    justify-content: space-between;
  }
  .paybar__note {
    text-align: left;
  }
}
</style>
