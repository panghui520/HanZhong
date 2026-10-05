<script setup lang="ts">
/**
 * 订单处理（M6 运营端）
 *
 * ============================================================
 * 运营在这一页做两件事：发货、处理退款
 * ============================================================
 * 发货要填**物流三要素**（快递公司 / 预计到达天数 / 快递单号），
 * 缺一项服务端返回 6009。不做"没填就存空字符串"的宽松处理 ——
 * 物流信息是给用户看的，存一条空记录比报错更糟：用户点开详情
 * 看到"快递公司：—"，却不知道该找谁。
 *
 * 处理退款有两个方向：同意（订单变已退款，终态）与拒绝（退回**申请前的状态**
 * 并记下理由）。拒绝时理由也要填 —— 用户看不到理由只会再申请一次，
 * 运营还要再拒一次。
 *
 * **支付与物流都是演示级**：不接真实支付通道（"付款"就是用户点一下按钮），
 * 物流由运营手工填写、系统不查快递接口。这条在验收记录里写明了，
 * 答辩时不能含糊成"我们做了支付"。
 *
 * ============================================================
 * 排序与筛选
 * ============================================================
 * 列表顺序**由服务端决定**（`actionPriority`：待发货 → 退款中 → 待付款 →
 * 已发货 → 终态），前端不再排一次 —— 两处各排一次，迟早有一处被改坏。
 *
 * 标签筛选放在**客户端**：这一页需要每个状态的**数量**（标签上的角标），
 * 而按状态逐个请求就要发 5 次请求才能凑齐。运营端的订单量是几十单量级，
 * 一次全量拉回来在内存里分组，比 5 次往返划算得多。
 * （服务端的 `?status=` 参数仍然可用，只是这一页用不上。）
 */
import { computed, onMounted, ref } from 'vue'
import { adminHandleRefund, adminListOrders, adminShipOrder } from '@/api/order'
import { ApiError } from '@/api/http'
import { useNotice } from '@/composables/useNotice'
import { when } from '@/utils/format'
import type { Order, OrderAction, OrderStatus } from '@/types'

/**
 * 2026-10-04：从一级页面「订单处理」降级为**农产品管理页里的一个 tab**。
 *
 * 订单是农产品这条业务线的末端（卖货 → 发货 → 售后），单独占一个一级入口
 * 会让"卖货"和"发货"分家。所以这里加 `embedded` 开关：内嵌时**不渲染页头**
 * （页面已经有了自己的标题与统计区间），其余逻辑一行没动 ——
 * 订单状态机是已验收的东西，重写一遍只会引入新 bug。
 */
withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })

const orders = ref<Order[]>([])
const loading = ref(true)
const busyId = ref<number | null>(null)

/** 操作提示条。逻辑在 composable 里，样式在下方（scoped style 进不了组合式函数） */
const { notice, say } = useNotice()

async function load() {
  loading.value = true
  try {
    orders.value = await adminListOrders()
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '订单加载失败')
    orders.value = []
  } finally {
    loading.value = false
  }
}
onMounted(load)

/* ============================================================
   标签
   ============================================================ */

type TabKey = 'ALL' | OrderStatus

/** 标签顺序按"运营关心的程度"排：要动手的在前 */
const TABS: { key: TabKey; label: string }[] = [
  { key: 'ALL', label: '全部' },
  { key: 'PENDING_SHIPMENT', label: '待发货' },
  { key: 'REFUND_REQUESTED', label: '退款中' },
  { key: 'PENDING_PAYMENT', label: '待付款' },
  { key: 'SHIPPED', label: '已发货' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'CANCELLED', label: '已取消' },
]

const tab = ref<TabKey>('ALL')
const filtered = computed(() =>
  tab.value === 'ALL' ? orders.value : orders.value.filter((o) => o.status === tab.value)
)
const countOf = (k: TabKey) =>
  k === 'ALL' ? orders.value.length : orders.value.filter((o) => o.status === k).length

