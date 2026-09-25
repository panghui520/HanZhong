<script setup lang="ts">
/**
 * 我的订单（M6）
 *
 * ============================================================
 * 七态状态机，但列表只负责"分类"和"催办"
 * ============================================================
 * 完整流转（下单 → 待付款 → 待发货 → 已发货 → 已完成，另有
 * 已取消 / 退款中 / 已退款三条分支）都画在 `OrderDetail.vue` 里。
 * 列表页做两件事：
 *   1. 按状态分标签，让用户一眼看到"哪几单在等我动手"；
 *   2. 把**最需要立刻做的那一个动作**（付款 / 确认收货）直接放到卡片上，
 *      不强迫他先点进详情。
 *
 * 标签是**客户端过滤**：`GET /api/orders` 本来就一次返回我的全部订单
 * （含明细），再为每个标签各发一次请求纯属浪费 —— 订单量对单个用户
 * 来说是个位数，全量拉回来在内存里分组的代价可以忽略。
 *
 * ============================================================
 * 按钮仍然来自服务端
 * ============================================================
 * 卡片上显示哪个按钮，由 `order.available_actions` 决定（服务端算的），
 * 这里只挑"最要紧的一个"渲染。前端**不写"什么状态显示什么按钮"的映射表**，
 * 理由见 `types/index.ts` 里 OrderAction 的注释。
 *
 * 订单行上刻意显示**体验锚点与产地**（下单时的快照）：产品以后下架了，
 * 这一单仍然说得清"这件东西来自哪次体验、哪个村"。这是第一条红线
 * 在用户可见界面上最直接的一次体现。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { cancelOrder, confirmReceipt, getMyOrders, payOrder } from '@/api/order'
import { ApiError } from '@/api/http'
import { useOrderStore } from '@/stores/order'
import { useSessionStore } from '@/stores/session'
import { countdown, when } from '@/utils/format'
import type { Order, OrderAction, OrderStatus } from '@/types'

const router = useRouter()
const session = useSessionStore()
const orderStore = useOrderStore()

const orders = ref<Order[]>([])
const loading = ref(true)
const error = ref('')
const busyId = ref<number | null>(null)

const notice = ref<{ type: 'ok' | 'err'; text: string } | null>(null)
let noticeTimer: number | undefined
function say(type: 'ok' | 'err', text: string) {
  notice.value = { type, text }
  if (noticeTimer !== undefined) window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => (notice.value = null), 4000)
}

async function load() {
  if (!session.isLoggedIn) {
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    orders.value = await getMyOrders()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '订单加载失败'
  } finally {
    loading.value = false
    syncTicker()
  }
}
onMounted(load)

/* ============================================================
   标签与分组
   ============================================================ */

type TabKey = 'ALL' | OrderStatus | 'AFTER_SALE'

const TABS: { key: TabKey; label: string }[] = [
  { key: 'ALL', label: '全部' },
  { key: 'PENDING_PAYMENT', label: '待付款' },
  { key: 'PENDING_SHIPMENT', label: '待发货' },
  { key: 'SHIPPED', label: '已发货' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'AFTER_SALE', label: '退款 / 取消' },
]

/** 售后类的三个状态合并成一个标签。分开列会让标签条太长，而它们的共同点是"不用你动手" */
const AFTER_SALE: OrderStatus[] = ['REFUND_REQUESTED', 'REFUNDED', 'CANCELLED']

const tab = ref<TabKey>('ALL')

function matchTab(o: Order, k: TabKey): boolean {
  if (k === 'ALL') return true
  if (k === 'AFTER_SALE') return AFTER_SALE.includes(o.status)
  return o.status === k
}

const filtered = computed(() => orders.value.filter((o) => matchTab(o, tab.value)))

/** 每个标签的数量。全量数据已在手，算一次即可，不用额外请求 */
function countOf(k: TabKey) {
  return orders.value.filter((o) => matchTab(o, k)).length
}

/** 需要用户动手的订单数（待付款 + 已发货），与顶栏角标同一个口径 */
const todoCount = computed(
  () => orders.value.filter((o) => ['PENDING_PAYMENT', 'SHIPPED'].includes(o.status)).length
)

/* ============================================================
   待付款倒计时
   ============================================================ */

const now = ref(Date.now())
let ticker: number | undefined

