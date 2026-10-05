<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'
import EChart from '@/components/EChart.vue'
import { createWorkOrder, getOpsBusiness, getOpsSnapshot, getRisks } from '@/api/ops'
import { analyzeOps } from '@/api/ai'
import { ApiError } from '@/api/http'
import { useAsync } from '@/composables/useAsync'
import { useNotice } from '@/composables/useNotice'
import { inlineText, when } from '@/utils/format'
import {
  OPS_FOCUSES,
  OPS_MODE_LABEL,
  RISK_TYPE_LABEL,
  type OpsAnalysis,
  type OpsBizType,
  type OpsBusiness,
  type OpsRange,
  type OpsSnapshot,
} from '@/types'

/**
 * 运营快照。M5 起数据来自后端（`/api/admin/ops/snapshot`），
 * 不再是 `buildOpsSnapshot(pois, products)` 在前端按 id 派生出来的伪随机值。
 *
 * 换数据源时**只改"从哪来"，没改"长什么样"**：VO 的字段形状是按旧 mock
 * 对齐的，所以下面这些图表配置一行没动（除了字段名从驼峰改成后端的蛇形）。
 * 这一点是刻意的 —— 图表配置里字段名写错是**静默**的，图上就是一条平线，
 * 不报错，很难发现。
 *
 * 全部为仿真数据（`synthetic` 恒为 true），界面上有标注。
 */
const router = useRouter()
const { notice, say } = useNotice()

// ---- 全局统计区间（驾驶舱顶部的日期控件）----
/**
 * 四个档位复用 M5 已有的**风险统计日口径**（基准日 = 数据包里最新的一天，
 * 不是浏览器系统日期），不是新造的统计概念。
 *
 * 全屏的数字都由后端按这个档位**重新聚合** —— 前端不算任何指标。
 * 前端只负责把档位传下去、把结果画出来，这样"屏幕上的数"与"接口给的数"
 * 永远是同一份，答辩时对得上。
 */
const range = ref<OpsRange>('TODAY')
const RANGES: { key: OpsRange; label: string }[] = [
  { key: 'TODAY', label: '今日' },
  // 用"日"不用"天"：KPI 标签的周期前缀是"近 7 日"、`Risks.vue` 的日期分段
  // 也是"近 7 日"。同一个窗口在同一个系统里两种说法，看屏的人会以为是两个口径。
  { key: 'LAST7', label: '近 7 日' },
  { key: 'LAST30', label: '近 30 日' },
  { key: 'ALL', label: '全部' },
]

const ops = ref<OpsSnapshot | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
/** 切换区间时的"静默加载"。与首次加载分开：切档位不该让整屏回到骨架屏 */
const switching = ref(false)
/** 请求序号，用来丢弃过期响应 —— 见下面 load() 的注释 */
let reqId = 0

/**
 * 拉快照。
 *
 * `quiet = true` 时**保留旧数据**（不显示骨架屏），只在顶部标"更新中" ——
 * 否则每切一次档位整屏闪一下骨架，视觉上像刷新了页面。
 *
 * 丢弃过期响应：连点"近 7 天 → 全部"会有两个请求并发，先发的可能后到。
 * 判定用的是**后端回显的 `range`**（`d.range`），不是请求顺序 ——
 * 请求顺序只能证明"谁后发"，证明不了"谁的数据是当前想要的"。
 */
async function load(quiet = false) {
  const id = ++reqId
  const want = range.value
  if (quiet) switching.value = true
  else loading.value = true
  error.value = null
  // 四大业务总览与快照**并发拉**。它不是关键路径：拉不到就少显示一块，
  // 不该拖慢（更不该阻塞）整屏 —— 见 loadBiz 的注释
  void loadBiz(want)
  try {
    const d = await getOpsSnapshot(want)
    if (id !== reqId || d.range !== range.value) return
    ops.value = d
  } catch (e) {
    if (id !== reqId) return
    const msg = e instanceof ApiError ? e.message : '加载失败'
    if (quiet) {
      // 静默加载失败：**保留旧数据**，只用提示条说一声 ——
      // 把整屏换成错误页会让运营以为刚才看到的数据没了
      say('err', `切换统计区间失败：${msg}`)
    } else {
      error.value = msg
      ops.value = null
    }
  } finally {
    if (id === reqId) {
      loading.value = false
      switching.value = false
    }
  }
}

// ============================================================
// 四大业务总览（驾驶舱第二层）
// ============================================================
/**
 * 四个业务模块的概况数据。
 *
 * <p><b>为什么要额外拉 4 个请求。</b>`/ops/snapshot` 是**全局聚合**：它的 `mix`
 * 里虽然有餐饮 / 住宿的客流，但没有各业态自己的 Top3 与趋势。第二层要的是
 * "每个业务线各自的一屏小概况"，所以走 `business` 端点。
 *
 * <p><b>这 4 个请求与 snapshot 是同一套算法</b>（后端复用同一批 `sumWindow` /
 * `avgUsageByPoi` / `avgPriceByPoi`），所以卡片上的数**必然等于**点进去那一页
 * 的数 —— 对不上就是 bug，不是"口径不同"。
 *
 * <p><b>失败不阻塞整屏。</b>某一块拉不到就少显示一块（数字显示"—"），
 * 而不是让整个驾驶舱变成错误页。总览的价值在于"一屏看全"，少一块也比全白强。
 */
const biz = ref<Partial<Record<OpsBizType, OpsBusiness>>>({})

async function loadBiz(r: OpsRange) {
  const types: OpsBizType[] = ['rural', 'product', 'food', 'lodging']
  const results = await Promise.all(
    types.map(async (t) => {
      try {
        return [t, await getOpsBusiness(t, r)] as const
      } catch {
        return [t, null] as const
      }
    })
  )
  // 与 load() 同一套"丢弃过期响应"的判据：比的是**档位**，不是请求顺序
  if (r !== range.value) return
  const next: Partial<Record<OpsBizType, OpsBusiness>> = {}
  for (const [t, v] of results) {
    if (v) next[t] = v
  }
  biz.value = next
}

/**
 * 切档位：先清掉图表选中态（选中的那天可能已经不在新窗口里了），再静默重拉。
 *
 * **同时清掉上一次的 AI 解读**：那段解读是**按上一个窗口算出来的**，
 * 换窗口后它还挂在面板上，就等于用"近 30 天的图"配"今日的解读"。
 * 清掉比留着好 —— 面板会退回"选一个维度"的提示，用户重新点一次就是新口径的。
 * （不自动重跑：换一次档位就调一次模型，等于每次切日期都烧一次 token。）
 */
function pickRange(r: OpsRange) {
  if (range.value === r) return
  range.value = r
  clearPicks()
  analysis.value = null
  analysisError.value = ''
}

watch(range, () => void load(true))
onMounted(() => void load())

/**
 * 快照里**第一条还没处置**的风险。右侧列表只有 5 条（后端按
 * "等级优先 + 类型多样"挑过），所以这里不是"全市最严重的那条"，
 * 而是"这一屏里第一条可动的"。
 *
 * 为什么要挑 OPEN 的：快照里会混进已建单/已闭环的事件（那是历史，
 * 要能回看）。拿一条已建单的去建单，后端会回 8003，用户看到的是
 * 一个报错 —— 而界面上本来就能判断出"这条动不了"。
 */
const topOpenRisk = computed(() => ops.value?.risks?.find((r) => r.status === 'OPEN') ?? null)

const creating = ref(false)

/** 为右侧列表里第一条待处置风险建单，然后刷新快照 */
async function createTopWorkOrder() {
  const top = topOpenRisk.value
  if (!top || creating.value) return
  creating.value = true
  try {
    const wo = await createWorkOrder(top.id)
    say('ok', `已建单 ${wo.code} · ${wo.risk_poi_name}`)
    // 建完要重新拉一次：KPI 的"待处置风险"与列表里的状态都变了。
    // 不在本地改状态 —— 状态由服务端说了算，本地改会和下一次刷新打架。
    // 用静默加载：建单成功后整屏闪一下骨架屏，会让人以为页面被重置了。
    await load(true)
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '建单失败')
  } finally {
    creating.value = false
  }
}

