<script setup lang="ts">
/**
 * 订单详情（M6）
 *
 * ============================================================
 * 这一页的核心是「状态时间线 + 服务端算好的操作按钮」
 * ============================================================
 * 页面上出现的每个按钮都来自 `order.available_actions`（服务端算的），
 * 前端**不写"什么状态显示什么按钮"的映射表**。理由见 `types/index.ts`
 * 里 OrderAction 的注释：映射表会和后端规则慢慢漂移，而漂移的后果是
 * 按钮该出现时没出现、该消失时还在（用户点了才报错）。
 *
 * 时间线是**纯展示**，它按状态推出来的"走到第几步"只影响视觉，
 * 不参与任何判定 —— 少画一个对勾不会让用户点错按钮。
 *
 * ============================================================
 * 两个必须特殊处理的地方
 * ============================================================
 * 1. **6013（付款超时）**：服务端在返回 6013 之前已经把订单顺手改成了
 *    已取消（见 OrderService.payOrder 的注释）。所以收到 6013 不能只弹提示，
 *    必须重新拉一次订单 —— 否则页面还停在"待付款 + 付款按钮"，
 *    用户会以为没生效而反复点。
 *
 * 2. **待付款倒计时到 0 之后不能自己把状态改成"已取消"**：真正的取消
 *    由服务端每分钟一次的定时任务执行，最多晚 60 秒。前端若自己改，
 *    页面会显示"已取消"而库里还是待付款，刷新就打脸。所以到点只是
 *    停掉付款按钮并提示"即将自动取消"，状态一律以服务端回读为准。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  cancelOrder,
  confirmReceipt,
  createReview,
  getMyOrder,
  payOrder,
  requestRefund,
  uploadReviewImage,
} from '@/api/order'
import { ApiError } from '@/api/http'
import { useOrderStore } from '@/stores/order'
import { useSessionStore } from '@/stores/session'
import { countdown, mediaSrc, when } from '@/utils/format'
import type { Order, OrderAction, OrderStatus } from '@/types'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const orderStore = useOrderStore()

const order = ref<Order | null>(null)
const loading = ref(true)
const error = ref('')
const busy = ref(false)

const notice = ref<{ type: 'ok' | 'err'; text: string } | null>(null)
let noticeTimer: number | undefined
function say(type: 'ok' | 'err', text: string) {
  notice.value = { type, text }
  if (noticeTimer !== undefined) window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => (notice.value = null), 4000)
}

const orderId = computed(() => Number(route.params.id))

async function load() {
  if (!session.isLoggedIn) {
    loading.value = false
    return
  }
  if (!Number.isFinite(orderId.value) || orderId.value <= 0) {
    error.value = '订单号不正确'
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    order.value = await getMyOrder(orderId.value)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '订单加载失败'
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  await load()
  // 从结算页跳过来时带 ?created=1，提示一句"该付款了"。
  // 结算成功后**直接落到详情页**而不是订单列表 —— 用户此刻唯一想做的事
  // 就是付款，把他丢到列表里再找一次"付款"按钮是多余的一步。
  if (route.query.created) {
    say('ok', '下单成功，请在 30 分钟内完成付款')
  }
  // 倒计时表在拿到订单之后才启动：`remaining` 依赖 order.pay_deadline。
  // 表自己会在倒计时归零后停掉，所以对"不是待付款"的订单也不会空转。
  startTicker()
})

/* ============================================================
   状态时间线
   ============================================================ */

/** 主流程四步。分支状态（已取消 / 退款中 / 已退款）不在这条线上 */
const FLOW: { status: OrderStatus; label: string; hint: string }[] = [
  { status: 'PENDING_PAYMENT', label: '待付款', hint: '30 分钟内未付款，订单会自动取消' },
  { status: 'PENDING_SHIPMENT', label: '待发货', hint: '运营正在备货，发货后会填物流信息' },
  { status: 'SHIPPED', label: '已发货', hint: '货物在途，收到后请点「确认收货」' },
  { status: 'COMPLETED', label: '已完成', hint: '交易完成，可以评价这次购买' },
]

/**
 * 当前走到第几步（0..3）。
 *
 * 分支状态的落点这样推：
 *   - 已取消 —— 只能从「待付款」取消（已付款的要走退款），所以停在 0
 *   - 退款中 / 已退款 —— 从「待发货」或「已发货」申请来的。
 *     `status_before_refund` 服务端刻意不外露（纯内部字段），
 *     但用 `shipped_at` 有没有值就能判断：发过货就是第 2 步，否则第 1 步。
 */
