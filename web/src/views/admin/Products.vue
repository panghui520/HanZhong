<script setup lang="ts">
/**
 * 农产品管理（原「资源管理 → 农产品」tab + 「订单处理」一级页面的合并）
 *
 * ============================================================
 * 为什么把订单并进来
 * ============================================================
 * 订单是农产品这条业务线的末端：卖货 → 发货 → 售后。原来它独占一个一级入口，
 * 结果"哪些货卖得好"和"哪些货还没发"分在两个页面，运营要来回跳。
 * 现在合成一个页面两个 tab，数据层共用一个统计区间。
 *
 * 订单**状态机一行没改**（`OrdersPanel.vue` 就是原来的 Orders.vue，
 * 只加了一个"内嵌时不渲染页头"的开关）。重写状态机只会引入新 bug。
 *
 * ============================================================
 * 数据口径（这是本页最容易讲错的地方）
 * ============================================================
 * 本页同时存在**两套销售数字，它们不是一回事，页面文案必须分清**：
 *
 *   1. **产地级销售额**（图表区，仿真数据）
 *      = 该产地 `poi_visit_stats.purchases` × 该产地产品均价。
 *      与驾驶舱的 `product_sales` **同一个数**（后端复用同一批算法）。
 *      覆盖全部 10 个乡村产地、3243 笔购买。
 *
 *   2. **实际订单商品明细**（次要区，运行期真实数据）
 *      = `order_item` 表里的真实下单记录，几十条，覆盖 6 个产品。
 *
 * 前者是"这一片乡村的销售盘子"，后者是"演示期间真实发生过的那几单"。
 * 两者**数量级差很多**，页面上必须各自标明来源，不能混在一张图里 ——
 * 混了之后答辩时被问"这 4 万块是哪来的"就答不上来。
 *
 * 页面按 §四 的约定统一四档统计区间（今日 / 近 7 日 / 近 30 日 / 全部），
 * 下钻进本页时携带 `?range=`。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import EChart from '@/components/EChart.vue'
import ChartCard from '@/components/admin/ChartCard.vue'
import KpiBar from '@/components/admin/KpiBar.vue'
import type { KpiItem } from '@/components/admin/kpi'
import RangeSwitch from '@/components/admin/RangeSwitch.vue'
import ResourceFormDrawer from '@/components/admin/ResourceFormDrawer.vue'
import ResourceImagesDrawer from '@/components/admin/ResourceImagesDrawer.vue'
import ResourceTable from '@/components/admin/ResourceTable.vue'
import ResourceToolbar from '@/components/admin/ResourceToolbar.vue'
import OrdersPanel from '@/components/admin/OrdersPanel.vue'
import { adminListOrders } from '@/api/order'
import { useOpsBusiness } from '@/composables/useOpsBusiness'
import { useResourceList, type ResourceRow } from '@/composables/useResourceList'
import {
  areaLineOption,
  doughnutOption,
  groupBarOption,
  hBarOption,
  shareStackOption,
  fmtMoney,
} from '@/utils/adminCharts'
import type { AdminProduct, Order } from '@/types'

const route = useRoute()

// ============================================================
// 页面 tab：经营概览 / 订单处理
// ============================================================
type TabKey = 'overview' | 'orders'
const TABS: { key: TabKey; label: string }[] = [
  { key: 'overview', label: '经营概览' },
  { key: 'orders', label: '订单处理' },
]
/** 深链 `?tab=orders`（驾驶舱的「乡村复购率」卡会带过来） */
const tab = ref<TabKey>(route.query.tab === 'orders' ? 'orders' : 'overview')

// ============================================================
// 运营数据（四档统计区间）
// ============================================================
const { range, data, loading, switching, error, load, pickRange } = useOpsBusiness('product')