// ---- M7：AI 运营解读 ----

/**
 * 当前选中的解读维度。**不会自动跑** —— 进页面就调一次模型等于
 * 每打开一次驾驶舱就烧一次 token，而多数时候运营只是来看图的。
 * 想看解读就点一下维度，这是明确的意图。
 */
const focus = ref('overview')
const analysis = ref<OpsAnalysis | null>(null)
const analyzing = ref(false)
/** 面板内联的错误文案。用 toast 也行，但错误出现在它所属的那块面板里更好找 */
const analysisError = ref('')

/**
 * 跑一次解读。
 *
 * 失败时**清掉上一次的结果**：点了"销售"却失败，面板上还留着上一次
 * "冷热失衡"的三段话，会让人以为那是销售的解读 —— 这是最坏的一种错。
 */
async function runAnalysis(key: string) {
  if (analyzing.value) return
  focus.value = key
  analyzing.value = true
  analysisError.value = ''
  try {
    // 区间跟着**后端回显的档位**走（`ops.range`），不是前端的 `range`：
    // 万一响应过期、参数被回落，解读要和**屏幕上这份数据**同口径。
    analysis.value = await analyzeOps(key, ops.value?.range)
  } catch (e) {
    analysis.value = null
    analysisError.value = e instanceof ApiError ? e.message : 'AI 解读失败，请稍后再试'
  } finally {
    analyzing.value = false
  }
}

/** 当前维度的中文名。加载文案要用它，不能只说"正在解读…" */
const focusLabel = computed(
  () => OPS_FOCUSES.find((f) => f.key === focus.value)?.label ?? ''
)

/** 模式徽标的配色。三态**必须看得出区别**，尤其 cache 与 llm 不能同色 */
const modeTone = computed(() => {
  switch (analysis.value?.mode) {
    case 'llm':
      return 'tag-gold'
    case 'cache':
      return 'tag-warn'
    default:
      return 'tag'
  }
})

// ---- 图表通用配置（深色底，克制配色）----
const axisStyle = {
  axisLine: { lineStyle: { color: 'rgba(146,178,165,0.22)' } },
  axisTick: { show: false },
  axisLabel: { color: '#8aa398', fontSize: 11 },
  splitLine: { lineStyle: { color: 'rgba(146,178,165,0.10)' } },
}
const tooltipStyle = {
  backgroundColor: 'rgba(11,33,25,0.94)',
  borderColor: 'rgba(146,178,165,0.28)',
  textStyle: { color: '#eef3f0', fontSize: 12 },
}

/** 趋势柱宽。按点数自适应：7 天用 18px，60 天收到 4px（否则柱子互相压住） */
const barWidth = computed(() => {
  const n = ops.value?.trend.length ?? 7
  return Math.max(4, Math.min(18, Math.floor(220 / Math.max(1, n))))
})