const flowIndex = computed(() => {
  const s = order.value?.status
  if (!s) return 0
  const idx = FLOW.findIndex((f) => f.status === s)
  if (idx >= 0) return idx
  if (s === 'CANCELLED') return 0
  return order.value?.shipped_at ? 2 : 1
})

/** 分支状态：不在主流程线上，需要单独一句说明"为什么停了" */
const isBranch = computed(() =>
  ['CANCELLED', 'REFUND_REQUESTED', 'REFUNDED'].includes(order.value?.status ?? '')
)

const branchNote = computed(() => {
  const o = order.value
  if (!o) return ''
  if (o.status === 'CANCELLED') {
    return o.cancel_reason === 'TIMEOUT'
      ? `超时未付款，系统已自动取消（${when(o.cancelled_at)}）`
      : `你已取消这一单（${when(o.cancelled_at)}）`
  }
  if (o.status === 'REFUND_REQUESTED') {
    return `退款申请已提交，等待运营处理（${when(o.refund_at)}）`
  }
  if (o.status === 'REFUNDED') {
    return `退款已通过，款项原路返回（${when(o.refund_handled_at)}）`
  }
  return ''
})

/** 每一步的完成时刻。没有的时刻返回空串，模板里不渲染 */
function stepTime(i: number) {
  const o = order.value
  if (!o) return ''
  return when([o.created_at, o.paid_at, o.shipped_at, o.received_at][i])
}

/**
 * 状态徽标的色调。
 *
 * 注意这与 `status_label` 的分工：**文案和可做的操作都来自服务端**，
 * 这里只决定用什么颜色。所以就算漏了一种状态、颜色回落成默认灰，
 * 后果也只是"颜色不好看"，不会出现"该有的按钮没有"。
 */