const kpiItems = computed<KpiItem[]>(() => {
  const d = data.value
  const base: KpiItem[] = (d?.kpis ?? []).map((k) => ({
    key: k.key,
    label: k.label,
    value: k.value,
    unit: k.unit,
    hint:
      k.key === 'repurchase'
        ? '口径：复购笔数 ÷ 购买笔数。统计表是「资源点 × 天」的汇总，没有用户身份维度，算不出用户级复购率。'
        : k.key === 'sales'
          ? '产地级口径：该产地购买笔数 × 该产地产品均价。与驾驶舱的农产品销售额是同一个数。'
          : undefined,
  }))
  // 待发货来自**真实订单**，不是仿真统计 —— 它必须挂在订单 tab 上，
  // 否则用户会以为这个数字跟着上面的统计区间变（它不跟着变）
  base.push({
    key: 'pending_ship',
    label: '待发货订单',
    value: String(pendingShip.value),
    unit: '单',
    hint: '来自真实订单表（运行期数据），不受上方统计区间影响。',
    to: { path: '/admin/products', query: { tab: 'orders' } },
  })
  return base
})

const periodNote = computed(() =>
  data.value ? `基准区间 ${data.value.range_from} ~ ${data.value.range_to}` : ''
)

// ---- 图 1：乡村产地销售额排行（横向条，¥ 直显）----
const rankOption = computed(() => hBarOption(data.value?.top ?? [], { money: true, color: '#3d8b74' }))
/** 图 2：产地销售贡献（100% 堆叠条，看份额） */
const shareOption = computed(() => shareStackOption(data.value?.top ?? [], { money: true }))
/** 图 3：销售结构（环，按产品分类） */
const structureOption = computed(() =>
  doughnutOption(data.value?.distribution ?? [], { money: true, centerLabel: '销售额' })
)
/** 图 4：销售额趋势（线 + 面） */
const trendOption = computed(() =>
  areaLineOption(
    (data.value?.trend ?? []).map((t) => ({ date: t.date, value: t.sales })),
    { money: true, color: '#c09a4e' }
  )
)
/** 图 5：购买 vs 复购（双系列柱，看比例） */
const buyRepOption = computed(() => {
  const t = data.value?.trend ?? []
  return groupBarOption(
    t.map((x) => x.date),
    [
      { name: '购买笔数', values: t.map((x) => x.purchases), color: '#2a6f5b' },
      { name: '复购笔数', values: t.map((x) => x.repurchases), color: '#c09a4e' },
    ],
    { unit: ' 笔' }
  )
})

// ============================================================
// 图表点击 → 表格下钻
// ============================================================
/**
 * 点产地 → 只看这个产地的农产品；点分类 → 只看这个分类。
 *
 * 比"高亮某一行"更实用：农产品表里的一行是**产品**，而图上的一项是
 * **产地 / 分类** —— 两者不是一对一，高亮无从谈起。改成下钻，
 * 点一下就能看到"这个产地有哪些货在卖"，这是运营真正要的下一步。
 */
const poiFilter = ref('')
const catFilter = ref('')
/** 产地 id → 名称，用来把 id 型筛选渲染成可读的 chip */
const poiNameById = ref<Record<string, string>>({})

function onRankClick(p: any) {
  const id = String(p?.data?.id ?? '')
  if (!id) return
  poiFilter.value = poiFilter.value === id ? '' : id
}
function onStructureClick(p: any) {
  const name = String(p?.name ?? '')
  if (!name) return
  catFilter.value = catFilter.value === name ? '' : name
}

// ============================================================
// 资源列表（农产品）
// ============================================================
const rowFilter = computed(() => {
  if (!poiFilter.value && !catFilter.value) return null
  return (row: ResourceRow) => {
    const p = row as AdminProduct
    if (poiFilter.value && p.poi_id !== poiFilter.value) return false
    if (catFilter.value && p.category !== catFilter.value) return false
    return true
  }
})

const list = useResourceList('product', { filter: rowFilter })
const formRef = ref<InstanceType<typeof ResourceFormDrawer> | null>(null)
const imgRef = ref<InstanceType<typeof ResourceImagesDrawer> | null>(null)

watch(list.allRows, (rows) => {
  const m: Record<string, string> = {}
  for (const r of rows as AdminProduct[]) {
    if (r.poi_id) m[r.poi_id] = r.poi_name || r.poi_id
  }
  poiNameById.value = m
})

function onSave(body: Record<string, unknown>, editingId: string | null) {
  void list.write(editingId ? '已保存' : '已新增农产品', async () => {
    if (editingId) await list.updateProduct(editingId, body)
    else await list.createProduct(body)
    formRef.value?.close()
    await list.reload()
  })
}