/**
 * 只在**列表里存在待付款订单**时开表。
 *
 * 这一页每秒重算一次，会让整张列表重新渲染。没有待付款订单时完全没必要 ——
 * 所以动作做完、列表刷新后要调一次本函数把表关掉，而不是让它在后台空转。
 */
function syncTicker() {
  const need = orders.value.some((o) => o.status === 'PENDING_PAYMENT')
  if (need && ticker === undefined) {
    ticker = window.setInterval(() => (now.value = Date.now()), 1000)
  } else if (!need && ticker !== undefined) {
    window.clearInterval(ticker)
    ticker = undefined
  }
}

onUnmounted(() => {
  if (ticker !== undefined) window.clearInterval(ticker)
  if (noticeTimer !== undefined) window.clearTimeout(noticeTimer)
})

/**
 * 某单的剩余付款秒数。
 *
 * `pay_deadline` 是后端 `LocalDateTime` 序列化出来的、不带时区的
 * `2026-09-25T19:47:12` —— 按 ECMAScript 规范这种形式按**本地时间**解释，
 * 正好与后端一致。加上 `Z` 反而会平白差 8 小时。
 */
function remainingOf(o: Order): number {
  if (!o.pay_deadline) return 0
  const t = new Date(o.pay_deadline).getTime()
  if (!Number.isFinite(t)) return 0
  return Math.max(0, Math.floor((t - now.value) / 1000))
}

/** 倒计时到点的待付款单：付款按钮要停掉，否则点下去必然拿到 6013 */
const isExpired = (o: Order) => o.status === 'PENDING_PAYMENT' && remainingOf(o) <= 0

/* ============================================================
   操作
   ============================================================ */

const can = (o: Order, a: OrderAction) => o.available_actions.includes(a)

/**
 * 列表上只放**一个**最要紧的动作，优先级：付款（这单正在倒计时，不付就没了）
 * > 确认收货（货到了，确认完才能评价）。
 *
 * 其余动作（取消、申请退款、评价）都去详情页 —— 它们要么需要填理由、
 * 要么需要选星级传图，塞在列表卡里会把卡片撑爆。
 *
 * 返回动作名而不是一个函数：模板里对 `v-if="fn"` + `@click="fn()"` 的
 * 收窄在类型检查下不总是可靠，用字符串判断更直白。
 */
function primaryOf(o: Order): 'pay' | 'receipt' | null {
  if (can(o, 'PAY') && !isExpired(o)) return 'pay'
  if (can(o, 'CONFIRM_RECEIPT')) return 'receipt'
  return null
}

async function act(o: Order, kind: 'pay' | 'cancel' | 'receipt') {
  if (busyId.value !== null) return
  if (kind === 'cancel' && !window.confirm('取消这一单？待付款订单取消后不可恢复。')) return
  if (kind === 'receipt' && !window.confirm('确认已经收到货？确认后订单将完成，并可以评价。')) {
    return
  }

  busyId.value = o.id
  try {
    const saved =
      kind === 'pay'
        ? await payOrder(o.id)
        : kind === 'cancel'
          ? await cancelOrder(o.id)
          : await confirmReceipt(o.id)
    // 整行替换而不是只改 status：状态一变，available_actions 与时间戳
    // 都会跟着变，只改 status 会让按钮停留在上一组
    const idx = orders.value.findIndex((x) => x.id === o.id)
    if (idx >= 0) orders.value[idx] = saved
    await orderStore.refresh()
    say('ok', kind === 'pay' ? '付款成功' : kind === 'cancel' ? '订单已取消' : '已确认收货')
  } catch (e) {
    if (e instanceof ApiError && e.code === 6013) {
      // 付款超时：服务端在报错前已把订单改成已取消，必须重新拉一次
      say('err', e.message)
      await load()
      await orderStore.refresh()
    } else {
      say('err', e instanceof ApiError ? e.message : '操作失败，请重试')
    }
  } finally {
    busyId.value = null
    syncTicker()
  }
}

const toDetail = (o: Order) => router.push(`/orders/${o.id}`)

/* ---------- 展示辅助 ---------- */

function statusTone(o: Order) {
  switch (o.status) {
    case 'SHIPPED':
      return 'ost--info'
    case 'COMPLETED':
      return 'ost--done'
    case 'REFUND_REQUESTED':
      return 'ost--warn'
    case 'CANCELLED':
    case 'REFUNDED':
      return 'ost--muted'
    default:
      return 'ost--wait'
  }
}