const trendOption = computed(() => {
  const o = ops.value
  if (!o) return {}
  return {
    grid: { left: 8, right: 8, top: 34, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', ...tooltipStyle },
    legend: {
      data: ['核心景区到访', '承载占用率'],
      right: 0,
      top: 0,
      textStyle: { color: '#8aa398', fontSize: 11 },
      itemWidth: 10,
      itemHeight: 6,
    },
    xAxis: { type: 'category', data: o.trend.map((t) => t.date), ...axisStyle },
    yAxis: [
      { type: 'value', ...axisStyle },
      { type: 'value', max: 100, axisLabel: { ...axisStyle.axisLabel, formatter: '{value}%' }, splitLine: { show: false }, axisLine: { show: false } },
    ],
    series: [
      {
        name: '核心景区到访',
        type: 'bar',
        data: o.trend.map((t) => t.visitors),
        barWidth: barWidth.value,
        // 选中态交给 ECharts 自己管：回调只换详情文案，不重绘整张图
        selectedMode: 'single',
        select: { itemStyle: { color: '#c09a4e' } },
        itemStyle: { color: '#2a6f5b', borderRadius: [3, 3, 0, 0] },
      },
      {
        name: '承载占用率',
        type: 'line',
        yAxisIndex: 1,
        data: o.trend.map((t) => t.usage),
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { color: '#c09a4e', width: 2 },
        itemStyle: { color: '#c09a4e' },
      },
    ],
  }
})

const mixOption = computed(() => {
  const o = ops.value
  if (!o) return {}
  return {
    tooltip: { trigger: 'item', ...tooltipStyle },
    legend: {
      bottom: 0,
      textStyle: { color: '#8aa398', fontSize: 11 },
      itemWidth: 10,
      itemHeight: 6,
    },
    series: [
      {
        type: 'pie',
        radius: ['52%', '74%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        // 点击选中：选中的扇形**向外挪 6px**（ECharts 原生行为）。
        // 偏移量调小是刻意的 —— 默认 10px 在这个半径下会顶到图例。
        selectedMode: 'single',
        selectedOffset: 6,
        select: { itemStyle: { borderColor: '#c09a4e', borderWidth: 3 } },
        itemStyle: { borderColor: '#123026', borderWidth: 2 },
        label: {
          show: true,
          position: 'center',
          formatter: () => `{v|${o.mix.reduce((s, m) => s + m.value, 0).toLocaleString()}}\n{l|总到访人次}`,
          rich: {
            v: { color: '#ffffff', fontSize: 20, fontWeight: 700, lineHeight: 26 },
            l: { color: '#8aa398', fontSize: 11 },
          },
        },
        labelLine: { show: false },
        data: o.mix.map((m, i) => ({
          ...m,
          itemStyle: { color: ['#2e7bc4', '#2a6f5b', '#c09a4e', '#71a996'][i % 4] },
        })),
      },
    ],
  }
})

const imbalanceOption = computed(() => {
  const o = ops.value
  if (!o) return {}
  const rows = [...o.imbalance].sort((a, b) => a.scenic - b.scenic)
  return {
    grid: { left: 8, right: 24, top: 30, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, ...tooltipStyle },
    legend: { right: 0, top: 0, textStyle: { color: '#8aa398', fontSize: 11 }, itemWidth: 10, itemHeight: 6 },
    xAxis: {
      type: 'value',
      max: 100,
      ...axisStyle,
      axisLabel: { ...axisStyle.axisLabel, formatter: '{value}%' },
    },
    yAxis: { type: 'category', data: rows.map((r) => r.name), ...axisStyle },
    series: [
      {
        name: '景区承载',
        type: 'bar',
        data: rows.map((r) => r.scenic),
        barWidth: 8,
        selectedMode: 'single',
        select: { itemStyle: { color: '#e0c07a' } },
        itemStyle: { color: '#2e7bc4', borderRadius: [0, 3, 3, 0] },
      },
      {
        name: '乡村承载',
        type: 'bar',
        data: rows.map((r) => r.rural),
        barWidth: 8,
        selectedMode: 'single',
        select: { itemStyle: { color: '#e0c07a' } },
        itemStyle: { color: '#c09a4e', borderRadius: [0, 3, 3, 0] },
      },
    ],
  }
})

const productOption = computed(() => {
  const o = ops.value
  if (!o) return {}
  const rows = [...o.rural_sales_top].reverse()
  return {
    grid: { left: 8, right: 40, top: 12, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'item',
      ...tooltipStyle,
      formatter: (p: any) => `${p.name}<br/>销售额 ¥${p.value.toLocaleString()}`,
    },
    xAxis: { type: 'value', ...axisStyle, axisLabel: { show: false }, splitLine: { show: false } },
    yAxis: {
      type: 'category',
      data: rows.map((r) => r.name),
      ...axisStyle,
      axisLabel: { ...axisStyle.axisLabel, fontSize: 11 },
    },
    series: [
      {
        type: 'bar',
        data: rows.map((r) => r.sales),
        barWidth: 10,
        selectedMode: 'single',
        select: { itemStyle: { color: '#c09a4e' } },
        itemStyle: { color: '#3d8b74', borderRadius: [0, 3, 3, 0] },
        label: {
          show: true,
          position: 'right',
          color: '#8aa398',
          fontSize: 11,
          formatter: (p: any) => `¥${p.value.toLocaleString()}`,
        },
      },
    ],
  }
})

// ============================================================
// 图表点击：轻量选中态 + 面板内详情
// ============================================================
/**
 * 点柱子 / 点扇形 / 点条形之后，把"这一项"记下来，显示在**所属面板的副标题**位置。
 *
 * 为什么显示在面板自己的副标题里，而不是在页面别处开一块详情：
 * 项目里已经吃过一次亏 —— "点上面的指标卡、结果却出现在屏幕另一端，是自找的困惑"。
 * 而副标题是**既有元素**，换文案不改变任何布局尺寸，点一下图不会把下面的内容顶下去。
 *
 * 再点一次同一项 = 取消选中；切档位 = 全部清掉（选中的那天可能已不在新窗口里）。
 * 选中态本身交给 ECharts 的 `selectedMode`（见各 option 的 `select`），
 * 不靠"改数据再 setOption" —— 那会整张图重绘一次，点一下闪一下。
 */
const trendPick = ref<number | null>(null)
const mixPick = ref<string | null>(null)
const imbPick = ref<string | null>(null)
const prodPick = ref<string | null>(null)

function clearPicks() {
  trendPick.value = null
  mixPick.value = null
  imbPick.value = null
  prodPick.value = null
}

/** 点同一项再点一次 = 取消。`i` 可能是 undefined（点在空白处） */
function toggleIndex(cur: number | null, i: unknown): number | null {
  return typeof i === 'number' && cur !== i ? i : null
}

function toggleName(cur: string | null, n: unknown): string | null {
  const s = String(n ?? '')
  return s && cur !== s ? s : null
}

function onTrendClick(p: any) {
  trendPick.value = toggleIndex(trendPick.value, p?.dataIndex)
}
function onMixClick(p: any) {
  mixPick.value = toggleName(mixPick.value, p?.name)
}
function onImbalanceClick(p: any) {
  imbPick.value = toggleName(imbPick.value, p?.name)
}
function onProductClick(p: any) {
  prodPick.value = toggleName(prodPick.value, p?.name)
}

/**
 * 每天的风险事件数。**从全量风险事件现算**，不是快照里那 5 条 ——
 * 快照的 `risks` 是"等级优先 + 类型多样"挑过的展示样本，
 * 拿它当"那天的风险数"会少算。
 *
 * 这是本页唯一一处前端计算，算的是"按天计数"这种**原始事实的聚合**，
 * 不是业务指标 —— 业务指标（到访、承载、销售额、复购率）一律由后端给。
 */
const { data: riskList } = useAsync(() => getRisks())
const riskCountByDate = computed(() => {
  const m = new Map<string, number>()
  for (const r of riskList.value ?? []) {
    const d = String(r.stat_date ?? '').slice(0, 10)
    if (d) m.set(d, (m.get(d) ?? 0) + 1)
  }
  return m
})

/** 趋势图选中项的说明。日期用 `date_iso`（`date` 只是"周一"这种展示标签，不唯一） */
const trendDetail = computed(() => {
  const o = ops.value
  const i = trendPick.value
  if (!o || i == null || !o.trend[i]) return ''
  const t = o.trend[i]
  const n = riskCountByDate.value.get(t.date_iso) ?? 0
  // 日期只留 MM-DD：年份是当前区间内的常量，占位却不提供信息
  return `${t.date_iso.slice(5)} · 到访 ${t.visitors.toLocaleString()} · 承载 ${t.usage}% · 风险 ${n} 件`
})

/** 环形图选中业态：名称 / 客流量 / 占比。占比是"这一块占几成"，在这里现算 */
const mixDetail = computed(() => {
  const o = ops.value
  const n = mixPick.value
  if (!o || !n) return ''
  const total = o.mix.reduce((s, m) => s + m.value, 0)
  const item = o.mix.find((m) => m.name === n)
  if (!item || !total) return ''
  return `${item.name} ${item.value.toLocaleString()} 人次 · 占比 ${((item.value / total) * 100).toFixed(1)}%`
})

/**
 * 区县选中项。两个数都是**窗口内的平均承载率**（%），不是客流人次 ——
 * 数据只有"资源点 × 天"的承载率，区县级客流人次在当前数据下算不出来，
 * 不要为了好听说成"客流"。
 */
const imbalanceDetail = computed(() => {
  const o = ops.value
  const n = imbPick.value
  if (!o || !n) return ''
  const row = o.imbalance.find((r) => r.name === n)
  return row ? `${row.name} · 景区承载 ${row.scenic}% · 乡村承载 ${row.rural}%` : ''
})

const productDetail = computed(() => {
  const o = ops.value
  const n = prodPick.value
  if (!o || !n) return ''
  const row = o.rural_sales_top.find((r) => r.name === n)
  return row ? `${row.name} · 销售额 ¥${row.sales.toLocaleString()}` : ''
})

/** 带符号的百分比/百分点，保留一位小数 */
function signed(n: number, suffix: string) {
  return `${n >= 0 ? '+' : ''}${n.toFixed(1)}${suffix}`
}

/** 指标卡 → 业务页。目标与筛选参数都由 `kpis` 给好，这里只负责跳 */
function goTo(to: RouteLocationRaw) {
  void router.push(to)
}

/**
 * KPI 标签的周期前缀。**跟着所选区间走** —— 数字是几天的，标签就写几天。
 *
 * 读的是**后端回显的 `range`**（`o.range`），不是前端自己的 `range`：
 * 万一两者不一致（响应过期、参数不认识被回落），标签要跟着**实际数据**走。
 * 否则会出现"标签写近 7 日、数字其实是今日的"——这种不一致不报错，只会一直错。
 */
const rangePrefix = computed(() => {
  switch (ops.value?.range) {
    case 'LAST7':
      return '近 7 日'
    case 'LAST30':
      return '近 30 日'
    case 'ALL':
      return '全部'
    default:
      return '今日'
  }
})

/**
 * 逐卡口径 tooltip。**为什么写在卡片上**：鼠标停在某个数字上时，人最想知道的
 * 就是"这个数是怎么算出来的"。脚注那一行是五张卡**共用**的口径，讲的是
 * "销售额/复购率这两个词各自能读成两种意思"；这里补的是**每一张卡自己**的口径。
 *
 * ★ 复购卡这条是硬要求：口径必须原样写出"复购笔数 / 购买笔数"，
 *   防止被读成"用户级复购率"（见 OpsSnapshotVO 的字段注释）。
 */
const KPI_HINT: Record<string, string> = {
  scenic: '口径：所选统计区间内核心景区到访人次合计（人次，不是独立游客数）',
  rural: '口径：乡村到访 ÷（核心景区 + 乡村）到访，所选统计区间内合计',
  sales: '口径：乡村点关联产品均价 × 购买笔数（乡村点级，不是单品级销量）',
  repurchase: '口径：复购笔数 / 购买笔数（所选统计区间内的乡村点合计，非用户级）',
  risks: '口径：所选统计区间内未闭环风险事件数（status ≠ CLOSED，含已建单）',
}

const kpis = computed(() => {
  const o = ops.value
  if (!o) return []
  const p = rangePrefix.value
  // 每张卡带一个 `to`：点击进对应的业务页去"解决问题"。
  // 驾驶舱只负责"发现问题"，不在这一页做 CRUD。
  //
  // 口径提示（这里曾经写错，见 OpsSnapshotVO 的字段注释）：`total_visitors`
  // 是**核心景区**到访人次，不含乡村/餐饮/住宿，也不是独立游客数（是人次）。
  // 一个"全市"就让数字的含义大了一圈，而屏幕上没人能看出来。
  return [
    {
      key: 'scenic',
      label: `${p}核心景区到访`,
      value: o.total_visitors.toLocaleString(),
      unit: '人次',
      delta: `${signed(o.deltas.visitors_pct, '%')} 环比`,
      up: o.deltas.visitors_pct >= 0,
      // 2026-10-04 起指向「乡村景点管理」：原来的 `/admin/resources?kind=scenic`
      // 是一条兼容跳转，直接指新页面少一跳。**带上 range** —— 否则"切到近 30 日
      // 再点进去，看到的却是今日"，两个数字并排出现在两屏上，没人能一眼看出谁错了。
      to: { path: '/admin/attractions', query: { range: o.range } },
    },
    {
      key: 'rural',
      label: `${p}乡村到访占比`,
      value: (o.rural_ratio * 100).toFixed(1),
      unit: '%',
      delta: `${signed(o.deltas.rural_ratio_pt, 'pt')} 环比`,
      up: o.deltas.rural_ratio_pt >= 0,
      // 景点页内部的 `seg=rural` 分段 —— 景点页把核心景区与乡村景点收在**一页**里
      // （用轻量分段切换），不是两个一级菜单。所以这里不是"跳另一个页面"，
      // 而是"跳到那一页并把分段切到乡村"
      to: { path: '/admin/attractions', query: { seg: 'rural', range: o.range } },
    },
    {
      key: 'sales',
      label: `${p}农产品销售额`,
      value: `¥${(o.product_sales / 10000).toFixed(1)}`,
      unit: '万元',
      delta: `${signed(o.deltas.sales_pct, '%')} 环比`,
      up: o.deltas.sales_pct >= 0,
      to: { path: '/admin/products', query: { range: o.range } },
    },
    {
      key: 'repurchase',
      // ★ 这里**曾经叫「离境复购率」**，是错的：那会让人以为分母是"离境消费的
      //   用户数"，也就是一个**用户级**复购率。而实际口径是**笔数比**——
      //   `poi_visit_stats` 只有"资源点 × 天"的汇总，没有用户身份维度，
      //   用户级复购率在当前数据模型下**算不出来**（见 OpsSnapshotVO 的字段注释）。
      //   改名而不是改算法：算法是对的，名字在过度声称。
      //   页面下方的口径脚注（`.kpi__gloss`）负责把"笔数 / 笔数"说清楚。
      label: `${p}乡村复购率`,
      value: (o.repurchase_rate * 100).toFixed(1),
      unit: '%',
      delta: `${signed(o.deltas.repurchase_pt, 'pt')} 环比`,
      up: o.deltas.repurchase_pt >= 0,
      // 复购率的"明细"是订单 —— 订单现在是**农产品管理页里的一个 tab**，
      // 不再是独立一级页面（卖货与发货本就不该分家）
      to: { path: '/admin/products', query: { tab: 'orders', range: o.range } },
    },
    {
      key: 'risks',
      // ★ 标签是「未闭环」不是「待处置」：后端 `open_risks` 的判据是
      //   `status != CLOSED`（见 OpsServiceImpl），**含已建单未完结的**。
      //   叫"待处置"会把"处置中"的那几条也算成"还没人管"，是过度声称。
      //   本页下面的风险列表与跳转目标都用同一个口径（`status=UNCLOSED`）。
      //
      // 带周期前缀：`open_risks` 也是**按统计区间筛**的（按 `range_from`/
      // `range_to` 过滤 stat_date），四档实测 17 / 22 / 24 / 24。
      label: `${p}未闭环风险`,
      value: String(o.open_risks),
      unit: '件',
      delta: o.open_risks > 0 ? '需处置' : '运行正常',
      up: false,
      alert: o.open_risks > 0,
      // 带上**后端回显的档位**：进了 Risks.vue 看到的区间与驾驶舱是同一个。
      // `status=UNCLOSED` 而不是 `OPEN` —— 后者只数 OPEN，与卡片上的数对不上
      // （近 7 天档：卡片 22 / 页面 17）。Risks.vue 的 `UNCLOSED` 用的判据
      // 与后端一字不差，所以行数与卡片必然相等。
      to: { path: '/admin/risks', query: { tab: 'risks', status: 'UNCLOSED', range: o.range } },
    },
  ]
})

// ============================================================
// 四大业务总览卡
// ============================================================
/** 一张总览卡的形状。数字与图都由 `biz` 现算，没有任何写死的经营数字 */
interface BizStat {
  label: string
  value: string
  unit: string
}
interface BizCard {
  key: string
  name: string
  icon: string
  color: string
  to: RouteLocationRaw
  stats: BizStat[]
  top: string[]
  topLabel: string
  option: Record<string, unknown>
}

/** 迷你图的外框。**无轴、无网格、无图例** —— 它给的是"形状"，不是读数 */
const MINI_GRID = { left: 2, right: 2, top: 8, bottom: 2 }
const MINI_TOOLTIP = {
  backgroundColor: 'rgba(9,26,20,0.94)',
  borderColor: 'rgba(146,178,165,0.28)',
  borderWidth: 1,
  textStyle: { color: '#e8f1ec', fontSize: 11 },
}

/** 迷你柱：用于"排行"型概况（农产品产地 / 餐饮 / 住宿） */
function miniBar(names: string[], values: number[], color: string) {
  return {
    grid: MINI_GRID,
    xAxis: { type: 'category', data: names, show: false },
    yAxis: { type: 'value', show: false },
    tooltip: {
      ...MINI_TOOLTIP,
      trigger: 'item',
      formatter: (p: { name: string; value: number }) =>
        `${p.name}<br/><b>${Number(p.value).toLocaleString()}</b>`,
    },
    series: [
      {
        type: 'bar',
        data: values,
        barWidth: '52%',
        itemStyle: { color, borderRadius: [2, 2, 0, 0] },
      },
    ],
  }
}

/** 迷你面积线：用于"趋势"型概况（乡村景点客流） */
function miniLine(values: number[], color: string) {
  return {
    grid: MINI_GRID,
    xAxis: { type: 'category', data: values.map((_, i) => i), show: false, boundaryGap: false },
    yAxis: { type: 'value', show: false },
    tooltip: {
      ...MINI_TOOLTIP,
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: 'rgba(146,178,165,0.4)' } },
      formatter: (ps: { value: number }[]) => `<b>${Number(ps[0]?.value ?? 0).toLocaleString()}</b> 人次`,
    },
    series: [
      {
        type: 'line',
        data: values,
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2, color },
        areaStyle: { color, opacity: 0.16 },
      },
    ],
  }
}