// ============================================================
// 真实订单（待发货 KPI + 商品明细）
// ============================================================
const orders = ref<Order[]>([])
const ordersLoading = ref(false)

async function loadOrders() {
  ordersLoading.value = true
  try {
    orders.value = await adminListOrders()
  } catch {
    /* 订单拉不到不影响经营概览；待发货显示 0、明细区显示空态 */
    orders.value = []
  } finally {
    ordersLoading.value = false
  }
}

const pendingShip = computed(() => orders.value.filter((o) => o.status === 'PENDING_SHIPMENT').length)

/** 商品明细：把全部订单的 order_item 按产品聚合 */
interface ItemAgg {
  product_id: string
  product_name: string
  spec?: string
  unit_price: number
  qty: number
  subtotal: number
  orders: number
}
const itemRows = computed<ItemAgg[]>(() => {
  const m = new Map<string, ItemAgg & { orderSet: Set<number> }>()
  for (const o of orders.value) {
    for (const it of o.items ?? []) {
      const cur = m.get(it.product_id) ?? {
        product_id: it.product_id,
        product_name: it.product_name,
        spec: it.spec,
        unit_price: it.unit_price,
        qty: 0,
        subtotal: 0,
        orders: 0,
        orderSet: new Set<number>(),
      }
      cur.qty += it.quantity
      cur.subtotal += it.subtotal
      cur.orderSet.add(o.id)
      m.set(it.product_id, cur)
    }
  }
  return [...m.values()]
    .map((r) => ({ ...r, orders: r.orderSet.size }))
    .sort((a, b) => b.subtotal - a.subtotal)
})
const itemTotal = computed(() => itemRows.value.reduce((s, r) => s + r.subtotal, 0))

// ============================================================
// 生命周期
// ============================================================
onMounted(() => {
  void load()
  void list.reload()
  void loadOrders()
})
</script>