const statusTone = computed(() => {
  switch (order.value?.status) {
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
})

/* ============================================================
   待付款倒计时
   ============================================================ */

/** 每秒滴答一次，只为了重算 remaining。到点后停表，不再空转 */
const now = ref(Date.now())
let ticker: number | undefined

function startTicker() {
  if (ticker !== undefined) return
  ticker = window.setInterval(() => {
    now.value = Date.now()
    // 倒计时结束后没必要继续每秒唤醒一次
    if (remaining.value <= 0 && ticker !== undefined) {
      window.clearInterval(ticker)
      ticker = undefined
    }
  }, 1000)
}

onUnmounted(() => {
  if (ticker !== undefined) window.clearInterval(ticker)
  if (noticeTimer !== undefined) window.clearTimeout(noticeTimer)
})

/**
 * 剩余秒数。`pay_deadline` 是 `LocalDateTime` 序列化出来的、**不带时区**的
 * 形如 `2026-09-25T19:47:12` —— 按 ECMAScript 规范，这种"日期+时间但无偏移"
 * 的字符串按**本地时间**解释，正好和后端一致。
 * 若改成 `new Date(iso + 'Z')` 就会平白差 8 小时，倒计时会显示成 8 小时后。
 */
const remaining = computed(() => {
  const dl = order.value?.pay_deadline
  if (!dl) return 0
  const t = new Date(dl).getTime()
  if (!Number.isFinite(t)) return 0
  return Math.max(0, Math.floor((t - now.value) / 1000))
})

const expired = computed(() => !!order.value?.pay_deadline && remaining.value <= 0)

const remainingText = computed(() => countdown(remaining.value))

/* ============================================================
   操作
   ============================================================ */

/** 服务端算好的可做操作。页面不自己判断状态 */
const available = computed<OrderAction[]>(() => order.value?.available_actions ?? [])
const can = (a: OrderAction) => available.value.includes(a)

/** 付款按钮在倒计时到点后要停掉：这时候点下去必然拿到 6013 */
const canPay = computed(() => can('PAY') && !expired.value)

/**
 * 统一的动作包装。
 *
 * 三件事必须一起做，少一件页面就会说谎：
 *   1. 用服务端回读的订单**整份替换**本地对象（不是改一个 status 字段）
 *      —— 状态一变，available_actions、时间戳都会跟着变，只改 status
 *      会让按钮停留在上一组；
 *   2. 刷新顶栏角标（待付款 / 已发货的订单数变了）；
 *   3. 出错时不假装成功。
 */
async function run(label: string, fn: () => Promise<Order>) {
  if (busy.value) return
  busy.value = true
  try {
    order.value = await fn()
    await orderStore.refresh()
    say('ok', `${label}成功`)
  } catch (e) {
    if (e instanceof ApiError && e.code === 6013) {
      // 付款超时：服务端在报错前已经把订单改成已取消，必须重新拉一次
      say('err', e.message)
      await load()
      await orderStore.refresh()
    } else {
      say('err', e instanceof ApiError ? e.message : '操作失败，请重试')
    }
  } finally {
    busy.value = false
  }
}

const doPay = () => run('付款', () => payOrder(orderId.value))

async function doCancel() {
  if (!window.confirm('取消这一单？待付款订单取消后不可恢复。')) return
  await run('取消订单', () => cancelOrder(orderId.value))
}

async function doConfirmReceipt() {
  if (!window.confirm('确认已经收到货？确认后订单将完成，并可以评价。')) return
  await run('确认收货', () => confirmReceipt(orderId.value))
}

/* ---------- 申请退款 ---------- */

const refundOpen = ref(false)
const refundReason = ref('')
const refundError = ref('')

function openRefund() {
  refundOpen.value = true
  refundError.value = ''
}

async function submitRefund() {
  const reason = refundReason.value.trim()
  if (!reason) {
    refundError.value = '请说明退款原因，运营要按它判断'
    return
  }
  refundError.value = ''
  if (busy.value) return
  busy.value = true
  try {
    order.value = await requestRefund(orderId.value, reason)
    await orderStore.refresh()
    refundOpen.value = false
    refundReason.value = ''
    say('ok', '退款申请已提交，等待运营处理')
  } catch (e) {
    refundError.value = e instanceof ApiError ? e.message : '提交失败，请重试'
  } finally {
    busy.value = false
  }
}

/* ---------- 评价 ---------- */

/** 与后端 OrderService.MAX_REVIEW_IMAGES 一致。服务端还会再截一次，前端只是体验 */
const MAX_IMAGES = 3

const reviewOpen = ref(false)
const reviewRating = ref(0)
const hoverRating = ref(0)
const reviewContent = ref('')
/** 上传成功后的**相对路径**（提交时要传这个，不是完整 URL） */
const reviewImages = ref<string[]>([])
const uploading = ref(false)
const reviewError = ref('')

function openReview() {
  reviewOpen.value = true
  reviewError.value = ''
  reviewRating.value = 0
  reviewContent.value = ''
  reviewImages.value = []
}

/**
 * 选图上传。
 *
 * 两个容易漏的点：
 *   1. 一次只传一张（循环调接口）。三张一起传时失败一张就得整批重来，
 *      分开传可以只重试那一张。
 *   2. 处理完必须把 `input.value` 清空 —— 否则用户再选**同一个文件**
 *      不会触发 change 事件，看起来像"点了没反应"。
 */
async function onPickImages(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return

  for (const f of files) {
    if (reviewImages.value.length >= MAX_IMAGES) {
      say('err', `最多上传 ${MAX_IMAGES} 张图片`)
      break
    }
    uploading.value = true
    try {
      const { path } = await uploadReviewImage(f)
      reviewImages.value.push(path)
    } catch (err) {
      say('err', err instanceof ApiError ? err.message : '图片上传失败')
    } finally {
      uploading.value = false
    }
  }
}

function removeImage(i: number) {
  reviewImages.value.splice(i, 1)
}

async function submitReview() {
  if (reviewRating.value < 1) {
    reviewError.value = '请先选星级'
    return
  }
  reviewError.value = ''
  if (busy.value) return
  busy.value = true
  try {
    await createReview(orderId.value, {
      rating: reviewRating.value,
      content: reviewContent.value.trim() || undefined,
      images: reviewImages.value,
    })
    // createReview 返回的是评价本身，不是订单。要拿到"评价后
    // available_actions 里没有 REVIEW 了"的订单，必须重新读一次。
    await load()
    await orderStore.refresh()
    reviewOpen.value = false
    say('ok', '评价已提交，谢谢')
  } catch (e) {
    reviewError.value = e instanceof ApiError ? e.message : '提交失败，请重试'
  } finally {
    busy.value = false
  }
}

const backToOrders = () => router.push('/orders')
const loginHref = computed(() => ({ path: '/login', query: { redirect: route.fullPath } }))
</script>

<template>
  <div class="od">
    <div class="container section">
      <!-- 未登录 -->
      <div v-if="!session.isLoggedIn" class="empty empty--tall">
        <div class="empty__title">登录后查看订单详情</div>
        <div class="empty__desc">订单挂在账号上，换台设备登录也能看到。</div>
        <router-link :to="loginHref" class="btn btn-primary" style="margin-top: 20px">
          去登录
        </router-link>
      </div>

      <div v-else-if="loading" class="stack-4">
        <div class="skeleton" style="height: 120px; border-radius: 10px" />
        <div class="skeleton" style="height: 320px; border-radius: 10px" />
      </div>

      <div v-else-if="error" class="state-error">
        <p>{{ error }}</p>
        <div class="state-error__acts">
          <button class="btn btn-ghost btn-sm" @click="load">重新加载</button>
          <button class="btn btn-ghost btn-sm" @click="backToOrders">返回订单列表</button>
        </div>
      </div>

      <template v-else-if="order">
        <!-- ============ 页头 ============ -->
        <header class="odhead">
          <div>
            <nav class="crumbs">
              <router-link to="/orders" class="crumbs__link">我的订单</router-link>
              <span class="crumbs__sep">/</span>
              <span class="crumbs__cur">订单详情</span>
            </nav>
            <h1 class="h1 odhead__title">
              单号 <span class="num odhead__no">{{ order.order_no }}</span>
            </h1>
            <p class="odhead__meta">
              下单于 {{ when(order.created_at) }} ·
              {{ order.channel === 'REPURCHASE' ? '离境复购' : '到访当场' }}
            </p>
          </div>
          <span class="ost" :class="statusTone">
            <i class="ost__dot" />{{ order.status_label }}
          </span>
        </header>

        <Transition name="notice">
          <div v-if="notice" class="notice" :class="`notice--${notice.type}`">
            {{ notice.text }}
          </div>
        </Transition>

        <div class="od__grid">
          <!-- ================= 左：主信息 ================= -->
          <div class="od__main">
            <!-- ---------- 状态时间线 ---------- -->
            <section class="card tl">
              <h2 class="card__title">流转进度</h2>

              <ol class="tl__flow" :class="{ 'tl__flow--branch': isBranch }">
                <li
                  v-for="(s, i) in FLOW"
                  :key="s.status"
                  class="tl__step"
                  :class="{
                    'tl__step--done': i < flowIndex,
                    'tl__step--cur': i === flowIndex,
                    'tl__step--todo': i > flowIndex,
                  }"
                >
                  <span class="tl__dot">
                    <svg v-if="i < flowIndex" viewBox="0 0 24 24" width="12" height="12">
                      <path
                        d="M5 12.6l4.4 4.4L19 7.4"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2.6"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                      />
                    </svg>
                  </span>
                  <span class="tl__label">{{ s.label }}</span>
                  <span class="tl__time">{{ stepTime(i) || '—' }}</span>
                </li>
              </ol>

              <p v-if="isBranch" class="tl__branch">
                <span class="tl__branch-k">分支</span>{{ branchNote }}
              </p>
              <p v-else class="tl__hint">{{ FLOW[flowIndex].hint }}</p>
            </section>

            <!-- ---------- 物流信息 ---------- -->
            <section v-if="order.shipped_at" class="card">
              <h2 class="card__title">物流信息</h2>
              <div class="lg">
                <div class="lg__row">
                  <span class="lg__k">快递公司</span>
                  <span class="lg__v">{{ order.carrier || '—' }}</span>
                </div>
                <div class="lg__row">
                  <span class="lg__k">快递单号</span>
                  <span class="num lg__v lg__v--no">{{ order.tracking_no || '—' }}</span>
                </div>
                <div class="lg__row">
                  <span class="lg__k">预计到达</span>
                  <span class="lg__v">
                    {{ order.eta_days ? `约 ${order.eta_days} 天` : '—' }}
                    <span class="muted small">（发货于 {{ when(order.shipped_at) }}）</span>
                  </span>
                </div>
              </div>
              <p class="lg__note">
                物流信息由运营发货时手工填写，系统不查询快递公司接口 —— 这是演示级实现，
                单号仅用于说明流程，不能拿去官网查件。
              </p>
            </section>

            <!-- ---------- 订单明细 ---------- -->
            <section class="card">
              <h2 class="card__title">订单明细</h2>
              <ul class="oitems">
                <li v-for="it in order.items" :key="it.id" class="oitem">
                  <div class="oitem__main">
                    <span class="oitem__name">{{ it.product_name }}</span>
                    <span class="muted cap">{{ it.spec }} · ¥{{ it.unit_price }} × {{ it.quantity }}</span>
                    <!--
                      锚点是这一行最重要的信息，不折进小字里（红线一：
                      农产品必须挂靠体验或产地，不做独立商城）
                    -->
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
              </ul>
            </section>

            <!-- ---------- 收货信息 ---------- -->
            <section class="card">
              <h2 class="card__title">收货信息</h2>
              <div class="rcv">
                <p class="rcv__line">
                  <span class="rcv__name">{{ order.receiver_name }}</span>
                  <span class="num rcv__phone">{{ order.receiver_phone }}</span>
                </p>
                <p class="rcv__addr">{{ order.receiver_address }}</p>
                <p v-if="order.remark" class="rcv__remark">备注：{{ order.remark }}</p>
              </div>
              <p class="rcv__note">
                这是下单时的快照。以后改地址不影响这一单 —— 历史订单显示的就该是当时的地址。
              </p>
            </section>
          </div>

          <!-- ================= 右：操作与金额 ================= -->
          <aside class="od__side">
            <!-- ---------- 操作卡 ---------- -->
            <section class="card act">
              <div class="act__amount">
                <span class="muted cap">共 {{ order.item_count }} 件，合计</span>
                <span class="num act__total">¥{{ order.total_amount }}</span>
              </div>

              <!-- 待付款倒计时 -->
              <div v-if="canPay || expired" class="cd" :class="{ 'cd--over': expired }">
                <template v-if="expired">
                  <span class="cd__k">付款时限已过</span>
                  <span class="cd__v">订单即将自动取消</span>
                </template>
                <template v-else>
                  <span class="cd__k">剩余付款时间</span>
                  <span class="num cd__v">{{ remainingText }}</span>
                </template>
              </div>
              <p v-if="expired" class="cd__note">
                自动取消由服务端每分钟扫描一次，最长可能晚 60 秒。点下面的「刷新状态」
                可以立刻看到最新结果。
              </p>

              <div class="act__btns">
                <button
                  v-if="canPay"
                  class="btn btn-gold"
                  :disabled="busy"
                  @click="doPay"
                >
                  {{ busy ? '处理中…' : `付款 ¥${order.total_amount}` }}
                </button>
                <button
                  v-if="can('CANCEL')"
                  class="btn btn-ghost"
                  :disabled="busy"
                  @click="doCancel"
                >
                  取消订单
                </button>
                <button
                  v-if="can('CONFIRM_RECEIPT')"
                  class="btn btn-primary"
                  :disabled="busy"
                  @click="doConfirmReceipt"
                >
                  确认收货
                </button>
                <button
                  v-if="can('REQUEST_REFUND')"
                  class="btn btn-ghost"
                  :disabled="busy"
                  @click="openRefund"
                >
                  申请退款
                </button>
                <button
                  v-if="can('REVIEW') && !order.review"
                  class="btn btn-primary"
                  :disabled="busy"
                  @click="openReview"
                >
                  评价这次购买
                </button>

                <!-- 既没有可做的操作、也没有已完成的评价 → 说明这一单到此为止 -->
                <p v-if="!available.length && !order.review" class="act__none">
                  这一单没有需要你做的操作了。
                </p>

                <button v-if="expired" class="btn btn-ghost btn-sm" :disabled="busy" @click="load">
                  刷新状态
                </button>
              </div>

              <!-- 申请退款（内联展开，不做弹窗） -->
              <div v-if="refundOpen" class="sub">
                <label class="sub__label" for="rf">退款原因</label>
                <textarea
                  id="rf"
                  v-model="refundReason"
                  class="sub__ta"
                  rows="3"
                  maxlength="200"
                  placeholder="例如：买重了 / 临时不需要了。运营要按它判断是否同意"
                />
                <p v-if="refundError" class="sub__err">{{ refundError }}</p>
                <div class="sub__acts">
                  <button class="btn btn-primary btn-sm" :disabled="busy" @click="submitRefund">
                    {{ busy ? '提交中…' : '提交申请' }}
                  </button>
                  <button class="btn btn-ghost btn-sm" @click="refundOpen = false">放弃</button>
                </div>
              </div>
            </section>

            <!-- ---------- 退款进度 ---------- -->
            <section v-if="order.refund_at" class="card">
              <h2 class="card__title">退款进度</h2>
              <div class="lg">
                <div class="lg__row">
                  <span class="lg__k">申请时间</span>
                  <span class="lg__v">{{ when(order.refund_at) }}</span>
                </div>
                <div class="lg__row">
                  <span class="lg__k">退款原因</span>
                  <span class="lg__v">{{ order.refund_reason || '—' }}</span>
                </div>
                <div v-if="order.refund_handled_at" class="lg__row">
                  <span class="lg__k">处理时间</span>
                  <span class="lg__v">{{ when(order.refund_handled_at) }}</span>
                </div>
                <div v-if="order.refund_reply" class="lg__row">
                  <span class="lg__k">运营回复</span>
                  <span class="lg__v">{{ order.refund_reply }}</span>
                </div>
              </div>
              <p v-if="order.status === 'REFUND_REQUESTED'" class="lg__note">
                等运营处理。同意则订单变成「已退款」；不同意会退回申请前的状态，
                并在这里给出理由，你可以改一下原因再申请。
              </p>
            </section>

            <!-- ---------- 评价 ---------- -->
            <section v-if="order.review || reviewOpen" class="card">
              <h2 class="card__title">{{ order.review ? '我的评价' : '评价这次购买' }}</h2>

              <!-- 已评价：只读展示 -->
              <template v-if="order.review">
                <div class="rv">
                  <span class="stars" :aria-label="`${order.review.rating} 星`">
                    <svg
                      v-for="i in 5"
                      :key="i"
                      viewBox="0 0 24 24"
                      width="15"
                      height="15"
                      :class="i <= order.review.rating ? 'star--on' : 'star--off'"
                    >
                      <path
                        d="M12 2.8l2.9 6 6.5.9-4.7 4.5 1.1 6.4L12 17.5 6.2 20.6l1.1-6.4L2.6 9.7l6.5-.9z"
                        fill="currentColor"
                      />
                    </svg>
                  </span>
                  <span class="muted cap">{{ when(order.review.created_at) }}</span>
                </div>
                <p v-if="order.review.content" class="rv__text">{{ order.review.content }}</p>
                <div v-if="order.review.images.length" class="rv__imgs">
                  <img
                    v-for="(src, i) in order.review.images"
                    :key="i"
                    class="rv__img"
                    :src="src"
                    alt="评价图片"
                    loading="lazy"
                  />
                </div>
              </template>

              <!-- 未评价：表单 -->
              <template v-else>
                <div class="rv__pick">
                  <span class="sub__label">星级</span>
                  <div class="starpick" @mouseleave="hoverRating = 0">
                    <button
                      v-for="i in 5"
                      :key="i"
                      type="button"
                      class="starpick__b"
                      :aria-label="`${i} 星`"
                      @mouseenter="hoverRating = i"
                      @click="reviewRating = i"
                    >
                      <svg
                        viewBox="0 0 24 24"
                        width="24"
                        height="24"
                        :class="
                          i <= (hoverRating || reviewRating) ? 'star--on' : 'star--off'
                        "
                      >
                        <path
                          d="M12 2.8l2.9 6 6.5.9-4.7 4.5 1.1 6.4L12 17.5 6.2 20.6l1.1-6.4L2.6 9.7l6.5-.9z"
                          fill="currentColor"
                        />
                      </svg>
                    </button>
                    <span class="starpick__t">{{ reviewRating ? `${reviewRating} 星` : '请选择' }}</span>
                  </div>
                </div>

                <label class="sub__label" for="rc">评价内容（可空）</label>
                <textarea
                  id="rc"
                  v-model="reviewContent"
                  class="sub__ta"
                  rows="3"
                  maxlength="500"
                  placeholder="说说这次收到的农产品怎么样，比如口感、包装、发货速度"
                />

                <span class="sub__label">
                  图片（可空，最多 {{ MAX_IMAGES }} 张）
                </span>
                <div class="up">
                  <div v-for="(p, i) in reviewImages" :key="p" class="up__cell">
                    <img class="up__img" :src="mediaSrc(p)" alt="待提交的图片" />
                    <button class="up__del" type="button" title="移除" @click="removeImage(i)">
                      ×
                    </button>
                  </div>
                  <label v-if="reviewImages.length < MAX_IMAGES" class="up__add">
                    <input
                      class="up__input"
                      type="file"
                      accept="image/*"
                      multiple
                      :disabled="uploading"
                      @change="onPickImages"
                    />
                    <span class="up__plus">{{ uploading ? '…' : '+' }}</span>
                    <span class="up__hint">{{ uploading ? '上传中' : '选择图片' }}</span>
                  </label>
                </div>
                <p class="sub__note">
                  图片会先上传、提交评价时才和文字一起保存。一次传一张，某张失败可以单独重传。
                </p>

                <p v-if="reviewError" class="sub__err">{{ reviewError }}</p>
                <div class="sub__acts">
                  <button class="btn btn-primary btn-sm" :disabled="busy || uploading" @click="submitReview">
                    {{ busy ? '提交中…' : '提交评价' }}
                  </button>
                  <button class="btn btn-ghost btn-sm" @click="reviewOpen = false">放弃</button>
                </div>
              </template>
            </section>

            <router-link to="/goods" class="btn btn-ghost btn-sm od__more">
              继续挑好物
            </router-link>
          </aside>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 页头 ---------- */
