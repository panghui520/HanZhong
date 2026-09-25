<script setup lang="ts">
/**
 * 我的订单（M6）
 *
 * 只有两个状态：待发货 → 已发货。没有支付中、没有配送中、没有已完成 ——
 * 那些状态需要真实的支付与物流通道才成立，凭空加出来只会在答辩时
 * 被追问"这个状态是怎么流转的"。
 *
 * 订单行上刻意显示**体验锚点与产地**（下单时的快照）：产品以后下架了，
 * 这一单仍然说得清"这件东西来自哪次体验、哪个村"。这是第一条红线
 * 在用户可见界面上最直接的一次体现。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getMyOrders } from '@/api/order'
import { ApiError } from '@/api/http'
import { useSessionStore } from '@/stores/session'
import type { Order } from '@/types'

const route = useRoute()
const session = useSessionStore()

const orders = ref<Order[]>([])
const loading = ref(true)
const error = ref('')

/** 下单成功跳回来时带的单号，用于顶部提示 */
const createdNo = computed(() => (typeof route.query.created === 'string' ? route.query.created : ''))

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
  }
}
onMounted(load)

/** 时间戳显示：只到分钟。秒对用户没意义，反而让列表看起来更乱 */
function when(s?: string) {
  if (!s) return ''
  const t = s.replace('T', ' ')
  return t.length >= 16 ? t.slice(0, 16) : t
}

function statusClass(o: Order) {
  return o.status === 'SHIPPED' ? 'ost--done' : 'ost--wait'
}

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
              每一单都记着它来自哪次体验、哪个村。状态只有「待发货 / 已发货」两态。
            </p>
          </div>
          <router-link to="/goods" class="btn btn-ghost btn-sm">继续挑好物</router-link>
        </header>

        <!-- 下单成功提示 -->
        <div v-if="createdNo && !loading" class="okbar">
          <div class="okbar__main">
            <span class="okbar__t">下单成功</span>
            <span class="okbar__d">
              单号 <b class="num">{{ createdNo }}</b> 已进入待发货，运营会尽快处理。
            </span>
          </div>
          <router-link to="/goods" class="btn btn-ghost btn-sm">再挑几样</router-link>
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

        <div v-else class="olist">
          <article
            v-for="o in orders"
            :key="o.id"
            class="ocard"
            :class="{ 'ocard--new': o.order_no === createdNo }"
          >
            <header class="ocard__head">
              <div class="ocard__ids">
                <span class="num ocard__no">{{ o.order_no }}</span>
                <span class="muted cap">{{ when(o.created_at) }}</span>
              </div>
              <span class="ost" :class="statusClass(o)">
                <i class="ost__dot" />{{ o.status_label }}
              </span>
            </header>

            <ul class="oitems">
              <li v-for="it in o.items" :key="it.id" class="oitem">
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
            </ul>

            <footer class="ocard__foot">
              <div class="ocard__ship">
                <span class="ocard__ship-k">收货</span>
                <span class="ocard__ship-v">
                  {{ o.receiver_name }} · {{ o.receiver_phone }}
                </span>
                <span class="ocard__ship-addr">{{ o.receiver_address }}</span>
                <span v-if="o.remark" class="ocard__remark">备注：{{ o.remark }}</span>
              </div>
              <div class="ocard__totals">
                <span class="muted small">共 {{ o.item_count }} 件</span>
                <span class="num ocard__amount">¥{{ o.total_amount }}</span>
                <span v-if="o.shipped_at" class="muted cap">发货于 {{ when(o.shipped_at) }}</span>
              </div>
            </footer>
          </article>
        </div>
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
  margin-bottom: var(--sp-6);
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

.okbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: var(--sp-4) var(--sp-5);
  margin-bottom: var(--sp-5);
  background: var(--ok-50);
  border: 1px solid var(--brand-100);
  border-left: 3px solid var(--brand-500);
  border-radius: var(--r-md);
  flex-wrap: wrap;
}
.okbar__main {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.okbar__t {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--brand-700);
}
.okbar__d {
  font-size: var(--fs-xs);
  color: var(--ink-600);
}
.okbar__d b {
  color: var(--ink-900);
}

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
.ocard--new {
  border-color: var(--brand-300);
  box-shadow: var(--sh-brand);
}
.ocard__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: var(--sp-4) var(--sp-5);
  background: var(--paper-2);
  border-bottom: 1px solid var(--line-soft);
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
.ost--done {
  color: var(--brand-700);
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
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

.ocard__foot {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-5);
  padding: var(--sp-4) var(--sp-5);
  background: var(--paper-2);
  border-top: 1px solid var(--line-soft);
  flex-wrap: wrap;
}
.ocard__ship {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: var(--fs-xs);
  color: var(--ink-600);
  min-width: 0;
}
.ocard__ship-k {
  font-size: var(--fs-cap);
  letter-spacing: 0.08em;
  color: var(--warm-500);
}
.ocard__ship-v {
  color: var(--ink-700);
  font-weight: 500;
}
.ocard__ship-addr {
  color: var(--ink-500);
  line-height: 1.6;
}
.ocard__remark {
  color: var(--warm-500);
}
.ocard__totals {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}
.ocard__amount {
  font-size: 22px;
  font-weight: 700;
  color: var(--gold-600);
}

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
  .ocard__foot {
    padding-left: var(--sp-4);
    padding-right: var(--sp-4);
  }
  .ocard__totals {
    align-items: flex-start;
  }
}
</style>