/** 需要运营动手的总数：待发货 + 退款中 */
const todoCount = computed(
  () => orders.value.filter((o) => ['PENDING_SHIPMENT', 'REFUND_REQUESTED'].includes(o.status)).length
)

/* ============================================================
   发货
   ============================================================ */

const shipFor = ref<number | null>(null)
const shipForm = ref({ carrier: '', eta_days: 3, tracking_no: '' })
const shipError = ref('')

/** 常用快递公司，点一下填进去。省掉每次手打，也避免"顺丰"和"顺丰速运"两种写法混在一起 */
const CARRIERS = ['顺丰速运', '中通快递', '圆通速递', '韵达快递', '邮政EMS', '京东物流']

function openShip(o: Order) {
  shipFor.value = o.id
  shipError.value = ''
  // 天数给个合理默认值，其余清空 —— 预填上一次的单号会让运营
  // 顺手点提交，把 A 单的单号写到 B 单上
  shipForm.value = { carrier: '', eta_days: 3, tracking_no: '' }
}

async function submitShip(o: Order) {
  const carrier = shipForm.value.carrier.trim()
  const tracking = shipForm.value.tracking_no.trim()
  const days = Number(shipForm.value.eta_days)

  // 前端校验只为少一次往返，判定权在后端（同一套规则会再校验一次）
  if (!carrier) {
    shipError.value = '请填写快递公司'
    return
  }
  if (!Number.isInteger(days) || days < 1 || days > 30) {
    shipError.value = '预计到达天数请填 1–30 之间的整数'
    return
  }
  if (!tracking) {
    shipError.value = '请填写快递单号'
    return
  }

  shipError.value = ''
  await run(o.id, '发货', async () => {
    const saved = await adminShipOrder(o.id, {
      carrier,
      eta_days: days,
      tracking_no: tracking,
    })
    shipFor.value = null
    return saved
  })
}

/* ============================================================
   退款
   ============================================================ */

/** 正在处理的退款单：记录订单 id 与选定的处理方向 */
const refundFor = ref<{ id: number; approve: boolean } | null>(null)
const refundReply = ref('')
const refundError = ref('')

function openRefund(o: Order, approve: boolean) {
  refundFor.value = { id: o.id, approve }
  refundReply.value = ''
  refundError.value = ''
}

async function submitRefund(o: Order) {
  const cur = refundFor.value
  if (!cur) return
  const reply = refundReply.value.trim()
  // 拒绝时理由必填：用户看不到理由只会再申请一次，运营还要再拒一次
  if (!cur.approve && !reply) {
    refundError.value = '拒绝退款要写明理由，用户会看到它'
    return
  }
  refundError.value = ''
  await run(o.id, cur.approve ? '同意退款' : '拒绝退款', async () => {
    const saved = await adminHandleRefund(o.id, {
      approve: cur.approve,
      reply: reply || undefined,
    })
    refundFor.value = null
    return saved
  })
}

/* ============================================================
   统一的动作包装
   ============================================================ */

/**
 * 三件事必须一起做，少一件页面就会说谎：
 *   1. 用服务端回读的订单**整行替换**（状态一变，available_actions
 *      与时间戳都会跟着变，只改 status 会让按钮停留在上一组）；
 *   2. 失败时不假装成功；
 *   3. 成功后关掉展开的表单（由调用方在回调里做）。
 */
async function run(id: number, label: string, fn: () => Promise<Order>) {
  if (busyId.value !== null) return
  busyId.value = id
  try {
    const saved = await fn()
    const idx = orders.value.findIndex((x) => x.id === id)
    if (idx >= 0) orders.value[idx] = saved
    say('ok', `订单 ${saved.order_no} ${label}成功`)
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '操作失败，请重试')
  } finally {
    busyId.value = null
  }
}

/* ---------- 展示辅助 ---------- */