.crumbs {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.crumbs__link:hover {
  color: var(--brand-700);
}
.crumbs__sep {
  color: var(--warm-400);
}
.odhead {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-5);
  padding-bottom: var(--sp-5);
  border-bottom: 1px solid var(--line-soft);
  margin-bottom: var(--sp-5);
  flex-wrap: wrap;
}
.odhead__title {
  margin-top: var(--sp-3);
  color: var(--ink-900);
}
.odhead__no {
  letter-spacing: 0.03em;
}
.odhead__meta {
  margin-top: var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

/* ---------- 状态徽标 ---------- */
.ost {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 13px;
  font-size: var(--fs-xs);
  font-weight: 600;
  border-radius: var(--r-sm);
  flex: none;
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

/* ---------- 提示条 ---------- */
.notice {
  margin-bottom: var(--sp-5);
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

/* ---------- 两栏（沿用首页的不对称比例） ---------- */
.od__grid {
  display: grid;
  grid-template-columns: 1.32fr 1fr;
  gap: var(--sp-5);
  align-items: start;
}
.od__main,
.od__side {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
  min-width: 0;
}

.card {
  padding: var(--sp-5);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
}
.card__title {
  font-size: var(--fs-h3);
  color: var(--ink-900);
  margin-bottom: var(--sp-4);
}

/* ---------- 时间线 ---------- */
.tl__flow {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--sp-2);
}
.tl__step {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: var(--sp-5);
}
/* 连接线画在圆点所在的水平线上，从本步圆点左侧延伸到上一步 */
.tl__step::before {
  content: '';
  position: absolute;
  top: 7px;
  left: -50%;
  width: 100%;
  height: 1px;
  background: var(--line);
}
.tl__step:first-child::before {
  display: none;
}
.tl__step--done::before,
.tl__step--cur::before {
  background: var(--brand-500);
}
.tl__dot {
  position: absolute;
  top: 0;
  left: 0;
  width: 15px;
  height: 15px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #fff;
  border: 1.5px solid var(--line);
  color: #fff;
}
.tl__step--done .tl__dot {
  background: var(--brand-500);
  border-color: var(--brand-500);
}
.tl__step--cur .tl__dot {
  border-color: var(--brand-600);
  box-shadow: 0 0 0 3px var(--brand-50);
}
.tl__step--cur .tl__dot::after {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-600);
}
.tl__label {
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--ink-500);
}
.tl__step--done .tl__label,
.tl__step--cur .tl__label {
  color: var(--ink-900);
}
.tl__time {
  font-size: var(--fs-cap);
  color: var(--warm-500);
  min-height: 16px;
}
.tl__hint {
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
  border-top: 1px dashed var(--line);
  font-size: var(--fs-xs);
  color: var(--ink-500);
}
/* 分支状态：整条线压暗，避免看起来"还在正常流转" */
.tl__flow--branch .tl__step--cur .tl__dot {
  border-color: var(--warm-400);
  box-shadow: none;
}
.tl__flow--branch .tl__step--cur .tl__dot::after {
  background: var(--warm-400);
}
.tl__branch {
  margin-top: var(--sp-5);
  padding: var(--sp-3) var(--sp-4);
  background: var(--paper-2);
  border-left: 3px solid var(--warm-400);
  border-radius: var(--r-sm);
  font-size: var(--fs-xs);
  color: var(--ink-600);
}
.tl__branch-k {
  margin-right: var(--sp-2);
  font-size: var(--fs-cap);
  letter-spacing: 0.08em;
  color: var(--warm-500);
}