/** 从 KPI 列表里按键取值。**按键不按位置** —— 位置会随业态变化，键不会 */
function kpiVal(b: OpsBusiness | undefined, key: string, fallback = '—') {
  return b?.kpis.find((k) => k.key === key)?.value ?? fallback
}

/**
 * 四张总览卡。
 *
 * 每张卡的**下钻都带 `range`** —— 这是"驾驶舱切到近 30 日、点进去却是今日"
 * 那个坑的通用解法（跨页传参按参数全集对齐）。
 */
const bizCards = computed<BizCard[]>(() => {
  const o = ops.value
  const r = o?.range ?? range.value
  const rural = biz.value.rural
  const product = biz.value.product
  const food = biz.value.food
  const lodging = biz.value.lodging

  const ruralTop = rural?.top ?? []
  const productTop = product?.top ?? []
  const foodTop = food?.top ?? []
  const lodgingTop = lodging?.top ?? []

  return [
    {
      key: 'rural',
      name: '乡村景点',
      icon: '⛰',
      color: '#2a6f5b',
      to: { path: '/admin/attractions', query: { seg: 'rural', range: r } },
      stats: [
        { label: '总到访', value: kpiVal(rural, 'visitors'), unit: '人次' },
        { label: '平均承载率', value: kpiVal(rural, 'usage'), unit: '%' },
        {
          label: '乡村到访占比',
          value: o ? (o.rural_ratio * 100).toFixed(1) : '—',
          unit: '%',
        },
        { label: '高负荷资源', value: kpiVal(rural, 'high'), unit: '个' },
      ],
      top: ruralTop.slice(0, 3).map((t) => t.name),
      topLabel: '热门 Top3',
      option: miniLine((rural?.trend ?? []).map((t) => t.visitors), '#2a6f5b'),
    },
    {
      key: 'product',
      name: '农产品',
      icon: '▣',
      color: '#c09a4e',
      to: { path: '/admin/products', query: { range: r } },
      stats: [
        { label: '销售额', value: kpiVal(product, 'sales'), unit: '元' },
        { label: '乡村复购率', value: kpiVal(product, 'repurchase'), unit: '%' },
        { label: '购买笔数', value: kpiVal(product, 'buys'), unit: '笔' },
        { label: 'Top 产地', value: productTop[0]?.name ?? '—', unit: '' },
      ],
      top: productTop.slice(0, 3).map((t) => t.name),
      topLabel: '产地 Top3',
      option: miniBar(
        productTop.slice(0, 6).map((t) => t.name.replace(/^[^·]*·/, '')),
        productTop.slice(0, 6).map((t) => t.value),
        '#c09a4e'
      ),
    },
    {
      key: 'food',
      name: '餐饮',
      icon: '◍',
      color: '#2e7bc4',
      to: { path: '/admin/restaurants', query: { range: r } },
      stats: [
        { label: '资源数', value: kpiVal(food, 'resources'), unit: '个' },
        { label: '客流', value: kpiVal(food, 'visitors'), unit: '人次' },
        { label: '承载均值', value: kpiVal(food, 'usage'), unit: '%' },
        { label: '高负荷', value: kpiVal(food, 'high'), unit: '个' },
      ],
      top: foodTop.slice(0, 3).map((t) => t.name),
      topLabel: '热度 Top3',
      option: miniBar(
        foodTop.map((t) => t.name),
        foodTop.map((t) => t.value),
        '#2e7bc4'
      ),
    },
    {
      key: 'lodging',
      name: '住宿',
      icon: '⌂',
      color: '#71a996',
      to: { path: '/admin/hotels', query: { range: r } },
      stats: [
        { label: '资源数', value: kpiVal(lodging, 'resources'), unit: '个' },
        { label: '客流', value: kpiVal(lodging, 'visitors'), unit: '人次' },
        { label: '承载均值', value: kpiVal(lodging, 'usage'), unit: '%' },
        { label: '高负荷', value: kpiVal(lodging, 'high'), unit: '个' },
      ],
      top: lodgingTop.slice(0, 3).map((t) => t.name),
      topLabel: '热度 Top3',
      option: miniBar(
        lodgingTop.map((t) => t.name),
        lodgingTop.map((t) => t.value),
        '#71a996'
      ),
    },
  ] as BizCard[]
})
</script>