function statusTone(s: OrderStatus) {
  switch (s) {
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

/** 服务端算好的可做操作。运营端会出现 SHIP / HANDLE_REFUND */
const can = (o: Order, a: OrderAction) => o.available_actions.includes(a)
</script>

<template>
  <div class="aorders">
    <header v-if="!embedded" class="aorders__head">
      <div>
        <!--
          原稿写的是「消费与离境复购」。**"离境复购"这个说法已废弃**：
          当前复购率的口径是 `SUM(repurchases)/SUM(purchases)`（笔数比），
          统计表没有用户身份维度，算不出用户级复购，更谈不上"离境"。
          一个被否掉的词留在副标题里，比留在正文里更危险 —— 页头是答辩时会被念出来的。
        -->
        <span class="eyebrow">订单与售后</span>
        <h1 class="h1 aorders__title">订单处理</h1>
        <p class="aorders__sub">
          游客在「乡村好物」提交的订单都在这里。每一单的明细都带着下单时的体验锚点与产地快照 ——
          产品以后下架，这一单仍然说得清它来自哪次体验、哪个村。
        </p>
      </div>
      <div class="aorders__meta">
        <span v-if="todoCount > 0" class="badge-wait">待处理 {{ todoCount }}</span>
        <span v-else class="badge-sim">暂无待处理</span>
      </div>
    </header>

    <Transition name="notice">
      <div v-if="notice" class="notice" :class="`notice--${notice.type}`">{{ notice.text }}</div>
    </Transition>

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

    <div v-if="loading" class="stack-4">
      <div v-for="i in 3" :key="i" class="skeleton" style="height: 190px; border-radius: 10px" />
    </div>

    <div v-else-if="!filtered.length" class="empty">
      <div class="empty__title">
        {{ tab === 'ALL' ? '还没有订单' : '这个状态下没有订单' }}
      </div>
      <div class="empty__desc">游客在游客端「乡村好物」提交订单后会出现在这里。</div>
    </div>

    <div v-else class="olist">
      <article
        v-for="o in filtered"
        :key="o.id"
        class="ocard"
        :class="{ 'ocard--todo': can(o, 'SHIP') || can(o, 'HANDLE_REFUND') }"
      >
        <header class="ocard__head">
          <div class="ocard__ids">
            <span class="num ocard__no">{{ o.order_no }}</span>
            <span class="muted cap">{{ when(o.created_at) }}</span>
            <span class="ost" :class="statusTone(o.status)">
              <i class="ost__dot" />{{ o.status_label }}
            </span>
          </div>

          <!-- 发货：展开三要素表单 -->
          <button
            v-if="can(o, 'SHIP')"
            class="btn btn-gold btn-sm"
            :disabled="busyId !== null"
            @click="openShip(o)"
          >
            填写物流并发货
          </button>
          <!-- 退款：两个方向都要写处理说明，点哪个展开哪个 -->
          <div v-else-if="can(o, 'HANDLE_REFUND')" class="ocard__rf">
            <button
              class="btn btn-primary btn-sm"
              :disabled="busyId !== null"
              @click="openRefund(o, true)"
            >
              同意退款
            </button>
            <button
              class="btn btn-ghost btn-sm"
              :disabled="busyId !== null"
              @click="openRefund(o, false)"
            >
              拒绝退款
            </button>
          </div>
          <span v-else-if="o.shipped_at" class="muted cap">
            发货于 {{ when(o.shipped_at) }}
          </span>
        </header>

        <div class="ocard__body">
          <div class="ocol ocol--items">
            <span class="ocol__k">订单明细</span>
            <ul class="oitems">
              <li v-for="it in o.items" :key="it.id" class="oitem">
                <span class="oitem__name">{{ it.product_name }}</span>
                <span class="oitem__spec">{{ it.spec }} · × {{ it.quantity }}</span>
                <span class="oitem__anchor">
                  <template v-if="it.poi_id">
                    <router-link :to="`/poi/${it.poi_id}`" class="oitem__link">
                      产地 · {{ it.poi_name || it.poi_id }}
                    </router-link>
                  </template>
                  <template v-else>产地未标注</template>
                  <template v-if="it.experience_name">
                    <span class="oitem__sep">/</span>体验 · {{ it.experience_name }}
                  </template>
                </span>
                <span class="num oitem__amt">¥{{ it.subtotal }}</span>
              </li>
            </ul>
          </div>

          <div class="ocol ocol--ship">
            <span class="ocol__k">收货信息</span>
            <p class="ship__line">
              <span class="ship__name">{{ o.receiver_name }}</span>
              <span class="num ship__phone">{{ o.receiver_phone }}</span>
            </p>
            <p class="ship__addr">{{ o.receiver_address }}</p>
            <p v-if="o.remark" class="ship__remark">备注：{{ o.remark }}</p>

            <!-- 已发货的物流信息：运营自己填的，回显出来方便核对 -->
            <div v-if="o.carrier" class="ship__logi">
              <span class="ocol__k">物流</span>
              <p class="ship__logi-line">
                {{ o.carrier }} ·
                <span class="num">{{ o.tracking_no }}</span>
                <span v-if="o.eta_days" class="muted cap">（预计 {{ o.eta_days }} 天）</span>
              </p>
            </div>
          </div>

          <div class="ocol ocol--buyer">
            <span class="ocol__k">买家</span>
            <p class="buyer__name">{{ o.buyer_nickname || '—' }}</p>
            <p class="buyer__mail">{{ o.buyer_email || '' }}</p>
            <p class="muted cap">用户 #{{ o.user_id }}</p>
            <p v-if="o.paid_at" class="muted cap">付款于 {{ when(o.paid_at) }}</p>
          </div>
        </div>

        <!-- 退款申请详情 -->
        <div v-if="o.refund_at" class="rfb">
          <span class="rfb__k">退款申请</span>
          <span class="rfb__v">{{ o.refund_reason || '（未填原因）' }}</span>
          <span class="muted cap">{{ when(o.refund_at) }}</span>
          <template v-if="o.refund_reply">
            <span class="rfb__k">上次处理</span>
            <span class="rfb__v">{{ o.refund_reply }}</span>
          </template>
        </div>

        <!-- ---------- 发货表单 ---------- -->
        <div v-if="shipFor === o.id" class="form">
          <div class="form__grid">
            <div class="form__f">
              <label class="form__label" :for="`cr-${o.id}`">快递公司</label>
              <input
                :id="`cr-${o.id}`"
                v-model="shipForm.carrier"
                class="form__input"
                type="text"
                maxlength="32"
                placeholder="如：顺丰速运"
              />
              <div class="form__chips">
                <button
                  v-for="c in CARRIERS"
                  :key="c"
                  type="button"
                  class="chip"
                  :class="{ 'chip--on': shipForm.carrier === c }"
                  @click="shipForm.carrier = c"
                >
                  {{ c }}
                </button>
              </div>
            </div>
            <div class="form__f">
              <label class="form__label" :for="`et-${o.id}`">预计到达（天）</label>
              <input
                :id="`et-${o.id}`"
                v-model.number="shipForm.eta_days"
                class="form__input form__input--n"
                type="number"
                min="1"
                max="30"
              />
            </div>
            <div class="form__f form__f--wide">
              <label class="form__label" :for="`tk-${o.id}`">快递单号</label>
              <input
                :id="`tk-${o.id}`"
                v-model="shipForm.tracking_no"
                class="form__input num"
                type="text"
                maxlength="40"
                placeholder="如：SF1234567890"
              />
            </div>
          </div>
          <p v-if="shipError" class="form__err">{{ shipError }}</p>
          <div class="form__acts">
            <button
              class="btn btn-gold btn-sm"
              :disabled="busyId !== null"
              @click="submitShip(o)"
            >
              {{ busyId === o.id ? '提交中…' : '确认发货' }}
            </button>
            <button class="btn btn-ghost btn-sm" @click="shipFor = null">取消</button>
          </div>
        </div>

        <!-- ---------- 退款处理表单 ---------- -->
        <div v-if="refundFor && refundFor.id === o.id" class="form">
          <p class="form__head">
            正在处理退款：
            <b :class="refundFor.approve ? 'form__yes' : 'form__no'">
              {{ refundFor.approve ? '同意退款' : '拒绝退款' }}
            </b>
            <span v-if="refundFor.approve" class="muted cap">
              （订单将变成「已退款」终态）
            </span>
            <span v-else class="muted cap">
              （订单将退回申请前的状态，用户可再次申请）
            </span>
          </p>
          <label class="form__label" :for="`rp-${o.id}`">
            处理说明{{ refundFor.approve ? '（可空）' : '（必填，用户会看到）' }}
          </label>
          <textarea
            :id="`rp-${o.id}`"
            v-model="refundReply"
            class="form__ta"
            rows="2"
            maxlength="200"
            :placeholder="
              refundFor.approve ? '如：同意退款，款项原路返回' : '如：已出库，暂不支持退款'
            "
          />
          <p v-if="refundError" class="form__err">{{ refundError }}</p>
          <div class="form__acts">
            <button
              class="btn btn-sm"
              :class="refundFor.approve ? 'btn-primary' : 'btn-danger'"
              :disabled="busyId !== null"
              @click="submitRefund(o)"
            >
              {{ busyId === o.id ? '提交中…' : '确认' }}
            </button>
            <button class="btn btn-ghost btn-sm" @click="refundFor = null">取消</button>
          </div>
        </div>

        <footer class="ocard__foot">
          <span class="muted cap">渠道 {{ o.channel === 'REPURCHASE' ? '离境复购' : o.channel }}</span>
          <div class="ocard__totals">
            <span class="muted small">共 {{ o.item_count }} 件</span>
            <span class="num ocard__amount">¥{{ o.total_amount }}</span>
          </div>
        </footer>
      </article>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 页头 ---------- */
.aorders__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-5);
  margin-bottom: var(--sp-5);
}
.aorders__title {
  margin-top: var(--sp-2);
}
.aorders__sub {
  margin-top: var(--sp-3);
  max-width: 760px;
  font-size: var(--fs-sm);
  line-height: 1.85;
  color: var(--text-2);
}
.aorders__meta {
  display: flex;
  gap: var(--sp-2);
  flex: none;
}
.badge-wait {
  height: 24px;
  padding: 0 10px;
  display: inline-flex;
  align-items: center;
  border-radius: var(--r-sm);
  font-size: var(--fs-cap);
  font-weight: 600;
  color: #f7e6bd;
  background: rgba(168, 121, 29, 0.24);
  border: 1px solid rgba(192, 154, 78, 0.46);
}
.badge-sim {
  height: 24px;
  padding: 0 10px;
  display: inline-flex;
  align-items: center;
  border-radius: var(--r-sm);
  font-size: var(--fs-cap);
  color: var(--text-3);
  background: rgba(146, 178, 165, 0.1);
  border: 1px solid var(--line);
}