/** 明细只显示前两行，其余折成"等 N 件" —— 列表不该变成一张长清单 */
const HEAD_ITEMS = 2

const loginHref = computed(() => ({ path: '/login', query: { redirect: '/orders' } }))
</script>

<template>
  <div class="orders">
    <div class="container section">
      <div v-if="!session.isLoggedIn" class="empty empty--tall">
        <div class="empty__title">登录后查看我的订单</div>
        <div class="empty__desc">订单挂在账号上，换台设备登录也能看到。</div>
        <router-link :to="loginHref" class="btn btn-primary" style="margin-top: 20px">
          去登录
        </router-link>
      </div>

      <template v-else>
        <header class="ohead">
          <div>
            <span class="eyebrow">离境复购</span>
            <h1 class="h1 ohead__title">我的订单</h1>
            <p class="ohead__desc">
              每一单都记着它来自哪次体验、哪个村。待付款的订单有 30 分钟时限，
              超时会自动取消；收到货后点确认收货，就能评价。
            </p>
          </div>
          <router-link to="/goods" class="btn btn-ghost btn-sm">继续挑好物</router-link>
        </header>

        <Transition name="notice">
          <div v-if="notice" class="notice" :class="`notice--${notice.type}`">
            {{ notice.text }}
          </div>
        </Transition>

        <!-- 有需要动手的单时，先在顶部说一句，不用用户自己找 -->
        <div v-if="!loading && todoCount > 0" class="todo">
          <span class="todo__dot" />
          有 <b class="num">{{ todoCount }}</b> 单需要你处理（待付款 / 待收货）
        </div>

        <div v-if="loading" class="stack-4">
          <div class="skeleton" style="height: 200px; border-radius: 10px" />
          <div class="skeleton" style="height: 200px; border-radius: 10px" />
        </div>

        <div v-else-if="error" class="state-error">
          <p>{{ error }}</p>
          <button class="btn btn-ghost btn-sm" @click="load">重新加载</button>
        </div>

        <div v-else-if="!orders.length" class="empty empty--tall">
          <div class="empty__title">还没有订单</div>
          <div class="empty__desc">去「乡村好物」挑几样，从产地直接寄回家。</div>
          <router-link to="/goods" class="btn btn-primary" style="margin-top: 20px">
            去挑好物
          </router-link>
        </div>

        <template v-else>
          <!-- 状态标签 -->
          <div class="tabs">
            <button
              v-for="t in TABS"
              :key="t.key"
              class="tab"
              :class="{ 'tab--on': tab === t.key }"
              @click="tab = t.key"
            >
              {{ t.label }}
              <span class="tab__n">{{ countOf(t.key) }}</span>
            </button>
          </div>

          <div v-if="!filtered.length" class="empty">
            <div class="empty__title">这个分类下没有订单</div>
            <div class="empty__desc">换个标签看看，或者去「乡村好物」再挑几样。</div>
          </div>

          <div v-else class="olist">
            <article v-for="o in filtered" :key="o.id" class="ocard">
              <header class="ocard__head">
                <div class="ocard__ids">
                  <span class="num ocard__no">{{ o.order_no }}</span>
                  <span class="muted cap">{{ when(o.created_at) }}</span>
                </div>
                <span class="ost" :class="statusTone(o)">
                  <i class="ost__dot" />{{ o.status_label }}
                </span>
              </header>

              <ul class="oitems">
                <li v-for="it in o.items.slice(0, HEAD_ITEMS)" :key="it.id" class="oitem">
                  <div class="oitem__main">
                    <span class="oitem__name">{{ it.product_name }}</span>
                    <span class="muted cap">{{ it.spec }} · × {{ it.quantity }}</span>
                    <!-- 锚点是这一行最重要的信息，不折进小字里 -->
                    <span class="oitem__anchor">
                      <template v-if="it.poi_id">
                        <router-link :to="`/poi/${it.poi_id}`" class="oitem__link">
                          产地 · {{ it.poi_name || it.poi_id }}
                        </router-link>
                      </template>
                      <template v-else>产地未标注</template>
                      <template v-if="it.experience_name">
                        <span class="oitem__sep">/</span>
                        <span class="oitem__exp">体验 · {{ it.experience_name }}</span>
                      </template>
                    </span>
                  </div>
                  <span class="num oitem__amt">¥{{ it.subtotal }}</span>
                </li>
                <li v-if="o.items.length > HEAD_ITEMS" class="oitem__more">
                  另有 {{ o.items.length - HEAD_ITEMS }} 件，详情页可看完整清单
                </li>
              </ul>

              <!-- 待付款倒计时 -->
              <div v-if="o.status === 'PENDING_PAYMENT'" class="cd" :class="{ 'cd--over': isExpired(o) }">
                <template v-if="isExpired(o)">
                  <span class="cd__k">付款时限已过</span>
                  <span class="cd__v">订单即将自动取消</span>
                </template>
                <template v-else>
                  <span class="cd__k">剩余付款时间</span>
                  <span class="num cd__v">{{ countdown(remainingOf(o)) }}</span>
                </template>
              </div>

              <footer class="ocard__foot">
                <div class="ocard__ship">
                  <span class="muted small">共 {{ o.item_count }} 件</span>
                  <span class="num ocard__amount">¥{{ o.total_amount }}</span>
                  <span v-if="o.carrier" class="ocard__logi">
                    {{ o.carrier }} · <span class="num">{{ o.tracking_no }}</span>
                  </span>
                </div>
                <div class="ocard__acts">
                  <!-- 付款：唯一一个"不马上做就会失去"的动作，用金色主按钮 -->
                  <button
                    v-if="primaryOf(o) === 'pay'"
                    class="btn btn-gold btn-sm"
                    :disabled="busyId !== null"
                    @click="act(o, 'pay')"
                  >
                    {{ busyId === o.id ? '处理中…' : `付款 ¥${o.total_amount}` }}
                  </button>
                  <button
                    v-else-if="primaryOf(o) === 'receipt'"
                    class="btn btn-gold btn-sm"
                    :disabled="busyId !== null"
                    @click="act(o, 'receipt')"
                  >
                    {{ busyId === o.id ? '处理中…' : '确认收货' }}
                  </button>
                  <span v-else-if="isExpired(o)" class="ocard__dead">
                    已超时，进详情页可刷新状态
                  </span>
                  <button class="btn btn-ghost btn-sm" @click="toDetail(o)">查看详情</button>
                </div>
              </footer>
            </article>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.ohead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-6);
  padding-bottom: var(--sp-5);
  border-bottom: 1px solid var(--line-soft);
  margin-bottom: var(--sp-5);
  flex-wrap: wrap;
}
.ohead__title {
  margin-top: var(--sp-3);
  color: var(--ink-900);
}
.ohead__desc {
  margin-top: var(--sp-3);
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: var(--ink-500);
  max-width: 52em;
}

