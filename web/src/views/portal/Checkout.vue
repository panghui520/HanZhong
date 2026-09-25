<script setup lang="ts">
/**
 * 结算（M6）
 *
 * ============================================================
 * 关键设计：只填收货信息，不选商品、不填金额
 * ============================================================
 * 后端下单接口只接受 `receiver_name / receiver_phone / receiver_address / remark`。
 * 买什么从服务端购物车读，多少钱由服务端按库里价格算。
 *
 * 前端这一屏因此**没有任何价格入参**：下面显示的合计只是给用户看的，
 * 它和最终成交金额没有因果关系。这是刻意的 —— 只要前端能传单价，
 * 抓个包改成 0.01 就能成交。
 *
 * ============================================================
 * 为什么把地址快照进订单，而不是建地址簿
 * ============================================================
 * 地址簿是"复购很多次"才划算的设计，本项目用户大概率只买一次。
 * 更关键的是**快照语义**：如果订单引用地址簿的 id，用户后来改了收货地址，
 * 历史订单上显示的地址会跟着变 —— 那已经是错账了。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SceneArt from '@/components/SceneArt.vue'
import { createOrder, getCart } from '@/api/order'
import { ApiError } from '@/api/http'
import { useCartStore } from '@/stores/cart'
import { useSessionStore } from '@/stores/session'
import { sceneVariant } from '@/utils/scene'
import type { CartItem } from '@/types'

const router = useRouter()
const route = useRoute()
const session = useSessionStore()
const cart = useCartStore()

const items = ref<CartItem[]>([])
const loading = ref(true)
const loadError = ref('')
const submitting = ref(false)
const submitError = ref('')

const form = reactive({
  receiver_name: '',
  receiver_phone: '',
  receiver_address: '',
  remark: '',
})
/** 字段级错误。提交时才算，不在输入过程中一直报红 */
const errors = reactive<Record<string, string>>({})