/* ---------- 提示条 ---------- */
.notice {
  margin-bottom: var(--sp-4);
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-md);
  font-size: var(--fs-sm);
  border: 1px solid transparent;
}
.notice--ok {
  color: #cfe8dc;
  background: rgba(42, 111, 91, 0.24);
  border-color: rgba(113, 169, 150, 0.42);
}
.notice--err {
  color: #f3d3ca;
  background: rgba(168, 64, 43, 0.22);
  border-color: rgba(168, 64, 43, 0.5);
}
.notice-enter-active,
.notice-leave-active {
  transition: opacity var(--dur-2) var(--ease);
}
.notice-enter-from,
.notice-leave-to {
  opacity: 0;
}

/* ---------- 标签页 ---------- */
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
  color: var(--text-2);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: all var(--dur-1) var(--ease);
}
.tab:hover {
  color: #fff;
}
.tab--on {
  color: #fff;
  font-weight: 600;
  border-bottom-color: var(--gold-500);
}
.tab__n {
  margin-left: var(--sp-2);
  padding: 1px 7px;
  border-radius: var(--r-pill);
  font-size: var(--fs-cap);
  background: rgba(146, 178, 165, 0.16);
  color: var(--text-2);
}

/* ---------- 订单卡 ---------- */
.olist {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.ocard {
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: rgba(18, 48, 38, 0.5);
  overflow: hidden;
  transition: border-color var(--dur-2) var(--ease);
}
/* 需要运营动手的单描一道金边：一屏里能立刻看出哪几张要处理 */
.ocard--todo {
  border-color: rgba(192, 154, 78, 0.4);
}
.ocard__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: var(--sp-3) var(--sp-5);
  background: rgba(11, 33, 25, 0.42);
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}
.ocard__ids {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.ocard__no {
  font-size: var(--fs-sm);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: #fff;
}
.ocard__rf {
  display: flex;
  gap: var(--sp-2);
}
.ost {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
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
  color: #f7e6bd;
  background: rgba(168, 121, 29, 0.22);
  border: 1px solid rgba(192, 154, 78, 0.42);
}
.ost--info {
  color: #bfd8ee;
  background: rgba(46, 123, 196, 0.2);
  border: 1px solid rgba(93, 154, 210, 0.42);
}
.ost--done {
  color: #cfe8dc;
  background: rgba(42, 111, 91, 0.24);
  border: 1px solid rgba(113, 169, 150, 0.42);
}
.ost--warn {
  color: #f3d3ca;
  background: rgba(168, 64, 43, 0.24);
  border: 1px solid rgba(200, 104, 80, 0.46);
}
.ost--muted {
  color: var(--text-3);
  background: rgba(146, 178, 165, 0.1);
  border: 1px solid var(--line);
}

.ocard__body {
  display: grid;
  grid-template-columns: 1.5fr 1.1fr 0.7fr;
  gap: var(--sp-5);
  padding: var(--sp-5);
}
.ocol {
  min-width: 0;
}
.ocol + .ocol {
  padding-left: var(--sp-5);
  border-left: 1px solid var(--line);
}
.ocol__k {
  display: block;
  margin-bottom: var(--sp-3);
  font-size: var(--fs-cap);
  letter-spacing: 0.1em;
  color: var(--text-3);
}

.oitems {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.oitem {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px var(--sp-4);
  align-items: baseline;
}
.oitem__name {
  font-size: var(--fs-sm);
  color: #fff;
}
.oitem__spec {
  font-size: var(--fs-cap);
  color: var(--text-3);
}
.oitem__anchor {
  grid-column: 1 / 2;
  font-size: var(--fs-cap);
  color: var(--text-2);
  line-height: 1.6;
}
.oitem__link {
  color: var(--gold-300);
}
.oitem__link:hover {
  text-decoration: underline;
}
.oitem__sep {
  margin: 0 4px;
  color: var(--text-3);
}
.oitem__amt {
  grid-column: 2 / 3;
  grid-row: 1 / 2;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--gold-300);
}

.ship__line {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.ship__name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: #fff;
}
.ship__phone {
  font-size: var(--fs-sm);
  color: var(--gold-300);
}
.ship__addr {
  margin-top: var(--sp-2);
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-2);
}
.ship__remark {
  margin-top: var(--sp-2);
  font-size: var(--fs-cap);
  line-height: 1.6;
  color: var(--text-3);
}
.ship__logi {
  margin-top: var(--sp-4);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line);
}
.ship__logi .ocol__k {
  margin-bottom: var(--sp-2);
}
.ship__logi-line {
  font-size: var(--fs-xs);
  color: var(--text-2);
}