<template>
  <div class="dash">
    <header class="dash__head">
      <div>
        <span class="eyebrow dash__eyebrow">管理驾驶舱</span>
        <h1 class="h1 dash__title">汉中文旅运营总览</h1>
        <p class="dash__sub">客流 · 乡村业态 · 农产品消费 · 风险事件，一屏掌握</p>
      </div>
      <div class="dash__meta">
        <!--
          全局统计区间。四个档位对应后端的 `range` 参数 ——
          切换会让**后端重新聚合**，前端不算任何指标。
          「今日」= 数据包里最新的一天（基准日），不是浏览器系统日期。
        -->
        <div class="ranges" role="group" aria-label="统计区间">
          <button
            v-for="r in RANGES"
            :key="r.key"
            type="button"
            class="ranges__b"
            :class="{ 'ranges__b--on': r.key === range }"
            :disabled="switching"
            @click="pickRange(r.key)"
          >
            {{ r.label }}
          </button>
        </div>
        <!--
          周期文案**由后端给**（`period_label`），不在这里拼：
          前端自己写"近 7 日"，后端哪天把窗口改成 14 天，这行字不会跟着变，
          而屏幕上的数字已经变了 —— 这种不一致不会报错，只会一直错下去。
        -->
        <span class="dash__time">数据周期：{{ ops?.period_label ?? '—' }}</span>
        <!--
          "更新中"用 visibility 切换而不是 v-if：v-if 会让它在出现/消失时
          改变这一行的宽度，把右边的 SIMULATED 徽标推来推去 ——
          切个日期整行文字挪一下，看起来像页面跳了。
        -->
        <span class="dash__busy" :class="{ 'is-on': switching }">更新中…</span>
        <span class="badge-sim">SIMULATED</span>
      </div>
    </header>

    <!-- 操作提示（建单成功 / 失败）。结构由 useNotice 约定，样式在下方 -->
    <Transition name="notice">
      <div v-if="notice" class="notice" :class="`notice--${notice.type}`">{{ notice.text }}</div>
    </Transition>

    <!--
      骨架屏只在**首次加载**出现（`!ops`）。切区间时保留旧数据 + 顶部"更新中"，
      否则每切一次档位整屏闪一下骨架，看起来像刷新了页面。
    -->
    <div v-if="loading && !ops" class="dash__loading">
      <div v-for="i in 5" :key="i" class="skeleton" style="height: 96px; border-radius: 10px" />
      <div class="skeleton" style="height: 300px; border-radius: 10px; grid-column: span 2" />
      <div class="skeleton" style="height: 300px; border-radius: 10px" />
    </div>

    <div v-else-if="error" class="state-error">
      <p>{{ error }}</p>
      <button class="btn btn-ghost btn-sm" @click="load()">重新加载</button>
    </div>

    <template v-else-if="ops">
      <!--
        KPI 卡：**只加点击行为，不改视觉**。做成真 `<button>` 而不是带 @click 的
        div —— 键盘能 Tab 到、回车能进，读屏也知道这是可操作的。
        驾驶舱只负责"发现问题"，点进去由既有业务页"解决问题"。
      -->
      <div class="kpis">
        <button
          v-for="k in kpis"
          :key="k.key"
          type="button"
          class="kpi"
          :title="KPI_HINT[k.key] || '点开查看明细'"
          @click="goTo(k.to)"
        >
          <span class="kpi__label">{{ k.label }}</span>
          <div class="kpi__row">
            <span class="num kpi__value">{{ k.value }}</span>
            <span class="kpi__unit">{{ k.unit }}</span>
          </div>
          <span class="kpi__delta" :class="{ 'kpi__delta--down': !k.up, 'kpi__delta--alert': k.alert }">
            {{ k.delta }}
          </span>
        </button>
      </div>

      <!--
        口径脚注。**为什么要有这一行**：上面五张卡里，"乡村复购率"与"农产品销售额"
        这两个词各自都能被读成至少两种意思 ——
          · 复购率：是"用户级复购率"（离境消费的用户里有多少又买了）还是
            "笔数比"（复购笔数 ÷ 购买笔数）？本系统只有后者，因为统计表是
            "资源点 × 天"的汇总，**没有用户身份维度**。
          · 销售额：是"单品级"还是"产地级"？本系统是**产地（乡村点）级**，
            因为产品只有 16 条、没有逐单销量流水。
        不写这一行，屏幕上没人能分辨；写上之后，答辩时被追问也能当场指出来。
        放在 KPI 区下方而不是塞进卡片：卡片高度会被拉得参差不齐，
        而这一行是**五张卡共用的**口径，本就该在一处说一次。
      -->
      <p class="kpi__gloss">
        口径：农产品销售额 = 乡村点关联产品均价 × 购买笔数 · 乡村复购率 = 复购笔数 / 购买笔数
        · 均为所选统计区间内的<b>乡村点合计</b>（非用户级）· 全部为仿真数据
      </p>

      <!--
        第二层：四大业务总览。
        每块 = 3~4 个数字 + 迷你图 + Top3，**不放表格** —— 驾驶舱的职责是
        "5 秒看懂全局"，把四个业务页的内容搬过来，它就变成第二个大杂烩了。
        整块是一个按钮：进去才是那条业务线的管理中心。
      -->
      <div class="biz">
        <button
          v-for="b in bizCards"
          :key="b.key"
          type="button"
          class="biz__card"
          :title="`进入${b.name}管理`"
          @click="goTo(b.to)"
        >
          <header class="biz__head">
            <span class="biz__icon" :style="{ color: b.color }">{{ b.icon }}</span>
            <span class="biz__name">{{ b.name }}</span>
            <span class="biz__range">{{ rangePrefix }}</span>
          </header>

          <div class="biz__stats">
            <div v-for="s in b.stats" :key="s.label" class="biz__stat">
              <span class="biz__stat-label">{{ s.label }}</span>
              <span class="biz__stat-value">
                {{ s.value }}<em v-if="s.unit && s.value !== '—'">{{ s.unit }}</em>
              </span>
            </div>
          </div>

          <!-- variant="spark" 是给验收探针留的标记：这几张 54px 的迷你图
               和大图不是一类东西，探针按 `.echart` 出现顺序取图时必须能跳过它们 -->
          <EChart :option="b.option" height="54px" variant="spark" />

          <div class="biz__foot">
            <ol class="biz__top">
              <li v-for="(n, i) in b.top" :key="n">
                <i>{{ i + 1 }}</i>
                <span>{{ n }}</span>
              </li>
              <li v-if="!b.top.length" class="biz__top-empty">暂无数据</li>
            </ol>
            <span class="biz__go">{{ b.topLabel }}</span>
          </div>
        </button>
      </div>

      <!-- 趋势 + 业态构成 -->
      <div class="grid grid-3 dash__row">
        <section class="panel panel--span2">
          <div class="panel__head">
            <h2 class="h3 panel__title">客流与承载趋势</h2>
            <!--
              副标题这一行是**双用的**：没点图时是口径提示，点了柱子就换成
              那一天的数字。用既有元素承载详情，就不必新开一块卡片 ——
              而且换文案不改变任何尺寸，点一下图不会把下面的内容顶下去。
            -->
            <span v-if="trendDetail" class="pick" title="再点同一根柱子可取消">
              {{ trendDetail }}
            </span>
            <span v-else class="muted small">柱：核心景区到访 · 线：承载占用率</span>
          </div>
          <EChart :option="trendOption" height="272px" @click="onTrendClick" />
        </section>

        <section class="panel">
          <div class="panel__head">
            <h2 class="h3 panel__title">业态客流构成</h2>
            <span v-if="mixDetail" class="pick" title="再点同一块可取消">{{ mixDetail }}</span>
            <span v-else class="muted small">点扇形看占比</span>
          </div>
          <EChart :option="mixOption" height="272px" @click="onMixClick" />
        </section>
      </div>

      <!-- 冷热失衡 + 农产品 -->
      <div class="grid grid-2 dash__row">
        <section class="panel">
          <div class="panel__head">
            <h2 class="h3 panel__title">冷热失衡：景区高位 vs 乡村闲置</h2>
            <span v-if="imbalanceDetail" class="pick" title="再点同一个区县可取消">
              {{ imbalanceDetail }}
            </span>
            <span v-else class="muted small">按区县 · 点条形看明细</span>
          </div>
          <EChart :option="imbalanceOption" height="260px" @click="onImbalanceClick" />
        </section>

        <section class="panel">
          <div class="panel__head">
            <!--
              标题写"按产地归集"，是因为合成数据只支持到**乡村点级**销售额，
              没有单品级的销量 —— 图上每一根柱子是一个产地，不是一款产品。
              不写清的话，看屏的人会以为是单品排行（见 OpsSnapshotVO 的字段注释）。
            -->
            <h2 class="h3 panel__title">乡村好物销售额 Top 6</h2>
            <span v-if="productDetail" class="pick" title="再点同一根柱子可取消">
              {{ productDetail }}
            </span>
            <span v-else class="muted small">按产地归集</span>
          </div>
          <EChart :option="productOption" height="260px" @click="onProductClick" />
        </section>
      </div>

      <!-- 风险与 AI 归因 -->
      <div class="grid grid-3 dash__row">
        <section class="panel panel--span2">
          <div class="panel__head">
            <h2 class="h3 panel__title">风险事件（规则引擎判定）</h2>
            <span class="muted small">{{ rangePrefix }}{{ ops.open_risks }} 件未闭环</span>
          </div>
          <div v-if="!ops.risks.length" class="empty">
            <div class="empty__title">当前无风险事件</div>
          </div>
          <ul v-else class="risks">
            <li v-for="r in ops.risks" :key="r.id" class="risk">
              <span class="tag" :class="r.level === 'HIGH' ? 'tag-danger' : 'tag-warn'">
                {{ r.level === 'HIGH' ? '高' : '中' }}
              </span>
              <div class="risk__body">
                <div class="risk__title">{{ r.title }} · {{ r.poi_name }}</div>
                <div class="risk__detail">{{ r.detail }}</div>
              </div>
              <span class="risk__type">{{ RISK_TYPE_LABEL[r.type] ?? r.type }}</span>
            </li>
          </ul>
        </section>

        <!--
          M7 起这一段**真的有模型参与**，标题才改回「AI」。
          上一轮刻意叫「运营建议」并标注"规则引擎判定"，是因为那时从取数到成文
          全程确定 —— 标成 AI 生成经不起追问（"这段是哪个模型生成的？"）。

          标题写「解读」而不是「建议」：模型解释的是**已经算好**的数，
          判定仍由 M5 的规则引擎与统计做。这个分工是三个创新点之一，
          改标题的时候不能把它改掉。

          三件事界面必须显示清楚，否则这一块就只是不可核对的"AI 说的"：
            · 模式（模型生成 / 离线回放 / 暂不可用）—— 回放不能冒充刚生成
            · 依据（由后端代码算，不由模型复述）—— 能对着上面的图核
            · stale（回放的数据与当前面板不同批）—— 必须说明是哪一批
        -->
        <section class="panel panel--ai">
          <div class="panel__head">
            <h2 class="h3 panel__title">AI 运营解读</h2>
            <span v-if="analysis" class="tag" :class="modeTone">
              {{ OPS_MODE_LABEL[analysis.mode] }}
            </span>
          </div>

          <!--
            维度切换。方案 §14 写的是"指标卡 + 「让 AI 解读」按钮"，落地改成
            面板内的维度切换：点上面的指标卡、结果却出现在屏幕另一端，
            是自找的困惑。一次只解读一组指标也是刻意的 —— 全量塞进提示词
            会把输出摊薄成一句一段的泛泛之谈（见 ops_analysis.py 的 FOCUSES）。
          -->
          <div class="focuses">
            <button
              v-for="f in OPS_FOCUSES"
              :key="f.key"
              class="focus"
              :class="{ 'focus--on': f.key === focus }"
              :disabled="analyzing"
              @click="runAnalysis(f.key)"
            >
              {{ f.label }}
            </button>
          </div>

          <!-- 加载 -->
          <div v-if="analyzing" class="ai__loading">
            <i class="ai__spin" aria-hidden="true" />正在解读「{{ focusLabel }}」…
          </div>

          <!--
            出错：连不上后端 / 未登录 / AI 未启用。
            这与"模型没解读出来"是**两回事** —— 后者后端正常返回 200，
            只是 mode=unavailable，走下面的正常分支。
          -->
          <div v-else-if="analysisError" class="ai__error">
            <p>{{ analysisError }}</p>
            <button class="btn btn-ghost btn-sm" @click="runAnalysis(focus)">重试</button>
          </div>

          <!-- 还没点过。**不自动跑**：进页面就调模型等于每看一次烧一次 token -->
          <p v-else-if="!analysis" class="muted small ai__hint">
            选一个维度，让模型解读这一组指标。指标由规则引擎与统计算好，模型只负责解释。
          </p>

          <template v-else>
            <!-- 非 llm 模式必须给说明：回放要标明，不可用要说原因 -->
            <p v-if="analysis.note" class="ai__note">{{ analysis.note }}</p>

            <template v-if="analysis.sections.length">
              <div
                v-for="s in analysis.sections"
                :key="s.kind"
                class="ai__sec"
                :class="`ai__sec--${s.kind}`"
              >
                <div class="ai__sec-title">{{ s.title }}</div>
                <!-- 回答是纯文本段落，只把 **加粗** 转成 strong（与问答页共用一份实现） -->
                <p class="ai__text" v-html="inlineText(s.text)" />
              </div>

              <!--
                依据**默认收起**：它的用途是"想核的时候能核"，不是每次都要读。
                `<details>` 既省高度又保留可核对性 —— 换成"干脆不显示"就
                把这一块最有说服力的东西丢了。
              -->
              <details v-if="analysis.basis.length" class="basis">
                <summary>模型依据的是这些数（{{ analysis.basis.length }} 项）</summary>
                <ul>
                  <li v-for="b in analysis.basis" :key="b.label">
                    <span>{{ b.label }}</span><b>{{ b.value }}</b>
                  </li>
                </ul>
              </details>
            </template>
            <p v-else class="muted small">{{ analysis.note || '这次没有生成解读' }}</p>
          </template>

          <div class="ai__foot">
            <span class="muted small">
              <template v-if="analysis?.mode === 'llm'">
                由 {{ analysis.model }} 生成 · {{ when(analysis.generated_at) }}
              </template>
              <template v-else-if="analysis">
                {{ OPS_MODE_LABEL[analysis.mode] }}<template v-if="analysis.model">
                  · {{ analysis.model }}</template
                >
              </template>
              <template v-else>指标由规则引擎判定，模型只负责解释</template>
            </span>
            <!--
              按钮对**第一条待处置**的风险建单（不是"全市最严重的"，
              快照里只有 5 条，见 topOpenRisk 的注释）。
              全都处置过时按钮置灰而不是隐藏：隐藏会让人以为这一页没有这个能力，
              置灰配上文案才说得清"是暂时没得做"。
            -->
            <button
              class="btn btn-gold btn-sm"
              :disabled="!topOpenRisk || creating"
              @click="createTopWorkOrder"
            >
              {{ creating ? '建单中…' : topOpenRisk ? '生成分流工单' : '暂无可建单风险' }}
            </button>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.dash__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-5);
  flex-wrap: wrap;
  margin-bottom: var(--sp-6);
}
.dash__eyebrow {
  color: var(--gold-300);
}
.dash__eyebrow::before {
  background: var(--gold-300);
}
.dash__title {
  margin-top: var(--sp-2);
  color: #fff;
}
.dash__sub {
  margin-top: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--text-3);
}
.dash__meta {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.dash__time {
  font-size: var(--fs-sm);
  color: var(--text-3);
}
/*
  "更新中…"常驻占位、用 visibility 开关：v-if 会让它在出现/消失时
  改变这一行的宽度，把右边的 SIMULATED 徽标推来推去 —— 切个日期
  整行文字挪一下，看起来像页面跳了。
*/
.dash__busy {
  visibility: hidden;
  font-size: var(--fs-cap);
  color: var(--gold-300);
}
.dash__busy.is-on {
  visibility: visible;
}

/* ---------- 全局统计区间（分段控件） ---------- */
.ranges {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: rgba(11, 33, 25, 0.5);
}
.ranges__b {
  padding: 4px 10px;
  font-size: var(--fs-cap);
  color: var(--text-3);
  background: transparent;
  border: none;
  border-radius: calc(var(--r-sm) - 2px);
  cursor: pointer;
  white-space: nowrap;
  transition: color 0.15s, background 0.15s;
}
.ranges__b:hover:not(:disabled) {
  color: var(--gold-300);
}
.ranges__b--on {
  color: #0b2119;
  background: var(--gold-300);
  font-weight: 600;
}
.ranges__b:disabled {
  cursor: default;
  opacity: 0.7;
}
.badge-sim {
  height: 24px;
  padding: 0 10px;
  display: inline-flex;
  align-items: center;
  border-radius: var(--r-sm);
  font-size: var(--fs-cap);
  letter-spacing: 0.08em;
  color: var(--gold-300);
  background: rgba(192, 154, 78, 0.14);
  border: 1px solid rgba(192, 154, 78, 0.32);
}

/* KPI */
.kpis {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--sp-4);
  margin-bottom: var(--sp-5);
}
.kpi {
  /* 这是 <button>，先把浏览器默认样式抹掉，保持原来 div 的观感 */
  width: 100%;
  text-align: left;
  font: inherit;
  color: inherit;
  cursor: pointer;
  padding: var(--sp-4) var(--sp-5);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  transition: border-color var(--dur-2) var(--ease), transform var(--dur-2) var(--ease);
}
.kpi:hover {
  border-color: var(--line-strong);
  transform: translateY(-2px);
}
/* 键盘可达：只在键盘聚焦时描边，鼠标点不出这个圈 */
.kpi:focus-visible {
  outline: 2px solid var(--gold-300);
  outline-offset: 2px;
}
.kpi__label {
  font-size: var(--fs-cap);
  color: var(--text-3);
  letter-spacing: 0.06em;
}
.kpi__row {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin-top: var(--sp-2);
}
.kpi__value {
  font-size: 28px;
  font-weight: 700;
  color: #fff;
  line-height: 1.1;
}
.kpi__unit {
  font-size: var(--fs-sm);
  color: var(--text-3);
}
.kpi__delta {
  display: inline-block;
  margin-top: var(--sp-2);
  font-size: var(--fs-cap);
  color: var(--brand-300);
}
.kpi__delta--down {
  color: var(--text-3);
}
.kpi__delta--alert {
  color: var(--gold-300);
}
/*
  口径脚注。紧贴 KPI 区下方，所以要把 KPI 区自己的下边距收掉一半 ——
  否则这一行会飘在两张卡中间，看起来像属于下面那个面板。
*/
.kpi__gloss {
  margin: calc(var(--sp-5) * -1 + var(--sp-3)) 0 var(--sp-5);
  font-size: var(--fs-cap);
  color: var(--text-3);
  line-height: 1.7;
}
.kpi__gloss b {
  color: var(--text-2);
  font-weight: 600;
}