onMounted(async () => {
  if (!session.isLoggedIn) {
    loading.value = false
    return
  }
  try {
    const all = await getCart()
    // 失效商品不该出现在结算页 —— 购物车已经拦了一道，这里再兜一次：
    // 用户可能在另一个标签页里把某件商品放进了购物车，这边是旧快照。
    items.value = all.filter((i) => i.available)
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})

const totalQty = computed(() => items.value.reduce((s, i) => s + i.quantity, 0))
const totalAmount = computed(() => items.value.reduce((s, i) => s + (i.subtotal ?? 0), 0))

/** 按体验锚点分组显示，与购物车保持一致的分组维度 */
const groups = computed(() => {
  const map = new Map<string, { title: string; poiId?: string; poiName?: string; rows: CartItem[] }>()
  for (const it of items.value) {
    const key = it.experience_id || it.poi_id || 'UNKNOWN'
    let g = map.get(key)
    if (!g) {
      g = {
        title: it.experience_name || (it.poi_name ? `${it.poi_name} · 产地直供` : '未标注来源'),
        poiId: it.poi_id,
        poiName: it.poi_name,
        rows: [],
      }
      map.set(key, g)
    }
    g.rows.push(it)
  }
  return [...map.entries()].map(([key, v]) => ({ key, ...v }))
})

/**
 * 手机号规则与后端保持一致：11 位、1 开头、第二位 3-9。
 * 前端校验只是为了少一次往返，**判定权在后端**（后端用同一套正则再校验一次）。
 */
const PHONE = /^1[3-9]\d{9}$/

function validate(): boolean {
  errors.receiver_name = form.receiver_name.trim() ? '' : '请填写收货人姓名'
  const phone = form.receiver_phone.trim()
  errors.receiver_phone = !phone
    ? '请填写手机号'
    : PHONE.test(phone)
      ? ''
      : '请填写 11 位手机号'
  errors.receiver_address = form.receiver_address.trim().length >= 6 ? '' : '请填写详细收货地址（含省市区与门牌）'
  return !errors.receiver_name && !errors.receiver_phone && !errors.receiver_address
}

async function submit() {
  if (submitting.value) return
  submitError.value = ''
  if (!validate()) return
  if (!items.value.length) {
    submitError.value = '购物车是空的，先去挑几样'
    return
  }

  submitting.value = true
  try {
    const order = await createOrder({
      receiver_name: form.receiver_name.trim(),
      receiver_phone: form.receiver_phone.trim(),
      receiver_address: form.receiver_address.trim(),
      remark: form.remark.trim() || undefined,
    })
    // 下单成功后购物车已被服务端清空，角标要跟着归零
    await cart.refresh()
    // ★ 直接落到**订单详情**而不是订单列表：下单后进入的是「待付款」，
    // 用户此刻唯一想做的事就是付款，把他丢到列表里再找一次"付款"按钮
    // 是多余的一步。详情页有倒计时，也能说清"超时会自动取消"。
    void router.replace({ path: `/orders/${order.id}`, query: { created: '1' } })
  } catch (e) {
    submitError.value = e instanceof ApiError ? e.message : '提交失败，请重试'
  } finally {
    submitting.value = false
  }
}

const backToCart = () => router.push('/cart')
/** 从购物车返回时保留原意：没有商品就别停在结算页 */
const nothingToPay = computed(() => !loading.value && session.isLoggedIn && !items.value.length)
const goGoods = () => router.push('/goods')
const loginHref = computed(() => ({ path: '/login', query: { redirect: route.fullPath } }))
</script>

<template>
  <div class="checkout">
    <div class="container section">
      <!-- 未登录 -->
      <div v-if="!session.isLoggedIn" class="empty empty--tall">
        <div class="empty__title">请先登录再结算</div>
        <div class="empty__desc">订单要挂在账号上，发货信息与历史订单都跟着账号走。</div>
        <router-link :to="loginHref" class="btn btn-primary" style="margin-top: 20px">
          去登录
        </router-link>
      </div>

      <template v-else>
        <header class="cohead">
          <div>
            <span class="eyebrow">离境复购</span>
            <h1 class="h1 cohead__title">填写收货信息</h1>
            <p class="cohead__desc">
              运营会按这里的信息发货。地址会作为快照写进这一单，以后修改不影响历史订单。
            </p>
          </div>
          <button class="btn btn-ghost btn-sm" @click="backToCart">返回购物车</button>
        </header>

        <div v-if="loading" class="skeleton" style="height: 420px; border-radius: 10px" />

        <div v-else-if="loadError" class="state-error">
          <p>{{ loadError }}</p>
          <button class="btn btn-ghost btn-sm" @click="backToCart">返回购物车</button>
        </div>

        <div v-else-if="nothingToPay" class="empty empty--tall">
          <div class="empty__title">没有可结算的商品</div>
          <div class="empty__desc">购物车是空的，或者里面的商品都已下架。</div>
          <button class="btn btn-primary" style="margin-top: 20px" @click="goGoods">
            去挑好物
          </button>
        </div>

        <div v-else class="co">
          <!-- ============ 左：表单 ============ -->
          <form class="form" novalidate @submit.prevent="submit">
            <h2 class="h3 form__title">收货信息</h2>

            <div class="field">
              <label class="field__label" for="rn">收货人</label>
              <input
                id="rn"
                v-model="form.receiver_name"
                class="field__input"
                type="text"
                maxlength="64"
                placeholder="请填写真实姓名"
                autocomplete="name"
              />
              <p v-if="errors.receiver_name" class="field__err">{{ errors.receiver_name }}</p>
            </div>

            <div class="field">
              <label class="field__label" for="rp">联系电话</label>
              <input
                id="rp"
                v-model="form.receiver_phone"
                class="field__input"
                type="tel"
                maxlength="11"
                inputmode="numeric"
                placeholder="11 位手机号"
                autocomplete="tel"
              />
              <p v-if="errors.receiver_phone" class="field__err">{{ errors.receiver_phone }}</p>
            </div>

            <div class="field">
              <label class="field__label" for="ra">收货地址</label>
              <textarea
                id="ra"
                v-model="form.receiver_address"
                class="field__input field__input--area"
                rows="3"
                maxlength="255"
                placeholder="省 / 市 / 区县 + 街道门牌，例如：陕西省汉中市汉台区中山街 12 号 3 单元 501"
                autocomplete="street-address"
              />
              <p v-if="errors.receiver_address" class="field__err">{{ errors.receiver_address }}</p>
            </div>

            <div class="field">
              <label class="field__label" for="rm">
                备注 <span class="field__opt">选填</span>
              </label>
              <input
                id="rm"
                v-model="form.remark"
                class="field__input"
                type="text"
                maxlength="255"
                placeholder="如：希望周末送达、需要礼盒包装"
              />
            </div>

            <div class="form__note">
              <span class="form__note-k">为什么没有支付方式</span>
              <p>
                本演示系统不接入在线支付与物流通道。提交后订单进入「待发货」，
                由运营在管理端统一处理 —— 状态只有待发货 / 已发货两态。
              </p>
            </div>
          </form>

          <!-- ============ 右：订单摘要 ============ -->
          <aside class="summary">
            <h2 class="h3 summary__title">订单摘要</h2>

            <div v-for="g in groups" :key="g.key" class="sgroup">
              <div class="sgroup__head">
                <span class="sgroup__k">体验锚点</span>
                <span class="sgroup__t">{{ g.title }}</span>
              </div>
              <router-link v-if="g.poiId" :to="`/poi/${g.poiId}`" class="sgroup__poi">
                产地 · {{ g.poiName || g.poiId }} →
              </router-link>

              <ul class="srows">
                <li v-for="it in g.rows" :key="it.id" class="srow">
                  <div class="srow__art">
                    <SceneArt :variant="sceneVariant(it.scene, 'terrace')" ratio="1 / 1" />
                  </div>
                  <div class="srow__main">
                    <span class="srow__name">{{ it.name }}</span>
                    <span class="muted cap">{{ it.spec }} · × {{ it.quantity }}</span>
                  </div>
                  <span class="num srow__amt">¥{{ it.subtotal }}</span>
                </li>
              </ul>
            </div>

            <div class="summary__total">
              <span class="muted small">共 {{ totalQty }} 件，合计</span>
              <span class="num summary__amount">¥{{ totalAmount.toFixed(2) }}</span>
            </div>

            <p v-if="submitError" class="summary__err">{{ submitError }}</p>

            <button
              class="btn btn-primary btn-lg summary__submit"
              :disabled="submitting"
              @click="submit"
            >
              {{ submitting ? '提交中…' : '提交订单' }}
            </button>
            <p class="summary__hint">提交后可在「我的订单」查看状态与发货情况。</p>
          </aside>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.cohead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-6);
  padding-bottom: var(--sp-5);
  border-bottom: 1px solid var(--line-soft);
  margin-bottom: var(--sp-7);
  flex-wrap: wrap;
}
.cohead__title {
  margin-top: var(--sp-3);
  color: var(--ink-900);
}
.cohead__desc {
  margin-top: var(--sp-3);
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: var(--ink-500);
  max-width: 52em;
}