.buyer__name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: #fff;
}
.buyer__mail {
  margin-top: 3px;
  font-size: var(--fs-cap);
  color: var(--text-2);
  word-break: break-all;
}
.ocol--buyer .muted {
  margin-top: var(--sp-2);
}

/* ---------- 退款申请条 ---------- */
.rfb {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  flex-wrap: wrap;
  padding: var(--sp-3) var(--sp-5);
  background: rgba(168, 64, 43, 0.14);
  border-top: 1px solid rgba(200, 104, 80, 0.32);
  font-size: var(--fs-xs);
}
.rfb__k {
  font-size: var(--fs-cap);
  letter-spacing: 0.08em;
  color: #d8a595;
}
.rfb__v {
  color: #f3d3ca;
}

/* ---------- 内联表单 ---------- */
.form {
  padding: var(--sp-4) var(--sp-5) var(--sp-5);
  background: rgba(11, 33, 25, 0.36);
  border-top: 1px solid var(--line);
}
.form__head {
  margin-bottom: var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--text-2);
}
.form__yes {
  color: #cfe8dc;
}
.form__no {
  color: #f3d3ca;
}
.form__grid {
  display: grid;
  grid-template-columns: 1.6fr 0.6fr;
  gap: var(--sp-4);
}
.form__f {
  min-width: 0;
}
.form__f--wide {
  grid-column: 1 / -1;
}
.form__label {
  display: block;
  margin-bottom: var(--sp-2);
  font-size: var(--fs-cap);
  letter-spacing: 0.06em;
  color: var(--text-3);
}
.form__input,
.form__ta {
  width: 100%;
  height: 34px;
  padding: 0 var(--sp-3);
  font-family: inherit;
  font-size: var(--fs-xs);
  color: #fff;
  background: rgba(11, 33, 25, 0.6);
  border: 1px solid var(--line-strong);
  border-radius: var(--r-md);
}
.form__ta {
  height: auto;
  padding: var(--sp-3);
  line-height: 1.7;
  resize: vertical;
}
.form__input:focus,
.form__ta:focus {
  outline: none;
  border-color: var(--gold-500);
}
.form__input--n {
  max-width: 120px;
}
.form__chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: var(--sp-2);
}
.chip {
  padding: 3px 9px;
  font-size: var(--fs-cap);
  color: var(--text-2);
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  transition: all var(--dur-1) var(--ease);
}
.chip:hover {
  color: #fff;
  border-color: var(--line-strong);
}
.chip--on {
  color: #3a2a0c;
  background: var(--gold-300);
  border-color: var(--gold-300);
}
.form__err {
  margin-top: var(--sp-3);
  font-size: var(--fs-cap);
  color: #f3d3ca;
}
.form__acts {
  display: flex;
  gap: var(--sp-3);
  margin-top: var(--sp-4);
}