/*
  四大业务总览。四列等宽，每列一张可点的卡。
  信息密度刻意压得很低：3~4 个数字 + 一张 54px 的迷你图 + 3 行排行 ——
  目标是"扫一眼就知道这条业务线今天怎么样"，不是"在这里做管理"。
*/
.biz {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-4);
  margin-bottom: var(--sp-5);
}
.biz__card {
  /* 同样是 <button>：键盘可达、读屏知道可操作。抹掉默认样式保持卡片观感 */
  width: 100%;
  text-align: left;
  font: inherit;
  color: inherit;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-4);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  transition: border-color var(--dur-2) var(--ease), transform var(--dur-2) var(--ease);
}
.biz__card:hover {
  border-color: var(--line-strong);
  transform: translateY(-2px);
}
.biz__card:focus-visible {
  outline: 2px solid var(--gold-300);
  outline-offset: 2px;
}
.biz__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.biz__icon {
  font-size: 15px;
  line-height: 1;
}
.biz__name {
  font-family: var(--font-display);
  font-size: var(--fs-md);
  color: var(--text);
  letter-spacing: 0.04em;
}
/* 周期前缀只出现一次（卡头），不逐条挂在数字上 —— 挂四遍会把标签挤成两行 */
.biz__range {
  margin-left: auto;
  font-size: var(--fs-cap);
  color: var(--gold-300);
  background: rgba(192, 154, 78, 0.12);
  border: 1px solid rgba(192, 154, 78, 0.28);
  border-radius: var(--r-sm);
  padding: 1px 6px;
  white-space: nowrap;
}
.biz__stats {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sp-2) var(--sp-3);
}
.biz__stat {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.biz__stat-label {
  font-size: var(--fs-cap);
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.biz__stat-value {
  font-family: var(--font-display);
  font-size: 19px;
  line-height: 1.2;
  color: var(--text);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.biz__stat-value em {
  margin-left: 3px;
  font-family: var(--font-sans);
  font-size: 11px;
  font-style: normal;
  color: var(--text-3);
}
.biz__foot {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-2);
  border-top: 1px solid var(--line);
  padding-top: var(--sp-3);
}
.biz__top {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.biz__top li {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-cap);
  color: var(--text-2);
  min-width: 0;
}
.biz__top li i {
  flex: none;
  width: 14px;
  font-style: normal;
  color: var(--gold-300);
  font-variant-numeric: tabular-nums;
}
.biz__top li span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.biz__top-empty {
  color: var(--text-3);
}
.biz__go {
  flex: none;
  font-size: var(--fs-cap);
  color: var(--text-3);
  white-space: nowrap;
}

/* 面板 */
.dash__row {
  margin-bottom: var(--sp-5);
  gap: var(--sp-5);
}
.panel {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  padding: var(--sp-5);
  min-width: 0;
}
.panel--span2 {
  grid-column: span 2;
}
.panel__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-4);
}
.panel__title {
  color: #fff;
}
.panel .muted,
.panel .small {
  color: var(--text-3);
}
/*
  图表选中项的详情。**就放在 panel__head 的副标题位**，所以它必须与原来的
  提示文字占同一行、同一字号 —— 否则点一下图，面板标题那行的高度会变。
  过长时省略号截断：宁可截断，也不要让副标题折成两行把图往下推。
*/
.pick {
  font-size: var(--fs-cap);
  color: var(--gold-300);
  text-align: right;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

/* 风险 */
.risks {
  display: flex;
  flex-direction: column;
}
.risk {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-3);
  padding: var(--sp-3) 0;
  border-bottom: 1px solid var(--line);
}
.risk:last-child {
  border-bottom: none;
}
.risk__body {
  flex: 1;
  min-width: 0;
}
.risk__title {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: #fff;
}
.risk__detail {
  margin-top: 2px;
  font-size: var(--fs-cap);
  color: var(--text-3);
  line-height: 1.7;
}
.risk__type {
  font-size: 10px;
  letter-spacing: 0.08em;
  color: var(--text-3);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 2px 6px;
  flex: none;
}