<template>
  <div class="pg">
    <header class="pg__head">
      <div>
        <span class="eyebrow">乡村好物</span>
        <h1 class="h1 pg__title">农产品管理</h1>
        <p class="pg__sub">
          农产品不是独立商城 —— 每一款都挂在一次乡村体验或一个产地资源点上。
          这一页同时看两件事：乡村产地的销售盘子（仿真统计），和真实发生过的订单（运行期数据）。
        </p>
      </div>
      <div class="pg__meta">
        <span class="badge-sim">仿真数据 + 真实订单</span>
      </div>
    </header>

    <div class="pg__bar">
      <RangeSwitch :model-value="range" :loading="switching" :note="periodNote" @update:model-value="pickRange" />
      <span v-if="switching" class="pg__busy">更新中…</span>
    </div>

    <KpiBar :items="kpiItems" :loading="loading" note="销售额 / 购买 / 复购为仿真统计（产地级口径）；待发货订单为真实订单数据。" />

    <div v-if="error" class="notice notice--err">{{ error }}</div>

    <div class="tabs">
      <button
        v-for="t in TABS"
        :key="t.key"
        class="tab"
        :class="{ 'tab--on': tab === t.key }"
        @click="tab = t.key"
      >
        {{ t.label }}
        <span v-if="t.key === 'orders' && pendingShip" class="tab__n">{{ pendingShip }}</span>
      </button>
    </div>

    <!-- ==================== 订单处理（原 Orders.vue，状态机未改） ==================== -->
    <OrdersPanel v-if="tab === 'orders'" embedded />

    <!-- ==================== 经营概览 ==================== -->
    <template v-else>
      <div v-if="loading" class="grid-3 pg__grid">
        <div v-for="i in 3" :key="i" class="skeleton" style="height: 260px; border-radius: 10px" />
      </div>

      <template v-else>
        <!-- 第一层：谁是主力（排行，主图） + 卖的是什么（构成，次图） -->
        <div class="pg__grid pg__grid--top">
          <ChartCard
            tone="hero"
            title="乡村产地销售额排行"
            :sub="`全部 ${data?.top.length ?? 0} 个乡村产地 · 产地级口径（购买笔数 × 产地产品均价）`"
            tip="产地级口径：与驾驶舱的农产品销售额同源同算法，两个数字必然相等。"
            :is-empty="!(data?.top.length)"
            empty="当前区间内没有乡村产地的销售记录"
          >
            <EChart :option="rankOption" height="300px" @click="onRankClick" />
            <template #foot>点某一根条 → 下方农产品列表只看这个产地。</template>
          </ChartCard>

          <ChartCard
            tone="main"
            title="销售结构"
            :sub="`按产品分类聚合 · ${data?.distribution.length ?? 0} 个分类有销售`"
            tip="产地内若有多个分类的产品，销售额按款数均摊 —— 数据只到产地级，做不到单品级拆分。"
            :is-empty="!(data?.distribution.length)"
            empty="当前区间内没有分类销售数据"
          >
            <EChart :option="structureOption" height="300px" @click="onStructureClick" />
            <template #foot>点某一环 → 下方列表只看这个分类。</template>
          </ChartCard>
        </div>

        <!-- 第二层：趋势（整行） + 购买与复购的比例（次图） -->
        <div class="pg__grid pg__grid--mid">
          <ChartCard
            tone="trend"
            title="销售额趋势"
            :sub="`逐日销售额 · ${data?.trend.length ?? 0} 个数据点（不足 7 天时按 7 天窗口展示）`"
            tip="趋势点数下限 7 —— 与驾驶舱同一约定，避免「今日」档只有一根柱看不出走势。"
            :is-empty="!(data?.trend.length)"
          >
            <EChart :option="trendOption" height="240px" />
          </ChartCard>

          <ChartCard
            tone="main"
            title="购买 vs 复购"
            :sub="`复购率 ${data?.kpis.find((k) => k.key === 'repurchase')?.value ?? '—'}% · 口径：复购笔数 ÷ 购买笔数`"
            tip="这是笔数比，不是用户级复购率 —— 统计表是「资源点 × 天」的汇总，没有用户身份维度。"
            :is-empty="!(data?.trend.length)"
          >
            <EChart :option="buyRepOption" height="240px" />
          </ChartCard>
        </div>

        <!-- 第三层：贡献份额。与上方的「排行」刻意不同形状 —— 排行看绝对值，这张看份额 -->
        <ChartCard
          tone="main"
          title="产地销售贡献"
          :sub="`各产地占总销售额的比重 · 合计 100% · 共 ${data?.top.length ?? 0} 个产地`"
          tip="与「销售额排行」是同一个数据源，但看的是份额而不是绝对值：十个产地挤在一条上，谁占多少一眼分得出。"
          :is-empty="!(data?.top.length)"
        >
          <EChart :option="shareOption" height="150px" @click="onRankClick" />
        </ChartCard>
      </template>

      <!-- ==================== 实际订单商品明细（运行期真实数据） ==================== -->
      <section class="pg__sec">
        <header class="pg__sec-head">
          <div>
            <h2 class="h3">实际订单商品明细</h2>
            <p class="pg__sec-sub">
              来自真实订单表（<code>order_item</code>），不是仿真统计 ——
              上面几张图是"乡村的销售盘子"，这里是"演示期间真实发生过的那几单"，两者数量级不同，不要放在一起比较。
            </p>
          </div>
          <span class="badge-sim">运行期数据</span>
        </header>

        <div v-if="ordersLoading" class="skeleton" style="height: 120px; border-radius: 10px" />
        <div v-else-if="!itemRows.length" class="empty">
          <div class="empty__title">还没有真实订单</div>
          <div class="empty__desc">游客在「乡村好物」下单后会出现在这里，也会出现在「订单处理」标签页。</div>
        </div>
        <div v-else class="tablewrap">
          <table class="rt">
            <thead>
              <tr>
                <th class="rt__name">商品</th>
                <th>规格</th>
                <th>单价</th>
                <th>件数</th>
                <th>涉及订单</th>
                <th class="rt__ops">小计</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in itemRows" :key="r.product_id">
                <td>
                  <div class="rt__title">{{ r.product_name }}</div>
                  <div class="rt__meta"><code>{{ r.product_id }}</code></div>
                </td>
                <td class="muted">{{ r.spec || '—' }}</td>
                <td class="num">{{ fmtMoney(r.unit_price) }}</td>
                <td class="num">{{ r.qty }}</td>
                <td class="num">{{ r.orders }}</td>
                <td class="rt__ops num">{{ fmtMoney(r.subtotal) }}</td>
              </tr>
              <tr class="pg__sum">
                <td colspan="5">合计</td>
                <td class="rt__ops num">{{ fmtMoney(itemTotal) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- ==================== 农产品管理 ==================== -->
      <section class="pg__sec">
        <header class="pg__sec-head">
          <div>
            <h2 class="h3">农产品管理</h2>
            <p class="pg__sec-sub">
              下架优先于删除 —— 被订单或体验引用的资源不允许物理删除，以免留下悬空引用。
              来自城市数据包的农产品当前不支持编辑，要改内容请改数据包，只想藏起来请下架。
            </p>
          </div>
        </header>

        <Transition name="notice">
          <div v-if="list.notice.value" class="notice" :class="`notice--${list.notice.value.type}`">
            {{ list.notice.value.text }}
          </div>
        </Transition>

        <ResourceToolbar
          v-model:keyword="list.keyword.value"
          v-model:status="list.statusFilter.value"
          noun="农产品"
          :busy="list.busy.value"
          :loading="list.loading.value"
          @search="list.reload"
          @create="formRef?.openFor(null)"
        >
          <template #chips>
            <button v-if="poiFilter" class="chip" title="点一下清掉这个筛选" @click="poiFilter = ''">
              {{ poiNameById[poiFilter] || poiFilter }} ✕
            </button>
            <button v-if="catFilter" class="chip" title="点一下清掉这个筛选" @click="catFilter = ''">
              {{ catFilter }} ✕
            </button>
          </template>
        </ResourceToolbar>

        <div v-if="list.loading.value" class="stack-3">
          <div v-for="i in 5" :key="i" class="skeleton" style="height: 56px; border-radius: 10px" />
        </div>
        <ResourceTable
          v-else
          :rows="list.rows.value"
          :is-poi="false"
          noun="农产品"
          :busy="list.busy.value"
          :empty-hint="list.keyword.value || list.statusFilter.value || poiFilter || catFilter ? '换个关键词或状态试试' : '点右上角「新增」建一条'"
          :can-edit="list.canEdit"
          :edit-hint="list.editHint"
          :remove-hint="list.removeHint"
          @edit="formRef?.openFor($event)"
          @toggle="list.toggleStatus"
          @images="imgRef?.openFor($event)"
          @remove="list.remove"
        />
      </section>
    </template>

    <ResourceFormDrawer
      ref="formRef"
      :is-poi="false"
      default-biz-type=""
      :biz-options="[]"
      :busy="list.busy.value"
      @save="onSave"
    />
    <ResourceImagesDrawer ref="imgRef" />
  </div>
</template>

<style scoped>
.pg {
  max-width: 1400px;
}

.pg__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-5);
  margin-bottom: var(--sp-4);
}
.pg__title {
  margin-top: var(--sp-2);
}
.pg__sub {
  margin-top: var(--sp-2);
  max-width: 780px;
  font-size: var(--fs-sm);
  line-height: 1.85;
  color: var(--text-2);
}
.pg__meta {
  flex: none;
  padding-top: var(--sp-3);
}