/* ---------- 提示 ---------- */
.notice {
  margin-bottom: var(--sp-4);
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-md);
  font-size: var(--fs-sm);
  border: 1px solid transparent;
  border-left-width: 3px;
}
.notice--ok {
  color: var(--brand-700);
  background: var(--ok-50);
  border-color: var(--brand-100);
  border-left-color: var(--brand-500);
}
.notice--err {
  color: var(--danger);
  background: var(--danger-50);
  border-color: rgba(168, 64, 43, 0.24);
  border-left-color: var(--danger);
}
.notice-enter-active,
.notice-leave-active {
  transition: opacity var(--dur-2) var(--ease);
}
.notice-enter-from,
.notice-leave-to {
  opacity: 0;
}

/* ---------- 待办提示条 ---------- */
.todo {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-4);
  padding: var(--sp-3) var(--sp-4);
  background: var(--warn-50);
  border: 1px solid rgba(168, 121, 29, 0.24);
  border-radius: var(--r-md);
  font-size: var(--fs-xs);
  color: var(--ink-700);
}
.todo__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--warn);
}
.todo b {
  color: var(--ink-900);
}

/* ---------- 标签 ---------- */
.tabs {
  display: flex;
  gap: var(--sp-2);
  margin-bottom: var(--sp-5);
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}
.tab {
  position: relative;
  padding: var(--sp-3) var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--ink-500);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: all var(--dur-1) var(--ease);
}
.tab:hover {
  color: var(--brand-700);
}
.tab--on {
  color: var(--brand-700);
  font-weight: 600;
  border-bottom-color: var(--gold-500);
}
.tab__n {
  margin-left: 6px;
  padding: 1px 7px;
  border-radius: var(--r-pill);
  font-size: var(--fs-cap);
  background: var(--paper-3);
  color: var(--ink-500);
}
.tab--on .tab__n {
  background: var(--brand-50);
  color: var(--brand-700);
}

