<script setup lang="ts">
/**
 * 订单处理（M6 运营端）
 *
 * 只做一件事：把「待发货」改成「已发货」。
 *
 * **刻意不做物流单号、批量导出、取消退款。** 那些需要真实的物流与支付通道
 * 才成立，摆一个填不进任何东西的输入框，在答辩时只会被追问
 * "这个单号填进去之后干什么"。留作扩展点，写进验收记录。
 *
 * 两个容易忽略但真实存在的细节：
 *   - 列表默认返回「待发货优先 + 时间倒序」，因为运营进这一页就是来处理待发货的；
 *   - 标记发货是**幂等**的（服务端校验当前必须是待发货），所以按钮双击不会
 *     把发货时间刷成第二次的时间 —— 按钮挨得近，双击是常态。
 */
import { onMounted, ref } from 'vue'
import { adminListOrders, adminShipOrder } from '@/api/order'
import { ApiError } from '@/api/http'
import type { Order } from '@/types'

type TabKey = 'ALL' | 'PENDING' | 'SHIPPED'

const tab = ref<TabKey>('ALL')
const orders = ref<Order[]>([])
const loading = ref(true)
const busyId = ref<number | null>(null)
const notice = ref<{ type: 'ok' | 'err'; text: string } | null>(null)

let noticeTimer: number | undefined
function say(type: 'ok' | 'err', text: string) {
  notice.value = { type, text }
  if (noticeTimer !== undefined) window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => (notice.value = null), 3500)
}

async function load() {
  loading.value = true
  try {
    orders.value = await adminListOrders(tab.value === 'ALL' ? undefined : tab.value)
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '订单加载失败')
    orders.value = []
  } finally {
    loading.value = false
  }
}
onMounted(load)

function switchTab(k: TabKey) {
  if (tab.value === k) return
  tab.value = k
  void load()
}

/** 数量：由服务端过滤，但角标要显示"待发货有几单"，所以单独发一次全量统计 */
const pendingCount = ref(0)
async function loadPendingCount() {
  try {
    pendingCount.value = (await adminListOrders('PENDING')).length
  } catch {
    pendingCount.value = 0
  }
}
onMounted(loadPendingCount)

async function ship(o: Order) {
  if (busyId.value !== null) return
  busyId.value = o.id
  try {
    const saved = await adminShipOrder(o.id)
    // 整行替换：保留买家昵称等只有服务端才有的字段
    const idx = orders.value.findIndex((x) => x.id === o.id)
    if (idx >= 0) {
      if (tab.value === 'PENDING') {
        // 待发货视图下，发完这一单就不该再留在列表里
        orders.value.splice(idx, 1)
      } else {
        orders.value[idx] = saved
      }
    }
    pendingCount.value = Math.max(0, pendingCount.value - 1)
    say('ok', `订单 ${o.order_no} 已标记为已发货`)
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '操作失败，请重试')
  } finally {
    busyId.value = null
  }
}

function when(s?: string) {
  if (!s) return ''
  const t = s.replace('T', ' ')
  return t.length >= 16 ? t.slice(0, 16) : t
}
</script>

<template>
  <div class="aorders">
    <header class="aorders__head">
      <div>
        <span class="eyebrow">消费与离境复购</span>
        <h1 class="h1 aorders__title">订单处理</h1>
        <p class="aorders__sub">
          游客在「乡村好物」提交的订单都在这里。每一单的明细都带着下单时的体验锚点与产地快照 ——
          产品以后下架，这一单仍然说得清它来自哪次体验、哪个村。
        </p>
      </div>
      <div class="aorders__meta">
        <span v-if="pendingCount > 0" class="badge-wait">待发货 {{ pendingCount }}</span>
        <span v-else class="badge-sim">暂无待发货</span>
      </div>
    </header>

    <Transition name="notice">
      <div v-if="notice" class="notice" :class="`notice--${notice.type}`">{{ notice.text }}</div>
    </Transition>

    <div class="tabs">
      <button class="tab" :class="{ 'tab--on': tab === 'ALL' }" @click="switchTab('ALL')">
        全部
        <span class="tab__n">{{ tab === 'ALL' ? orders.length : '—' }}</span>
      </button>
      <button class="tab" :class="{ 'tab--on': tab === 'PENDING' }" @click="switchTab('PENDING')">
        待发货
        <span class="tab__n">{{ tab === 'PENDING' ? orders.length : pendingCount }}</span>
      </button>
      <button class="tab" :class="{ 'tab--on': tab === 'SHIPPED' }" @click="switchTab('SHIPPED')">
        已发货
        <span class="tab__n">{{ tab === 'SHIPPED' ? orders.length : '—' }}</span>
      </button>
    </div>

    <div v-if="loading" class="stack-4">
      <div v-for="i in 3" :key="i" class="skeleton" style="height: 190px; border-radius: 10px" />
    </div>

    <div v-else-if="!orders.length" class="empty">
      <div class="empty__title">
        {{ tab === 'PENDING' ? '没有待发货的订单' : '还没有订单' }}
      </div>
      <div class="empty__desc">
        游客在游客端「乡村好物」提交订单后会出现在这里。
      </div>
    </div>

    <div v-else class="olist">
      <article v-for="o in orders" :key="o.id" class="ocard" :class="{ 'ocard--pending': o.status === 'PENDING' }">
        <header class="ocard__head">
          <div class="ocard__ids">
            <span class="num ocard__no">{{ o.order_no }}</span>
            <span class="muted cap">{{ when(o.created_at) }}</span>
            <span class="ost" :class="o.status === 'SHIPPED' ? 'ost--done' : 'ost--wait'">
              <i class="ost__dot" />{{ o.status_label }}
            </span>
          </div>
          <button
            v-if="o.status === 'PENDING'"
            class="btn btn-gold btn-sm"
            :disabled="busyId !== null"
            @click="ship(o)"
          >
            {{ busyId === o.id ? '处理中…' : '标记已发货' }}
          </button>
          <span v-else class="muted cap">发货于 {{ when(o.shipped_at) }}</span>
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
          </div>

          <div class="ocol ocol--buyer">
            <span class="ocol__k">买家</span>
            <p class="buyer__name">{{ o.buyer_nickname || '—' }}</p>
            <p class="buyer__mail">{{ o.buyer_email || '' }}</p>
            <p class="muted cap">用户 #{{ o.user_id }}</p>
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
.ocard--pending {
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
.ost--done {
  color: #cfe8dc;
  background: rgba(42, 111, 91, 0.24);
  border: 1px solid rgba(113, 169, 150, 0.42);
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
  .ocard__foot {
    padding-left: var(--sp-4);
    padding-right: var(--sp-4);
  }
}
</style>