/* ---------- 键值行（物流 / 退款共用） ---------- */
.lg {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.lg__row {
  display: grid;
  grid-template-columns: 88px 1fr;
  gap: var(--sp-3);
  align-items: baseline;
  font-size: var(--fs-sm);
}
.lg__k {
  font-size: var(--fs-cap);
  letter-spacing: 0.06em;
  color: var(--warm-500);
}
.lg__v {
  color: var(--ink-700);
  min-width: 0;
  word-break: break-all;
}
.lg__v--no {
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--ink-900);
}
.lg__note {
  margin-top: var(--sp-4);
  padding-top: var(--sp-3);
  border-top: 1px dashed var(--line);
  font-size: var(--fs-cap);
  line-height: 1.75;
  color: var(--ink-500);
}

/* ---------- 明细 ---------- */
.oitems {
  list-style: none;
  margin: 0;
  padding: 0;
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

/* ---------- 收货信息 ---------- */
.rcv__line {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.rcv__name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-900);
}
.rcv__phone {
  font-size: var(--fs-sm);
  color: var(--ink-600);
}
.rcv__addr {
  margin-top: var(--sp-2);
  font-size: var(--fs-xs);
  line-height: 1.75;
  color: var(--ink-600);
}
.rcv__remark {
  margin-top: var(--sp-2);
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.rcv__note {
  margin-top: var(--sp-4);
  padding-top: var(--sp-3);
  border-top: 1px dashed var(--line);
  font-size: var(--fs-cap);
  line-height: 1.75;
  color: var(--ink-500);
}

/* ---------- 操作卡 ---------- */
.act {
  border-color: var(--line);
  box-shadow: var(--sh-1);
}
.act__amount {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-bottom: var(--sp-4);
  border-bottom: 1px solid var(--line-soft);
}
.act__total {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--gold-600);
}
.act__btns {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  margin-top: var(--sp-4);
}
.act__btns .btn {
  width: 100%;
  justify-content: center;
}
.act__none {
  font-size: var(--fs-xs);
  color: var(--ink-500);
  text-align: center;
  padding: var(--sp-2) 0;
}