/* ---------- 订单卡 ---------- */
.olist {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
}
.ocard {
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  overflow: hidden;
  transition: border-color var(--dur-2) var(--ease), box-shadow var(--dur-2) var(--ease);
}
.ocard:hover {
  border-color: var(--line);
  box-shadow: var(--sh-1);
}
.ocard__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: var(--sp-4) var(--sp-5);
  background: var(--paper-2);
  border-bottom: 1px solid var(--line-soft);
  flex-wrap: wrap;
}
.ocard__ids {
  display: flex;
  align-items: baseline;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.ocard__no {
  font-size: var(--fs-sm);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--ink-900);
}

.ost {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 11px;
  font-size: var(--fs-cap);
  font-weight: 600;
  border-radius: var(--r-sm);
}
.ost__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.ost--wait {
  color: var(--warn);
  background: var(--warn-50);
  border: 1px solid rgba(168, 121, 29, 0.28);
}
.ost--info {
  color: var(--tech-600);
  background: var(--tech-50);
  border: 1px solid rgba(46, 123, 196, 0.28);
}
.ost--done {
  color: var(--brand-700);
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
}
.ost--warn {
  color: var(--danger);
  background: var(--danger-50);
  border: 1px solid rgba(168, 64, 43, 0.26);
}
.ost--muted {
  color: var(--ink-500);
  background: var(--paper-3);
  border: 1px solid var(--line);
}

.oitems {
  list-style: none;
  margin: 0;
  padding: 0 var(--sp-5);
}
.oitem {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: var(--sp-4);
  padding: var(--sp-4) 0;
}
.oitem + .oitem {
  border-top: 1px solid var(--line-soft);
}
.oitem__main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.oitem__name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-900);
}
.oitem__anchor {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
  font-size: var(--fs-cap);
  color: var(--brand-600);
}
.oitem__link:hover {
  text-decoration: underline;
}
.oitem__sep {
  color: var(--warm-400);
}
.oitem__exp {
  color: var(--ink-500);
}
.oitem__amt {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-900);
}
.oitem__more {
  padding: var(--sp-3) 0 0;
  border-top: 1px solid var(--line-soft);
  font-size: var(--fs-cap);
  color: var(--warm-500);
}

/* ---------- 倒计时 ---------- */
.cd {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-5);
  background: var(--warn-50);
  border-top: 1px solid rgba(168, 121, 29, 0.2);
}
.cd--over {
  background: var(--danger-50);
  border-top-color: rgba(168, 64, 43, 0.2);
}
.cd__k {
  font-size: var(--fs-cap);
  color: var(--ink-600);
}
.cd__v {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--warn);
}
.cd--over .cd__v {
  font-size: var(--fs-xs);
  color: var(--danger);
}

/* ---------- 卡底 ---------- */
.ocard__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-5);
  padding: var(--sp-4) var(--sp-5);
  background: var(--paper-2);
  border-top: 1px solid var(--line-soft);
  flex-wrap: wrap;
}
.ocard__ship {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  flex-wrap: wrap;
  min-width: 0;
}
.ocard__amount {
  font-size: 20px;
  font-weight: 700;
  color: var(--gold-600);
}
.ocard__logi {
  font-size: var(--fs-cap);
  color: var(--ink-500);
}
.ocard__acts {
  display: flex;
  gap: var(--sp-3);
  flex-wrap: wrap;
  align-items: center;
}
.ocard__dead {
  font-size: var(--fs-cap);
  color: var(--danger);
}

/* ---------- 状态 ---------- */
.empty--tall {
  padding: var(--sp-9) var(--sp-5);
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

@media (max-width: 720px) {
  .ohead {
    flex-direction: column;
    align-items: flex-start;
  }
  .ocard__head,
  .oitems,
  .ocard__foot,
  .cd {
    padding-left: var(--sp-4);
    padding-right: var(--sp-4);
  }
  .ocard__foot {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