.ocard__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: var(--sp-3) var(--sp-5);
  background: rgba(11, 33, 25, 0.3);
  border-top: 1px solid var(--line);
  flex-wrap: wrap;
}
.ocard__totals {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
}
.ocard__amount {
  font-size: 20px;
  font-weight: 700;
  color: var(--gold-300);
}

/* 管理端是深色表面，按钮的 ghost 变体要反色 */
.btn-ghost {
  color: var(--text-2);
  border-color: var(--line-strong);
}
.btn-ghost:hover {
  color: #fff;
  background: rgba(146, 178, 165, 0.14);
  border-color: var(--line-strong);
}
.btn-danger {
  color: #f3d3ca;
  border: 1px solid rgba(200, 104, 80, 0.5);
  background: rgba(168, 64, 43, 0.24);
}
.btn-danger:hover {
  color: #fff;
  background: rgba(168, 64, 43, 0.4);
}

@media (max-width: 1180px) {
  .ocard__body {
    grid-template-columns: 1fr 1fr;
  }
  .ocol--buyer {
    grid-column: 1 / -1;
    padding-left: 0;
    border-left: none;
    padding-top: var(--sp-4);
    border-top: 1px solid var(--line);
  }
}
@media (max-width: 720px) {
  .aorders__head {
    flex-direction: column;
  }
  .ocard__body {
    grid-template-columns: 1fr;
    padding: var(--sp-4);
  }
  .ocol + .ocol {
    padding-left: 0;
    border-left: none;
    padding-top: var(--sp-4);
    border-top: 1px solid var(--line);
  }
  .ocard__head,
  .ocard__foot,
  .form,
  .rfb {
    padding-left: var(--sp-4);
    padding-right: var(--sp-4);
  }
  .form__grid {
    grid-template-columns: 1fr;
  }
}
</style>