.co {
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  gap: var(--sp-7);
  align-items: start;
}

/* ---------- 表单 ---------- */
.form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
  padding: var(--sp-6);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
}
.form__title {
  color: var(--ink-900);
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field__label {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-700);
}
.field__opt {
  font-weight: 400;
  color: var(--warm-500);
  font-size: var(--fs-cap);
}
.field__input {
  width: 100%;
  padding: 10px var(--sp-3);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--paper);
  font-family: var(--font-sans);
  font-size: var(--fs-sm);
  color: var(--ink-900);
  transition: border-color var(--dur-1) var(--ease), box-shadow var(--dur-1) var(--ease);
}
.field__input::placeholder {
  color: var(--warm-400);
}
.field__input:focus {
  outline: none;
  border-color: var(--brand-500);
  background: #fff;
  box-shadow: 0 0 0 3px var(--brand-50);
}
.field__input--area {
  resize: vertical;
  line-height: 1.7;
}
.field__err {
  font-size: var(--fs-cap);
  color: var(--danger);
}

.form__note {
  padding: var(--sp-4);
  background: var(--paper-2);
  border-left: 2px solid var(--gold-500);
  border-radius: 0 var(--r-md) var(--r-md) 0;
}
.form__note-k {
  font-size: var(--fs-cap);
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--gold-600);
}
.form__note p {
  margin-top: 6px;
  font-size: var(--fs-xs);
  line-height: 1.75;
  color: var(--ink-500);
}

/* ---------- 摘要 ---------- */
.summary {
  position: sticky;
  top: calc(var(--nav-h) + var(--sp-4));
  padding: var(--sp-6);
  background: var(--paper-2);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
}
.summary__title {
  color: var(--ink-900);
  margin-bottom: var(--sp-5);
}
.sgroup + .sgroup {
  margin-top: var(--sp-5);
  padding-top: var(--sp-5);
  border-top: 1px solid var(--line);
}
.sgroup__head {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.sgroup__k {
  font-size: var(--fs-cap);
  letter-spacing: 0.08em;
  color: var(--warm-500);
  padding: 2px 7px;
  background: var(--gold-50);
  border: 1px solid var(--gold-300);
  border-radius: var(--r-sm);
}
.sgroup__t {
  font-family: var(--font-display);
  font-size: 16px;
  color: var(--ink-900);
}
.sgroup__poi {
  display: inline-block;
  margin-top: 5px;
  font-size: var(--fs-cap);
  color: var(--brand-600);
}
.sgroup__poi:hover {
  text-decoration: underline;
}
.srows {
  list-style: none;
  margin: var(--sp-3) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.srow {
  display: grid;
  grid-template-columns: 44px 1fr auto;
  align-items: center;
  gap: var(--sp-3);
}
.srow__art {
  border-radius: var(--r-sm);
  overflow: hidden;
}
.srow__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.srow__name {
  font-size: var(--fs-sm);
  color: var(--ink-700);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.srow__amt {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-900);
}

.summary__total {
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--line);
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-4);
}
.summary__amount {
  font-size: 28px;
  font-weight: 700;
  color: var(--gold-600);
}
.summary__submit {
  width: 100%;
  margin-top: var(--sp-5);
}
.summary__hint {
  margin-top: var(--sp-3);
  font-size: var(--fs-cap);
  color: var(--warm-500);
  text-align: center;
}
.summary__err {
  margin-top: var(--sp-4);
  padding: 10px var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--danger);
  background: var(--danger-50);
  border: 1px solid rgba(168, 64, 43, 0.24);
  border-radius: var(--r-md);
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

@media (max-width: 1080px) {
  .co {
    grid-template-columns: 1fr;
    gap: var(--sp-6);
  }
  .summary {
    position: static;
  }
}
</style>