/* AI 卡 */
.panel--ai {
  background: linear-gradient(180deg, rgba(192, 154, 78, 0.1) 0%, var(--surface) 46%);
  border-color: rgba(192, 154, 78, 0.3);
}
.ai__text {
  font-size: var(--fs-sm);
  color: var(--text-2);
  line-height: 1.85;
}
/*
  模型输出的加粗是 `**x**` 经 inlineText 转出来的 <strong>（v-html 渲染），
  而页面里手写的文案用的是 <b>。两者都要高亮 —— 只写 <b> 的话，
  模型强调的关键数字会悄悄变成普通字重，而"哪些字被强调了"是这段解读
  可读性的一半。
*/
.ai__text b,
.ai__text strong {
  color: var(--gold-300);
  font-weight: 600;
}

/* ---------- 维度切换 ---------- */
.focuses {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin: var(--sp-3) 0 var(--sp-4);
}
.focus {
  padding: 4px 11px;
  font-size: var(--fs-xs);
  color: var(--text-3);
  background: transparent;
  border: 1px solid var(--line);
  border-radius: 999px;
  cursor: pointer;
  transition:
    color 0.15s,
    border-color 0.15s,
    background 0.15s;
}
.focus:hover:not(:disabled) {
  color: var(--gold-300);
  border-color: rgba(192, 154, 78, 0.5);
}
.focus--on {
  color: var(--gold-300);
  background: rgba(192, 154, 78, 0.14);
  border-color: rgba(192, 154, 78, 0.55);
}
.focus:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* ---------- 加载 / 错误 / 空态 ---------- */
.ai__loading {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-4) 0;
  font-size: var(--fs-sm);
  color: var(--text-3);
}
.ai__spin {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(192, 154, 78, 0.3);
  border-top-color: var(--gold-300);
  border-radius: 50%;
  animation: ai-spin 0.8s linear infinite;
}
@keyframes ai-spin {
  to {
    transform: rotate(360deg);
  }
}
.ai__error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  border: 1px solid rgba(190, 90, 90, 0.35);
  border-radius: 8px;
  background: rgba(190, 90, 90, 0.08);
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.ai__hint {
  padding: var(--sp-4) 0;
  line-height: 1.75;
}
/* 降级说明（离线回放 / 暂不可用）。用左侧竖线而不是整块底色：
   它是"补充说明"，不该在视觉上盖过下面的三段正文 */