/* ---------- 倒计时 ---------- */
.cd {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-top: var(--sp-4);
  padding: var(--sp-3) var(--sp-4);
  background: var(--warn-50);
  border: 1px solid rgba(168, 121, 29, 0.24);
  border-radius: var(--r-md);
}
.cd--over {
  background: var(--danger-50);
  border-color: rgba(168, 64, 43, 0.24);
}
.cd__k {
  font-size: var(--fs-cap);
  color: var(--ink-600);
}
.cd__v {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--warn);
}
.cd--over .cd__v {
  font-size: var(--fs-sm);
  color: var(--danger);
}
.cd__note {
  margin-top: var(--sp-2);
  font-size: var(--fs-cap);
  line-height: 1.7;
  color: var(--ink-500);
}

/* ---------- 内联子表单（退款 / 评价共用） ---------- */
.sub {
  margin-top: var(--sp-4);
  padding-top: var(--sp-4);
  border-top: 1px dashed var(--line);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.sub__label {
  display: block;
  margin-top: var(--sp-3);
  font-size: var(--fs-cap);
  letter-spacing: 0.06em;
  color: var(--warm-500);
}
.sub__ta {
  width: 100%;
  padding: var(--sp-3);
  font-family: inherit;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--ink-900);
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  resize: vertical;
}
.sub__ta:focus {
  outline: none;
  border-color: var(--brand-500);
  background: #fff;
}
.sub__err {
  font-size: var(--fs-cap);
  color: var(--danger);
}
.sub__note {
  font-size: var(--fs-cap);
  line-height: 1.7;
  color: var(--ink-500);
}
.sub__acts {
  display: flex;
  gap: var(--sp-3);
  margin-top: var(--sp-2);
}