.pg__bar {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  margin-bottom: var(--sp-4);
}
.pg__busy {
  font-size: var(--fs-cap);
  color: var(--gold-300);
}

.tabs {
  margin-top: var(--sp-5);
}
.tab__n {
  margin-left: 6px;
  padding: 0 6px;
  font-size: 11px;
  color: #2b1e07;
  background: var(--gold-500);
  border-radius: 999px;
}

/* ---------- 图表栅格 ---------- */
.pg__grid {
  display: grid;
  gap: var(--sp-4);
  margin-bottom: var(--sp-4);
}
.pg__grid--top {
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
}
.pg__grid--mid {
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
}
@media (max-width: 1100px) {
  .pg__grid--top,
  .pg__grid--mid {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* ---------- 次级区块 ---------- */
.pg__sec {
  margin-top: var(--sp-6);
}
.pg__sec-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
  margin-bottom: var(--sp-4);
}
.pg__sec-sub {
  margin-top: 6px;
  max-width: 820px;
  font-size: var(--fs-cap);
  line-height: 1.8;
  color: var(--text-3);
}
.pg__sec-sub code {
  color: var(--gold-300);
}
.pg__sum td {
  color: var(--text-2);
  font-weight: 600;
  border-top: 1px solid var(--line-strong);
}
</style>