.ai__note {
  margin-bottom: var(--sp-4);
  padding: var(--sp-2) var(--sp-3);
  border-left: 2px solid rgba(192, 154, 78, 0.5);
  font-size: var(--fs-xs);
  color: var(--text-3);
  line-height: 1.75;
}

/* ---------- 三段正文 ---------- */
.ai__sec + .ai__sec {
  margin-top: var(--sp-4);
}
.ai__sec-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--text-3);
}
.ai__sec-title::before {
  content: '';
  width: 3px;
  height: 12px;
  border-radius: 2px;
  background: var(--gold-500);
}
/* 「建议做什么」是唯一能直接落地的一段，给它多一点重量 */
.ai__sec--todo .ai__sec-title {
  color: var(--gold-300);
}
.ai__sec--todo .ai__sec-title::before {
  background: var(--gold-300);
}

/* ---------- 依据（默认收起） ---------- */
.basis {
  margin-top: var(--sp-5);
  font-size: var(--fs-xs);
}
.basis summary {
  cursor: pointer;
  color: var(--text-3);
  list-style: none;
}
.basis summary::-webkit-details-marker {
  display: none;
}
.basis summary::before {
  content: '▸ ';
  color: var(--gold-500);
}
.basis[open] summary::before {
  content: '▾ ';
}
.basis summary:hover {
  color: var(--gold-300);
}
.basis ul {
  margin: var(--sp-3) 0 0;
  padding: 0;
  list-style: none;
}
.basis li {
  display: flex;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: 5px 0;
  border-bottom: 1px dashed var(--line);
  color: var(--text-3);
}
.basis li b {
  color: var(--text-2);
  font-weight: 600;
  text-align: right;
}
.ai__foot {
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.dash__loading {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-4);
}

.state-error {
  padding: var(--sp-6);
  text-align: center;
  border: 1px dashed var(--line-strong);
  border-radius: var(--r-lg);
  color: #e8a08c;
  font-size: var(--fs-sm);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-3);
}

/* ---------- 操作提示条（与 Orders.vue 同款，逻辑在 useNotice） ---------- */
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

@media (max-width: 1400px) {
  .kpis {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  /* 四大业务总览：窄屏从 4 列降到 2 列 —— 4 列时每张卡的四个数字会被挤成两行 */
  .biz {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 1080px) {
  .kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .grid-3,
  .grid-2 {
    grid-template-columns: minmax(0, 1fr);
  }
  .panel--span2 {
    grid-column: span 1;
  }
  .dash__loading {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 720px) {
  .kpis {
    grid-template-columns: minmax(0, 1fr);
  }
  .biz {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