/* ---------- 星级 ---------- */
.stars {
  display: inline-flex;
  gap: 2px;
  color: var(--gold-500);
}
.star--on {
  color: var(--gold-500);
}
.star--off {
  color: var(--line);
}
.rv__pick {
  display: flex;
  flex-direction: column;
}
.starpick {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-top: var(--sp-2);
}
.starpick__b {
  padding: 2px;
  line-height: 0;
  transition: transform var(--dur-1) var(--ease);
}
.starpick__b:hover {
  transform: scale(1.12);
}
.starpick__t {
  margin-left: var(--sp-3);
  font-size: var(--fs-cap);
  color: var(--ink-500);
}

/* ---------- 已评价展示 ---------- */
.rv {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.rv__text {
  margin-top: var(--sp-3);
  font-size: var(--fs-xs);
  line-height: 1.85;
  color: var(--ink-600);
  white-space: pre-wrap;
}
.rv__imgs {
  display: flex;
  gap: var(--sp-2);
  margin-top: var(--sp-3);
  flex-wrap: wrap;
}
.rv__img {
  width: 72px;
  height: 72px;
  object-fit: cover;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

/* ---------- 图片上传 ---------- */
.up {
  display: flex;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-top: var(--sp-2);
}
.up__cell {
  position: relative;
  width: 72px;
  height: 72px;
}
.up__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}
.up__del {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  font-size: 14px;
  line-height: 1;
  color: #fff;
  background: var(--danger);
  border-radius: 50%;
  box-shadow: var(--sh-1);
}
.up__add {
  width: 72px;
  height: 72px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border: 1px dashed var(--line);
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: all var(--dur-1) var(--ease);
}
.up__add:hover {
  border-color: var(--brand-500);
  background: var(--brand-50);
}
.up__input {
  display: none;
}
.up__plus {
  font-size: 20px;
  line-height: 1;
  color: var(--warm-500);
}
.up__hint {
  font-size: 10px;
  color: var(--warm-500);
}

/* ---------- 其它 ---------- */
.od__more {
  align-self: flex-start;
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
.state-error__acts {
  display: flex;
  gap: var(--sp-3);
}

@media (max-width: 1000px) {
  .od__grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 720px) {
  .odhead {
    flex-direction: column;
  }
  .tl__flow {
    grid-template-columns: 1fr 1fr;
    gap: var(--sp-4) var(--sp-2);
  }
  .tl__step:nth-child(3)::before {
    display: none;
  }
  .lg__row {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
</style>
