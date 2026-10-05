<script setup lang="ts">
/**
 * 风险与工单（M5 运营端）
 *
 * ============================================================
 * 2026-10-03 重构：从「卡片堆叠」改为「运营工作台」
 * ============================================================
 * 上一版把每条风险做成一张大卡，卡里再嵌一张「分流方案」卡，同一屏上
 * 同时出现：标题 / 描述 / 建议 / 分流方案 / 候选 / 建单按钮 / 公告按钮 /
 * 时间 / 规则。一条风险 300–400px 高，一屏放不下三条 —— 而运营真正要
 * 回答的问题只有一个：**哪几条需要我现在动手**。
 *
 * 这一版的信息架构（自上而下）：
 *   页头（标题 + 重新扫描）
 *   → 统计条（待处理 / 已建单 / 已闭环 / 高风险，**同时就是筛选器**）
 *   → 一级业务 Tab（风险事件 / 工单 / 分流公告）
 *   → 紧凑表格，一行一条风险
 *   → 点「查看 / 处理」开右侧抽屉，按运营处置的真实顺序分四段
 *
 * 四条刻意不做的事：
 *   ① **列表里不展开分流候选。** 候选是"决定要分流时才需要看"的信息，
 *      默认展开会把每条风险都撑成一屏。它现在在抽屉的第二段。
 *   ② **不再有卡片套卡片。** 抽屉内部只用分隔线分节，每节都不加边框 ——
 *      边框一多，"哪块是重点"就靠颜色去猜了。
 *   ③ **每行只有一个操作按钮**（查看 / 处理），其余动作收进抽屉底部的
 *      「更多」。并排三个按钮等于没有重点。
 *   ④ **不用徽标承载状态。** 等级、状态、公告态一律是着色文字，
 *      一屏十几个胶囊会把眼睛带偏。
 *
 * ============================================================
 * 业务逻辑一行未改
 * ============================================================
 * 接口、状态机、权限、判定全不动：建单仍是 `POST /admin/risks/{id}/work-order`，
 * 发布仍是 `PATCH /admin/diversion-notices/{id}`，扫描仍是 `POST /admin/ops/scan`。
 * 这一轮只换信息架构与呈现。
 *
 * ============================================================
 * 「设计容量 / 超载比例」是怎么来的（没有改后端）
 * ============================================================
 * `risk_event` 只给承载率（`metric_value`），没有设计容量。但承载率 =
 * 当日到访 ÷ 设计容量，而 `GET /api/stats/pois`（公开接口）同时给
 * `today_visitors` 与 `capacity_usage` —— 两者相除即得容量。
 * 已逐点核对过 42 个资源点：反推值与 `poi.capacity` **逐值一致**。
 *
 * ★ 只对 `OVERLOAD` 成立。`RURAL_IDLE` 的 `metric_value` 是**区县景区
 *   平均值**（不是该点的承载），拿它去反推容量会得到一个假数 ——
 *   所以那条规则下「设计容量」显示 `—`，不做推算。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import EChart from '@/components/EChart.vue'
import {
  createWorkOrder,
  getPoiStats,
  getRisks,
  getWorkOrders,
  rescanRisks,
  updateWorkOrder,
} from '@/api/ops'
import {
  createNoticeDraft,
  getAdminDiversionNotices,
  updateDiversionNotice,
} from '@/api/diversion'
import { ApiError } from '@/api/http'
import { useNotice } from '@/composables/useNotice'
import { reloadDiversionNotices } from '@/composables/useDiversionNotices'
import { useSessionStore } from '@/stores/session'
import { areaLineOption, distributionBarOption, doughnutOption } from '@/utils/adminCharts'
import { when } from '@/utils/format'
import {
  ACTION_TYPE_LABEL,
  NOTICE_STATUS_LABEL,
  RISK_STATUS_LABEL,
  RISK_TYPE_LABEL,
  WORK_ORDER_STATUS_LABEL,
  type DiversionNotice,
  type PoiStat,
  type RiskEvent,
  type RiskLevel,
  type RiskStatus,
  type WorkOrder,
  type WorkOrderStatus,
} from '@/types'

const session = useSessionStore()
const { notice, say } = useNotice()

/**
 * 深链参数（2026-10-04 加，给驾驶舱的「待处置风险」卡用）：
 *   · `?tab=risks|orders|notices`   —— 打开哪个一级 Tab
 *   · `?status=OPEN|HANDLED|CLOSED` —— 预置筛选（驾驶舱传 `OPEN` = 待处置）
 *   · `?range=TODAY|LAST7|LAST30|ALL` —— 预置统计区间档位
 *
 * **只读一次、只用于初始状态**：之后页面里的操作**不回写 URL**。
 * 回写的话，每次点筛选都会往历史栈里压一条记录，返回键要按好几下才离得开这一页 ——
 * 而"点了个筛选，返回却退不出去"是很烦人的一种交互。
 *
 * ★ `LAST30` 是 2026-10-04 补的：驾驶舱顶部的区间有四档，其中"近 30 天"
 * 会带着 `range=LAST30` 跳进来。本页原来只认 TODAY/LAST7/ALL，遇到 LAST30
 * 会**静默回落到 TODAY** —— 于是"卡片上写近 30 日 24 件待处置、点进去看到
 * 今日 17 件"。两个数字并排出现在前后两页、都不报错，是这条联动链上
 * 最难发现的一种断法。档位集合现在与驾驶舱一致。
 *
 * 参数不认识时一律回落到本页原本的默认值（TODAY / 全部 / 风险 Tab），不报错：
 * 一个拼错的 query 不该让运营看到一页空的筛选结果，还以为数据没了。
 */
const route = useRoute()

function initTab(): 'risks' | 'orders' | 'notices' {
  const v = String(route.query.tab ?? '')
  return v === 'orders' || v === 'notices' ? v : 'risks'
}

function initActive(): 'OPEN' | 'HANDLED' | 'CLOSED' | 'UNCLOSED' | 'HIGH' | 'MID' | null {
  const v = String(route.query.status ?? '')
  return v === 'OPEN' || v === 'HANDLED' || v === 'CLOSED' || v === 'UNCLOSED' || v === 'HIGH' || v === 'MID'
    ? v
    : null
}

function initRange(): RangeMode {
  const v = String(route.query.range ?? '')
  return v === 'LAST7' || v === 'LAST30' || v === 'ALL' || v === 'CUSTOM' ? v : 'TODAY'
}

const tab = ref<'risks' | 'orders' | 'notices'>(initTab())
const risks = ref<RiskEvent[]>([])
const orders = ref<WorkOrder[]>([])
const notices = ref<DiversionNotice[]>([])
/** 承载与容量。来自公开的 `/api/stats/pois`，只用于抽屉里的概况数字 */
const poiStats = ref<PoiStat[]>([])
const loading = ref(true)
/** 抽屉里正在提交的动作。非空时禁用抽屉里的所有按钮 */
const busy = ref(false)
const scanning = ref(false)

/**
 * 当前筛选口径。**状态维度与等级维度共用这一个变量** —— 两个维度是互斥的
 * （一条风险要么按状态看、要么按等级看），分成两个 ref 会出现"点了状态又点了
 * 等级、结果谁也说不清"的状态：等级筛选会静默失效，或者两个条件叠加后
 * 一行都不剩，而界面上两个控件都是亮的。
 *
 * `null` = 全部。取值：`OPEN` / `HANDLED` / `CLOSED` / `UNCLOSED`（状态）
 * 或 `HIGH` / `MID`（等级）。
 *
 * ★ `UNCLOSED`（未闭环 = OPEN + HANDLED）是 2026-10-04 补的，为的是让
 * 驾驶舱「待处置风险」卡跳进来时**行数与卡片上的数一致**：后端 `open_risks`
 * 的判据是 `status != CLOSED`（见 `OpsServiceImpl`），而本页原来的
 * `status=OPEN` 只数 OPEN —— 近 7 天档下卡片写 22 件、跳过来只有 17 行。
 * 不选"把后端改成只数 OPEN"是因为那会改动 M5 既有口径；这里只是**补一个
 * 筛选项**，让页面能表达"未闭环"这个后端本来就在用的口径。
 */
const active = ref<'OPEN' | 'HANDLED' | 'CLOSED' | 'UNCLOSED' | 'HIGH' | 'MID' | null>(initActive())

/* ============================================================
   日期筛选
   ============================================================
   这一页的事件会越攒越多（规则引擎每次重扫都按基准日重算，旧日期的事件
   会一直留着），所以「看哪一段」必须是一等公民，而不是让人翻长列表。

   ★ 「今日」= **数据基准日**，不是系统当天。
     规则引擎的基准日是数据包里最新的一天（`OpsServiceImpl.loadContext`
     取 `max(stat_date)`），不是 `LocalDate.now()`。仿真客流数据包是按天
     生成的，生成完不动它，"最新统计日"就会停在生成那天。
     如果按系统当天算，一旦数据包没更新，默认视图就是空的 —— 而运营真正
     想看的是"最近这一批要处理的风险"。所以这里把"今日"定义成基准日，
     **并把实际日期显示出来**，两者不一致时再补一句说明，不藏着。
*/
type RangeMode = 'TODAY' | 'LAST7' | 'LAST30' | 'ALL' | 'CUSTOM'

/**
 * 日期分段的四个档。**不含 CUSTOM** —— 自定义由右边的两个日期框触发
 * （`pickMode('CUSTOM')`），不是按一个按钮。
 *
 * 档位集合与驾驶舱顶部**必须一致**：驾驶舱的「待处置风险」卡会带
 * `range=LAST30` 跳进来，本页少一档就会静默回落成"今日"。
 * 文案也用同一套词（"近 7 日"而不是"近 7 天"）—— 同一个窗口两种说法，
 * 看屏的人会怀疑是两个口径。
 */
const RANGE_SEGMENTS = [
  { key: 'TODAY' as const, label: '今日' },
  { key: 'LAST7' as const, label: '近 7 日' },
  { key: 'LAST30' as const, label: '近 30 日' },
  { key: 'ALL' as const, label: '全部' },
]
const rangeMode = ref<RangeMode>(initRange())
/** 自定义起止。用字符串比较即可 —— `YYYY-MM-DD` 字典序等于时间序 */
const customFrom = ref('')
const customTo = ref('')

/** 系统当天（本机时区）。只用来提示"基准日和今天不是同一天" */
const sysToday = localDate(new Date())

/** `YYYY-MM-DD`。`created_at` 是 `YYYY-MM-DDTHH:mm:ss`，`stat_date` 已经是日期 */
function dateOnly(v?: string | null) {
  return v ? String(v).slice(0, 10) : ''
}

/** 本机时区下的 `YYYY-MM-DD`。**不要用 `toISOString()`** —— 那是 UTC，会差一天 */
function localDate(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** `YYYY-MM-DD` 加减天数 */
function shiftDays(day: string, delta: number) {
  const d = new Date(`${day}T00:00:00`)
  d.setDate(d.getDate() + delta)
  return localDate(d)
}

/**
 * 数据基准日 = 风险事件里最新的 `stat_date`。
 *
 * 从**已加载的事件**里取，不另开接口：事件列表本来就全量拉回来了，
 * 再为一个日期跑一次请求不划算。没有事件时退回系统当天（此时页面本来就空，
 * 基准日取什么都一样，但取当天至少不会显示一个空字符串）。
 */
const baseDate = computed(() => {
  let mx = ''
  for (const r of risks.value) {
    const d = dateOnly(r.stat_date)
    if (d > mx) mx = d
  }
  return mx || sysToday
})

/** 解析后的实际区间。`null` = 不筛 */
const dateRange = computed<{ from: string; to: string } | null>(() => {
  const base = baseDate.value
  if (rangeMode.value === 'ALL') return null
  if (rangeMode.value === 'TODAY') return { from: base, to: base }
  if (rangeMode.value === 'LAST7') return { from: shiftDays(base, -6), to: base }
  // 30 天含基准日本身，所以往回推 29 天 —— 与后端 `LAST30_DAYS=30` 同口径
  if (rangeMode.value === 'LAST30') return { from: shiftDays(base, -29), to: base }
  // 自定义：留空的一端回落到基准日；起止颠倒时自动摆正
  const f = customFrom.value || base
  const t = customTo.value || base
  return f <= t ? { from: f, to: t } : { from: t, to: f }
})

function inRange(day: string) {
  const r = dateRange.value
  if (!r) return true
  if (!day) return false
  return day >= r.from && day <= r.to
}

/** 区间文案。`ALL` 时不写死"全部时间" —— 数据到哪天就说哪天 */
const rangeText = computed(() => {
  const r = dateRange.value
  if (!r) {
    let mn = ''
    let mx = ''
    for (const x of risks.value) {
      const d = dateOnly(x.stat_date)
      if (!mn || d < mn) mn = d
      if (d > mx) mx = d
    }
    return mn ? `全部 ${mn} ～ ${mx}` : '全部'
  }
  return r.from === r.to ? r.from : `${r.from} ～ ${r.to}`
})

/**
 * 基准日与系统当天不一致时的一句说明。一致时为空串（正常情况不出现）。
 *
 * ★ 文案刻意压到最短。这条与「区间文本」并排，而日期条在 1440 下只有 1106px
 *   可用 —— 实测原来那句「数据包最新统计日 X，系统当天为 Y」宽 313px，与
 *   区间文本、口径提示、回到今日按钮挤在一起会**溢出 147px 折成两行**
 *   （日期条从 50px 变 96px）。为什么、怎么看，放进 `title` 里说。
 */
const baseNote = computed(() =>
  baseDate.value === sysToday ? '' : `数据截至 ${baseDate.value} · 系统当天 ${sysToday}`
)

/**
 * 切换区间档位。
 *
 * ★ 离开 `CUSTOM` 时**顺手把两个日期框清空**。
 *   不清的话会出现"分段高亮在「今日」，日期框里却还留着上次选的 2026-01-01"
 *   这种自相矛盾的画面 —— 而且清空之后，「今日」这个分段按钮就成了一个
 *   **完整意义上的"回到今日"**，页面上不必再放一个功能重复的按钮
 *   （那个按钮 87px 宽，实测会把日期条挤到两行）。
 */
function pickMode(m: RangeMode) {
  rangeMode.value = m
  if (m !== 'CUSTOM') {
    customFrom.value = ''
    customTo.value = ''
  }
}

/** 空态里的显式出口用。与 `pickMode('TODAY')` 等价，保留是为了语义清晰 */
function resetRange() {
  pickMode('TODAY')
}

/* ------------------------------------------------------------
   按日期切分后的三份数据。**所有统计与角标都以这三份为准** ——
   否则会出现"统计条说 25 条、表格里 17 条"这种对不上的情况。

   ★ 三个维度**统一按"风险统计日"这一根轴**筛，不是各用各的时间戳：
     · 风险事件 → 自己的 `stat_date`
     · 工单     → 来源事件的 `risk_stat_date`（后端 JOIN 出来给的）
     · 分流公告 → 关联风险的 `stat_date`（手工公告没有关联时退回创建日）

   为什么不按"创建日"筛工单：基准日会落后于系统当天（数据包不更新时，
   `baseDate` 停在生成那天）。这时运营在「今日」视图里点「处理」建单，
   工单的创建日是系统当天、不在区间内 —— **刚建的单一转身就从列表里消失了**，
   看起来像建单失败。按来源风险的统计日筛，动作不会把行"筛没"。
   公告同理（所以 `noticeDate` 要顺着 `risk_event_id` 去查）。
*/
const risksIn = computed(() => risks.value.filter((r) => inRange(dateOnly(r.stat_date))))
const ordersIn = computed(() => orders.value.filter((w) => inRange(orderDate(w))))
const noticesIn = computed(() => notices.value.filter((n) => inRange(noticeDate(n))))

// ============================================================
// 顶部三张图（2026-10-04 新增）
// ============================================================
/**
 * 风险的三张图。**数据完全在前端聚合**，没有新增任何后端接口 ——
 * `/api/admin/risks` 本来就返回当前窗口的全部事件，画三张图不需要更多字段。
 *
 * 它们回答三个不同的问题，所以是三种不同的形状，不是三张柱状图：
 *   · 类型构成（环）—— 「这一窗段的风险都是些什么事」；
 *   · 等级分布（条）—— 「其中有多少是高风险的」（这个数字与统计条上的
 *     「高风险」**同源同口径**，对不上就是 bug）；
 *   · 每日变化（线）—— 「是在变多还是变少」。
 *
 * ★ 三张图都只按**日期**筛（`risksIn`），**不跟着统计条/等级分段走** ——
 *   它们的作用是"告诉你窗口里有什么"，如果跟着筛，筛成"高风险"之后
 *   类型构成图就只剩高风险的几条，失去了全貌的意义。
 */
const riskTypeOption = computed(() => {
  const m = new Map<string, number>()
  for (const r of risksIn.value) m.set(r.type, (m.get(r.type) ?? 0) + 1)
  const rows = [...m.entries()]
    .map(([k, v]) => ({ id: k, name: RISK_TYPE_LABEL[k] ?? k, value: v }))
    .sort((a, b) => b.value - a.value)
  return doughnutOption(rows, { centerLabel: '风险事件' })
})

const riskLevelOption = computed(() => {
  const order: RiskLevel[] = ['HIGH', 'MID']
  const rows = order.map((l) => ({
    id: l,
    name: `${LEVEL_LABEL[l]}风险`,
    value: risksIn.value.filter((r) => r.level === l).length,
  }))
  // 高风险的柱子用金色（警示），中风险用品牌绿
  return distributionBarOption(rows, { colors: ['#c09a4e', '#3d8b74'], unit: ' 条' })
})

/**
 * 每日风险变化（趋势线）。
 *
 * ★ **窗口至少 7 天**（`§四` 的既有设计，与后端运营分析的
 *   `Math.max(days, TREND_DAYS)` 同口径）。只按当前区间取数的话，
 *   「今日」档就只有 1 个点 —— 一条线只剩一个孤点，既看不出"变多还是变少"，
 *   也不像一张趋势图。所以窗口取 `max(区间天数, 7)`，右端锚在区间结束日。
 *
 *   区间外的点补 0：**补的是"那天确实没有风险事件"这个事实**，
 *   不是编数据 —— 它与"库里没有那天的记录"是一回事，画成 0 才是诚实的。
 */
const riskDailyOption = computed(() => {
  const m = new Map<string, number>()
  for (const r of risks.value) {
    const d = dateOnly(r.stat_date)
    if (d) m.set(d, (m.get(d) ?? 0) + 1)
  }
  const dates = [...m.keys()].sort()
  const r = dateRange.value
  // 右端：有区间用区间结束日；ALL 用数据里最后一天
  const to = r ? r.to : dates[dates.length - 1] || baseDate.value
  // 左端：有区间用区间起始日；ALL 用数据里第一天
  let from = r ? r.from : dates[0] || baseDate.value
  // 不足 7 天就往回补足 —— 「今日」档与 ALL 档都会走到这里
  const span = Math.round((Date.parse(`${to}T00:00:00`) - Date.parse(`${from}T00:00:00`)) / 86400000) + 1
  if (span < 7) from = shiftDays(to, -6)

  const pts: { date: string; value: number }[] = []
  for (let d = from; d <= to; d = shiftDays(d, 1)) {
    // 横轴只留 MM-DD：完整日期在长区间下会挤成一团
    pts.push({ date: d.slice(5), value: m.get(d) ?? 0 })
  }
  return areaLineOption(pts, { color: '#d0705a', unit: ' 条' })
})

/** 工单的业务日。`risk_stat_date` 缺失（来源事件被清掉）时退回创建日 */
function orderDate(w: WorkOrder) {
  return dateOnly(w.risk_stat_date) || dateOnly(w.created_at)
}

/**
 * 公告的业务日 = **它关联的那条风险的统计日**。
 *
 * 公告表自己没有统计日，只有 `risk_event_id`。顺着一跳拿到风险日期，
 * 三个 Tab 才是同一根轴。手工建的公告（`risk_event_id` 为空）没有来源风险，
 * 退回创建日 —— 这是唯一一处轴不一致的地方，所以 `obj__sub` 里写的是
 * "统计日"三个字，让人看得出这个日期是哪来的。
 */
function noticeDate(n: DiversionNotice) {
  const r = n.risk_event_id != null ? risks.value.find((x) => x.id === n.risk_event_id) : undefined
  return r ? dateOnly(r.stat_date) : dateOnly(n.created_at)
}

/** 是否处于"只看今日"以外的口径 —— 用来决定要不要给行加"今日"标记 */
const showTodayMark = computed(() => {
  const r = dateRange.value
  return !r || r.from !== baseDate.value || r.to !== baseDate.value
})

/* ============================================================
   抽屉：三种模式共用一套外壳
   ============================================================
   风险 / 工单 / 公告各自有详情，但它们的"外壳"（遮罩、右滑面板、
   分节标题、底部主操作）是同一套。做成三个组件会复制三遍外壳，
   而且三者的底部操作逻辑高度相似（都是"当前状态下的下一步"）。
   这里用一个 `kind` 判别式切换内容，外壳只写一遍。
*/
const drawer = ref<{ kind: 'risk' | 'order' | 'notice'; id: number } | null>(null)
const moreOpen = ref(false)

async function load() {
  loading.value = true
  try {
    const [r, w, n, s] = await Promise.all([
      getRisks(),
      getWorkOrders(),
      getAdminDiversionNotices(),
      // 承载是"锦上添花"：读不到也不该让整页报错（`—` 比错误页诚实）
      getPoiStats().catch(() => [] as PoiStat[]),
    ])
    risks.value = r
    orders.value = w
    notices.value = n
    poiStats.value = s
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '数据加载失败')
    risks.value = []
    orders.value = []
    notices.value = []
  } finally {
    loading.value = false
  }
}
onMounted(load)

/**
 * 手动跑一次规则扫描。
 *
 * 服务启动时已经自动扫过一次（见 `OpsScanRunner`），这个按钮是给
 * "改了 `risk_rule` 的阈值、现在就想看结果"用的 —— 不用重启服务。
 * 幂等，重复点不会产生重复事件。
 */
async function scan() {
  if (scanning.value) return
  scanning.value = true
  try {
    const hit = await rescanRisks()
    say('ok', `扫描完成，命中 ${hit} 条风险事件`)
    await load()
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '扫描失败')
  } finally {
    scanning.value = false
  }
}

/* ============================================================
   统计条
   ============================================================
   四个数字**都是当前日期区间内的**，不是全库的 —— 否则日期一换，
   统计条不动而表格在变，两个数字谁也说不清。
*/

const countByStatus = computed(() => {
  const m: Record<string, number> = { ALL: risksIn.value.length, OPEN: 0, HANDLED: 0, CLOSED: 0 }
  for (const r of risksIn.value) m[r.status] = (m[r.status] ?? 0) + 1
  return m
})

const highCount = computed(() => risksIn.value.filter((r) => r.level === 'HIGH').length)

const stats = computed(() => [
  // 状态三项的文案**直接取 `RISK_STATUS_LABEL`**，不在这里另写一份 ——
  // 另写一份就会出现统计条写"待处理"、列表状态列写"待处置"这种
  // 同屏两个词指同一件事的情况。
  { key: 'OPEN' as const, label: RISK_STATUS_LABEL.OPEN, n: countByStatus.value.OPEN ?? 0 },
  { key: 'HANDLED' as const, label: RISK_STATUS_LABEL.HANDLED, n: countByStatus.value.HANDLED ?? 0 },
  { key: 'CLOSED' as const, label: RISK_STATUS_LABEL.CLOSED, n: countByStatus.value.CLOSED ?? 0 },
  // 未闭环 = 待处置 + 已建单（即后端 `open_risks` 的口径，见 `active` 的注释）。
  // 标签用字面量而不是 RISK_STATUS_LABEL：它不是一个 status 值，
  // 而是两个 status 的并集，硬套一个 status 的文案反而会误导。
  {
    key: 'UNCLOSED' as const,
    label: '未闭环',
    n: (countByStatus.value.OPEN ?? 0) + (countByStatus.value.HANDLED ?? 0),
  },
  { key: 'HIGH' as const, label: '高风险', n: highCount.value },
])

/**
 * 等级分段当前档位。**从 `active` 派生**，不另存一份状态 ——
 * 统计条上的「高风险」和分段里的「高风险」是同一个筛选项，
 * 两处必须永远同步（点哪边都一样，选中态也跟着走）。
 */
const levelFilter = computed<'ALL' | RiskLevel>(() =>
  active.value === 'HIGH' ? 'HIGH' : active.value === 'MID' ? 'MID' : 'ALL'
)

/** 统计条上的「高风险」再点一次取消，其余同理 */
function toggleStat(k: 'OPEN' | 'HANDLED' | 'CLOSED' | 'UNCLOSED' | 'HIGH') {
  active.value = active.value === k ? null : k
}

/** 等级分段：`ALL` 即清空筛选 */
function pickLevel(l: 'ALL' | RiskLevel) {
  active.value = l === 'ALL' ? null : l
}

/**
 * 列表筛选。**状态与等级是同一个筛选态的两个取值**，不是两个叠加条件 ——
 * 叠加的话"高风险 + 中风险"会得到 0 行，而用户以为自己在"看等级"。
 *
 * 日期区间是**前置条件**（先切时间段，再在这个段里挑状态/等级）。
 */
const filteredRisks = computed(() => {
  const f = active.value
  if (f == null) return risksIn.value
  if (f === 'HIGH' || f === 'MID') return risksIn.value.filter((r) => r.level === f)
  // 未闭环 = 不是"已闭环"。与后端 `open_risks` 的判据 `status != CLOSED` 一字不差，
  // 所以驾驶舱卡片上的数与本页行数必然相等（`status === 'OPEN'` 会少算已建单的）。
  if (f === 'UNCLOSED') return risksIn.value.filter((r) => r.status !== 'CLOSED')
  return risksIn.value.filter((r) => r.status === f)
})

/* ============================================================
   行数据：承载 / 容量 / 关联的工单与公告
   ============================================================ */

const statByPoi = computed(() => {
  const m = new Map<string, PoiStat>()
  for (const s of poiStats.value) {
    // `has_data=false` 时后端给的是占位的 0.0。留着会把"读不到"显示成"很空"
    if (s.has_data) m.set(s.poi_id, s)
  }
  return m
})

/**
 * 列表里「当前指标」那一格的值（比率，0–1 或增长率）。**读不到返回 undefined**
 * （显示 `—`），不要退化成 0。
 *
 * 六条规则**用的不是同一个指标**：`OVERLOAD` 是承载率，`LOW_CONVERSION` 是
 * 转化率，`REVIEW_SURGE` 是负面率，`HEAT_JUMP` / `REPURCHASE_DECAY` 是环比。
 * 所以列头写"当前指标"而不是"当前承载" —— 后者会让另外四条规则整列显示 `—`，
 * 一屏一半是横杠，正好是这次要消掉的那种噪音。"这条看的是哪个指标"
 * 由紧邻的「风险类型」列给出（客流超载 / 负面评价激增 / 热度突变 / …）。
 *
 * ★ `RURAL_IDLE` **不给值**。它的 `metric_value` 是**该区县景区平均承载**
 *   （见 `RuleEngine.evalRuralIdle`：事件挂在区县里最挤的那个景区上作为代表点），
 *   按行显示会被读成"这个点自己的承载"，是另一个数。它在抽屉里连标签一起给
 *   （「区县景区平均承载」），列表里留空。
 *
 * ★ 承载率优先取**实时**承载（`/stats/pois`），读不到才退回事件快照 ——
 *   快照可能是几小时前的。
 */
function metricOf(r: RiskEvent): number | undefined {
  if (r.rule_id === 'RURAL_IDLE') return undefined
  if (r.rule_id === 'OVERLOAD') {
    const s = statByPoi.value.get(r.poi_id)
    if (s?.capacity_usage != null) return s.capacity_usage
  }
  const v = Number(r.metric_value)
  return Number.isFinite(v) ? v : undefined
}

/** 环比型规则。列表里带 `+` 号，免得被读成"水平值"（+115% 是涨，不是 115% 满） */
const GROWTH_RULES = new Set(['HEAT_JUMP', 'REPURCHASE_DECAY'])

/** 列表单元格文本。涨的环比补一个 `+`；跌的本身带 `-`，不用管 */
function metricText(r: RiskEvent): string {
  const v = metricOf(r)
  if (v == null) return '—'
  const t = pctText(v)
  return GROWTH_RULES.has(r.rule_id) && v > 0 ? `+${t}` : t
}

/** 设计容量（人）。只对 OVERLOAD 推算，其余规则返回 undefined（见文件头） */
function capacityOf(r: RiskEvent): number | undefined {
  if (r.rule_id !== 'OVERLOAD') return undefined
  const s = statByPoi.value.get(r.poi_id)
  if (!s || !s.capacity_usage || s.today_visitors == null) return undefined
  return Math.round(s.today_visitors / s.capacity_usage)
}

function visitorsOf(r: RiskEvent): number | undefined {
  const s = statByPoi.value.get(r.poi_id)
  return s?.today_visitors
}

/**
 * 比率 → 百分比文本。
 *
 * 小于 10% 时保留**一位小数**（含末尾的 0，`4.0%` 而不是 `4%`）：转化率 3% 和
 * 3.2% 是两个不同的量级判断，统一取整会把 `LOW_CONVERSION`（阈值 10%）报出来的
 * 值全部压成 3%–9% 之间的同一个数；而 `4%` / `2.7%` 混排在一列数字里对不齐。
 * 承载率那种 80%–120% 的量级则不需要小数。
 */
function pctText(u?: number) {
  if (u == null) return '—'
  const p = u * 100
  return `${Math.abs(p) < 10 ? p.toFixed(1) : Math.round(p)}%`
}

function kmText(km: number) {
  return `${km.toFixed(1)} km`
}

/** 该事件已建的工单（列表里已有，不必再请求） */
function orderOf(r: RiskEvent) {
  return r.work_order_id == null ? undefined : orders.value.find((w) => w.id === r.work_order_id)
}

/** 该事件已生成的公告（一条事件最多一条，所以直接 find） */
function noticeOf(r: RiskEvent) {
  return notices.value.find((n) => n.risk_event_id === r.id)
}

/** 分流候选。**只有处置动作为 DIVERSION 的规则才有**，空数组 = 这条不涉及分流 */
function planOf(r: RiskEvent) {
  return r.candidates ?? []
}

/* ============================================================
   配色：状态与等级一律用着色文字，不做徽标
   ============================================================ */

function riskTone(s: RiskStatus) {
  return s === 'OPEN' ? 'warn' : s === 'HANDLED' ? 'info' : 'mute'
}

function noticeTone(s: string) {
  if (s === 'PUBLISHED') return 'ok'
  if (s === 'DRAFT') return 'warn'
  return 'mute'
}

function orderTone(s: WorkOrderStatus) {
  return s === 'DONE' ? 'ok' : s === 'PROCESSING' ? 'info' : 'warn'
}

const LEVEL_LABEL: Record<RiskLevel, string> = { HIGH: '高', MID: '中' }

/* ============================================================
   抽屉：打开与关闭
   ============================================================ */

function openRisk(r: RiskEvent) {
  moreOpen.value = false
  drawer.value = { kind: 'risk', id: r.id }
}

function openOrder(w: WorkOrder) {
  moreOpen.value = false
  orderErr.value = ''
  // 处置人默认填当前登录的运营账号：绝大多数情况下就是本人
  orderForm.value = { assignee: w.assignee || session.displayName, result: '' }
  drawer.value = { kind: 'order', id: w.id }
}

function openNotice(n: DiversionNotice) {
  moreOpen.value = false
  noticeErr.value = ''
  noticeEditing.value = n.status === 'DRAFT'
  noticeForm.value = { title: n.title, message: n.message }
  drawer.value = { kind: 'notice', id: n.id }
}

function closeDrawer() {
  drawer.value = null
  moreOpen.value = false
}

const curRisk = computed(() =>
  drawer.value?.kind === 'risk' ? risks.value.find((r) => r.id === drawer.value!.id) : undefined
)
const curOrder = computed(() =>
  drawer.value?.kind === 'order' ? orders.value.find((w) => w.id === drawer.value!.id) : undefined
)
const curNotice = computed(() =>
  drawer.value?.kind === 'notice'
    ? notices.value.find((n) => n.id === drawer.value!.id)
    : undefined
)

/* ============================================================
   抽屉 · 风险概况（第 1 段）
   ============================================================ */

interface Kv {
  k: string
  v: string
  hint?: string
}

/**
 * 概况的键值对。**按规则类型给不同的键** —— 六条规则里只有 OVERLOAD 有
 * "设计容量"这个概念，给转化率硬凑一个容量字段就是编数字。
 */
const riskKv = computed<Kv[]>(() => {
  const r = curRisk.value
  if (!r) return []
  const rule = `${r.rule_id} · ${RISK_TYPE_LABEL[r.type] ?? r.type}`
  const base: Kv[] = [{ k: '风险对象', v: r.poi_name, hint: r.district || undefined }]

  if (r.rule_id === 'OVERLOAD') {
    const cap = capacityOf(r)
    const vis = visitorsOf(r)
    const over = cap != null && vis != null ? vis - cap : undefined
    return [
      ...base,
      {
        k: '当前承载',
        v: pctText(metricOf(r)),
        hint: vis != null ? `当日到访 ${vis.toLocaleString()} 人次` : undefined,
      },
      { k: '设计容量', v: cap != null ? `${cap.toLocaleString()} 人` : '—' },
      {
        k: '超载比例',
        v: metricOf(r) != null ? `+${Math.round((metricOf(r)! - 1) * 1000) / 10}%` : '—',
        hint: over != null && over > 0 ? `超出 ${over.toLocaleString()} 人次` : '未超出设计上限',
      },
      { k: '触发规则', v: rule, hint: `阈值 ${pctText(Number(r.threshold))} · 统计日 ${r.stat_date}` },
    ]
  }

  if (r.rule_id === 'RURAL_IDLE') {
    // 这一条**不走 `metricOf`**：它的 metric_value 是区县均值，只有连标签一起
    // 给才不会被误读（列表里那一格因此是空的，见 metricOf 的注释）
    return [
      ...base,
      {
        k: '区县景区平均承载',
        v: pctText(Number(r.metric_value)),
        hint: `阈值 ${pctText(Number(r.threshold))}`,
      },
      { k: '触发规则', v: rule, hint: `统计日 ${r.stat_date}` },
    ]
  }

  // 其余四条：指标同样是一个比率（转化率 / 负面率 / 热度环比 / 复购环比），
  // 统一按百分比显示 —— 与上面两条规则"110% / 阈值 100%"保持同一种读法。
  // 具体是哪个指标由「触发规则」里的中文名给出；精确的原始句子在下面的
  // `detail` 里（"近 7 日体验 12 人次，仅 0 笔购买，转化 3.2%…"）。
  return [
    ...base,
    {
      k: '指标值',
      v: pctText(metricOf(r)),
      hint: `阈值 ${pctText(Number(r.threshold))}`,
    },
    { k: '触发规则', v: rule, hint: `统计日 ${r.stat_date}` },
  ]
})

/**
 * 是否额外显示规则自带的 `detail`。
 *
 * `OVERLOAD` 不显示 —— 上面那五个键值对已经把它的数字说全了，
 * 再贴一遍就是同一句话的第二遍（这一页最大的问题就是重复）。
 * 其余规则显示：它们的 `detail` 里有键值对放不下的信息
 * （乡村平均承载、样本量、窗口长度）。
 */
const showDetail = computed(() => curRisk.value != null && curRisk.value.rule_id !== 'OVERLOAD')

/* ============================================================
   抽屉 · 底部主操作
   ============================================================
   一个状态只给**一个**主按钮，其余进「更多」。顺序即优先级：
   建单 → 处置工单 → 生成公告 → 发布 → 撤下 → 重新生成。
   前两个属于"事件本身的处置"，后四个属于"对游客端的下发" ——
   先处置、后下发，与运营的实际动作顺序一致。
*/
interface Act {
  key: string
  label: string
  run: () => void | Promise<void>
}

const riskActions = computed<Act[]>(() => {
  const r = curRisk.value
  if (!r) return []
  const out: Act[] = []
  const w = orderOf(r)
  const n = noticeOf(r)

  if (r.status === 'OPEN' && !r.work_order_id) {
    out.push({ key: 'order', label: '建单', run: () => makeOrder(r) })
  }
  if (w && w.status !== 'DONE') {
    out.push({ key: 'process', label: '处置工单', run: () => openOrder(w) })
  }
  if (planOf(r).length && !n) {
    out.push({ key: 'notice', label: '生成公告草稿', run: () => makeNotice(r) })
  }
  if (n?.status === 'DRAFT') {
    out.push({ key: 'publish', label: '发布到游客端', run: () => setNoticeStatus(n, 'PUBLISHED') })
  }
  if (n?.status === 'PUBLISHED') {
    out.push({ key: 'withdraw', label: '撤下公告', run: () => setNoticeStatus(n, 'WITHDRAWN') })
  }
  if (n?.status === 'EXPIRED') {
    out.push({ key: 'regen', label: '重新生成公告', run: () => makeNotice(r) })
  }
  return out
})

const primaryAct = computed(() => riskActions.value[0] ?? null)
const moreActs = computed(() => riskActions.value.slice(1))

/** 抽屉底部一句状态说明：没有可做的动作时说清"为什么没有" */
const riskIdleHint = computed(() => {
  const r = curRisk.value
  if (!r) return ''
  if (r.status === 'CLOSED') return '该事件已闭环，公告仍会在有效期内对游客可见。'
  if (!planOf(r).length) return '该规则不涉及分流，处置动作在工单里。'
  return ''
})

/* ============================================================
   写操作：建单 / 生成公告 / 发布撤下
   ============================================================ */

/**
 * 为一条风险建单。
 *
 * 只有 `OPEN` 的才给按钮：已建单的再点一次后端会回 8003，用户看到的是
 * 一个报错 —— 而界面上本来就看得出"这条已经建过了"。
 */
async function makeOrder(r: RiskEvent) {
  if (busy.value) return
  busy.value = true
  try {
    const wo = await createWorkOrder(r.id)
    say('ok', `已建单 ${wo.code} · ${wo.risk_poi_name}`)
    await load()
    // 建完直接切到工单抽屉：下一步动作（指派 / 处置）在那里
    openOrder(wo)
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '建单失败')
  } finally {
    busy.value = false
  }
}

/**
 * 生成公告草稿。
 *
 * 不传文案，让后端按候选自动生成 —— 后端那句默认文案里带"演示用仿真数据"，
 * 前端自己拼一份容易漏掉这句。生成完**不自动发布**：公告是发给游客看的，
 * 发出去之前得有人看一眼。
 */
async function makeNotice(r: RiskEvent) {
  if (busy.value) return
  busy.value = true
  try {
    const n = await createNoticeDraft(r.id)
    say('ok', `已生成公告草稿 ${n.code}`)
    await load()
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '生成公告失败')
  } finally {
    busy.value = false
  }
}

/**
 * 从一条**已过期**的公告重新生成。
 *
 * 后端的"重新生成"是按**风险事件**建的（`POST /admin/risks/{id}/diversion-notice`），
 * 命中同事件上那条旧公告时会**原地顶替**（保留原 id、重算有效期、清空
 * `published_at`）。所以这里只需拿公告上带的 `risk_event_id` 再调一次建草稿。
 *
 * ★ 只有 `EXPIRED` 给这个入口。`DRAFT` / `PUBLISHED` 上再建一次会被后端
 *   8011「已有未过期公告」挡掉 —— 界面上本来也没必要给。
 *
 * ★ 顶替后 id 不变，所以 `load()` 一回来抽屉里就是新的那条，不用重新打开。
 */
async function regenFromNotice(n: DiversionNotice) {
  if (busy.value || n.risk_event_id == null) return
  busy.value = true
  try {
    const fresh = await createNoticeDraft(n.risk_event_id)
    say('ok', `已重新生成公告草稿 ${fresh.code}`)
    await load()
    // 顶替后 id 不变，但状态从 EXPIRED 变回 DRAFT、文案可能被后端重写过。
    // 重开一次抽屉把 `noticeForm` / `noticeEditing` 同步到新数据上。
    const next = notices.value.find((x) => x.id === n.id)
    if (next) openNotice(next)
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '重新生成失败')
  } finally {
    busy.value = false
  }
}

/**
 * 发布 / 撤下。
 *
 * 发布后**必须让游客端那份缓存失效**（`reloadDiversionNotices`）：
 * 那个缓存是模块级的，会在路由切换间活下来。不清的话，运营发布完切到首页
 * 看到的还是发布前那一版 —— 看起来像"发布没生效"。
 */
async function setNoticeStatus(n: DiversionNotice, status: 'PUBLISHED' | 'WITHDRAWN') {
  if (busy.value) return
  busy.value = true
  try {
    await updateDiversionNotice(n.id, {
      status,
      published_by: status === 'PUBLISHED' ? session.displayName : undefined,
    })
    say('ok', status === 'PUBLISHED' ? `${n.code} 已发布到游客端` : `${n.code} 已撤下`)
    await load()
    void reloadDiversionNotices()
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '操作失败')
  } finally {
    busy.value = false
  }
}

/* ============================================================
   抽屉 · 工单处置
   ============================================================ */

const orderForm = ref({ assignee: '', result: '' })
const orderErr = ref('')

/** 置为处置中（可同时指派处置人） */
async function submitProcessing(w: WorkOrder) {
  if (busy.value) return
  busy.value = true
  orderErr.value = ''
  try {
    await updateWorkOrder(w.id, { status: 'PROCESSING', assignee: orderForm.value.assignee.trim() })
    say('ok', `${w.code} 已置为处置中`)
    await load()
  } catch (e) {
    orderErr.value = e instanceof ApiError ? e.message : '操作失败'
  } finally {
    busy.value = false
  }
}

/**
 * 完结并提交反馈。
 *
 * 反馈在客户端先拦一道空值，而不是直接让后端回 8005：
 * 后端那道**必须保留**（接口不是只有这一页在调），但在这里先说清楚
 * 能省掉一次往返，也让"为什么不能提交"紧挨着输入框。
 */
async function submitDone(w: WorkOrder) {
  if (busy.value) return
  const result = orderForm.value.result.trim()
  if (!result) {
    orderErr.value = '完结前请填写处置反馈：说不出做了什么，等于没有处置记录'
    return
  }
  busy.value = true
  orderErr.value = ''
  try {
    await updateWorkOrder(w.id, {
      status: 'DONE',
      assignee: orderForm.value.assignee.trim(),
      result,
    })
    say('ok', `${w.code} 已完结`)
    await load()
  } catch (e) {
    orderErr.value = e instanceof ApiError ? e.message : '操作失败'
  } finally {
    busy.value = false
  }
}

/* ============================================================
   抽屉 · 公告文案
   ============================================================ */

const noticeForm = ref({ title: '', message: '' })
const noticeErr = ref('')
/** 是否处于编辑态。草稿直接进编辑态 —— 草稿的下一步本来就是改文案 */
const noticeEditing = ref(false)

async function saveNotice(n: DiversionNotice) {
  if (busy.value) return
  const title = noticeForm.value.title.trim()
  if (!title) {
    noticeErr.value = '公告标题不能为空'
    return
  }
  busy.value = true
  noticeErr.value = ''
  try {
    await updateDiversionNotice(n.id, { title, message: noticeForm.value.message.trim() })
    say('ok', `${n.code} 文案已保存`)
    noticeEditing.value = false
    await load()
  } catch (e) {
    noticeErr.value = e instanceof ApiError ? e.message : '保存失败'
  } finally {
    busy.value = false
  }
}

/* ============================================================
   Tab 角标
   ============================================================ */

/**
 * 三个维度的角标。**都按当前日期区间、都数"这个分类里有多少条"**。
 *
 * 两条都是刻意的：
 *
 * 1. 按区间算。角标是"点进去有多少条"的承诺 —— 按全库算的话，切到「今日」
 *    之后角标写 25、列表只有 17 条，就是在骗人。
 *
 * 2. 数全部条数，不数"待办条数"。三个 Tab 的角标在同一个位置、长得一样，
 *    就必须是同一种含义。曾经公告这一项只数 `DRAFT`/`PUBLISHED`（理由是
 *    `EXPIRED`/`WITHDRAWN` 已经不在游客端了），结果是：另两个 Tab 数 25/11，
 *    它数 2，点进去却有 9 行 —— 同一个控件给出三种读法，还得先解释一遍。
 *    "待办"这件事交给表格里的**状态列**和草稿行上的金色「发布」按钮去表达，
 *    那是它们本来就该干的活。
 */
const tabs = computed(() => [
  { key: 'risks' as const, label: '风险事件', n: risksIn.value.length },
  { key: 'orders' as const, label: '工单', n: ordersIn.value.length },
  { key: 'notices' as const, label: '分流公告', n: noticesIn.value.length },
])
</script>

<template>
  <div class="rk">
    <!-- ==================== 页头 ==================== -->
    <header class="rk__head">
      <div>
        <span class="eyebrow eyebrow--light">M5 · 承载力与乡村分流</span>
        <h1 class="h1">风险与工单</h1>
      </div>
      <div class="rk__tools">
        <span class="badge-sim">仿真数据</span>
        <button class="btn btn-scan" :disabled="scanning" @click="scan">
          <span class="btn-scan__ico" :class="{ 'is-spin': scanning }" aria-hidden="true">↻</span>
          {{ scanning ? '扫描中…' : '重新扫描' }}
        </button>
      </div>
    </header>

    <!--
      ==================== 日期筛选 ====================
      事件会越攒越多，所以「看哪一段」做成一级控件，放在统计条上面 ——
      统计条与三个 Tab 的角标都跟着它走。

      ★ 「今日」是**数据基准日**（数据包里最新的一天 = 规则引擎的基准日），
        不是系统当天。理由见 script 里 `baseDate` 的注释。实际日期一直显示在
        右边，两者不一致时再补一句说明，不把差异藏起来。

      ★ 三个 Tab **统一按「风险统计日」这一根轴筛**，不是各按各的创建日。
        理由见 script 里 `orderDate` / `noticeDate` 的注释：基准日落后于系统
        当天时，按创建日筛会让"刚在今日视图里建出来的单"立刻从列表消失。
    -->
    <div class="datebar">
      <span class="datebar__label">日期</span>

      <div class="seg seg--date">
        <button
          v-for="s in RANGE_SEGMENTS"
          :key="s.key"
          class="seg__b"
          :class="{ 'seg__b--on': rangeMode === s.key }"
          @click="pickMode(s.key)"
        >
          {{ s.label }}
        </button>
      </div>

      <div class="datebar__custom" :class="{ 'is-on': rangeMode === 'CUSTOM' }">
        <input
          v-model="customFrom"
          class="datebar__d"
          type="date"
          :max="customTo || baseDate"
          aria-label="起始日期"
          @change="pickMode('CUSTOM')"
        />
        <span class="datebar__tilde">～</span>
        <input
          v-model="customTo"
          class="datebar__d"
          type="date"
          :min="customFrom || undefined"
          aria-label="结束日期"
          @change="pickMode('CUSTOM')"
        />
      </div>

      <span
        class="datebar__range"
        title="风险事件取自己的统计日；工单与公告顺着来源事件取同一个统计日，没有来源记录的退回创建日"
      >
        {{ rangeText }}
      </span>
      <span
        v-if="baseNote"
        class="datebar__note"
        title="数据包不更新时，规则引擎的基准日会停在数据里最新的一天，所以这里的「今日」指的是数据最新那天，不是系统当天"
      >
        {{ baseNote }}
      </span>

      <!--
        ★ 这里**没有**单独的「回到今日」按钮。
          它 87px 宽，而「今日」这个分段按钮一直就在旁边、一直有字、一直可点 ——
          两者做的是同一件事（`pickMode` 还会顺手把自定义日期框清空）。
          实测：多这一个按钮会让日期条在「全部」模式下溢出 34px 折成两行
          （条高 50 → 92px，正好吃掉一屏一行）。空态里那个「回到今日」是另一回事，
          那里用户是真的迷路了，留着一个带日期的显式出口。
      -->
      <span
        class="datebar__hint"
        title="风险 / 工单 / 分流公告三个页签统一按「风险统计日」归类，不是各按各的创建日"
      >
        按风险统计日
      </span>
    </div>

    <!--
      ==================== 统计条 ====================
      四个数字同时也是筛选器。点一下按该维度过滤，再点一下取消。
      「高风险」筛的是等级，其余三个筛的是状态 —— 两者**共用同一个筛选态**
      （`active`），所以是"换一个口径"而不是"叠加一个条件"：点「高风险」再点
      分段里的「中风险」，得到的是中风险，不是 0 行。
    -->
    <div class="stats">
      <button
        v-for="s in stats"
        :key="s.key"
        class="stat"
        :class="{ 'stat--on': active === s.key }"
        @click="toggleStat(s.key)"
      >
        <b class="stat__n">{{ s.n }}</b>
        <span class="stat__l">{{ s.label }}</span>
      </button>

      <div class="stats__gap" />

      <!--
        等级分段。与统计条上的「高风险」是**同一个筛选项**：点哪边都改
        `active`，两处选中态一起走。放在这里是因为统计条只放了"高风险"一档，
        想单看"中风险"需要一个入口。
      -->
      <div class="seg">
        <button
          v-for="l in (['ALL', 'HIGH', 'MID'] as const)"
          :key="l"
          class="seg__b"
          :class="{ 'seg__b--on': levelFilter === l }"
          @click="pickLevel(l)"
        >
          {{ l === 'ALL' ? '全部' : `${LEVEL_LABEL[l]}风险` }}
        </button>
      </div>
    </div>

    <!--
      ==================== 风险总览三图 ====================
      放在统计条**下面**、业务 Tab **上面**：先给全貌（这一窗段有什么风险），
      再进入具体处置（风险事件 / 工单 / 分流公告）。

      三张图都只按日期筛，不跟统计条走 —— 见 riskTypeOption 的注释。
    -->
    <div class="riskcharts">
      <section class="rchart">
        <h3 class="rchart__t">
          风险类型构成
          <span class="rchart__tip" title="按 risk_rule.type 聚合。六条规则全量覆盖，图例里没出现的类型表示窗口内没有命中">?</span>
        </h3>
        <p class="rchart__s">窗口内 {{ risksIn.length }} 条风险事件的类型分布</p>
        <div v-if="!risksIn.length" class="rchart__empty">窗口内没有风险事件</div>
        <EChart v-else :option="riskTypeOption" height="220px" />
      </section>

      <section class="rchart">
        <h3 class="rchart__t">
          风险等级分布
          <span class="rchart__tip" title="与统计条上的「高风险」同源同口径 —— 两个数字对不上就是 bug">?</span>
        </h3>
        <p class="rchart__s">高风险 {{ risksIn.filter((r) => r.level === 'HIGH').length }} 条 · 中风险 {{ risksIn.filter((r) => r.level === 'MID').length }} 条</p>
        <div v-if="!risksIn.length" class="rchart__empty">窗口内没有风险事件</div>
        <EChart v-else :option="riskLevelOption" height="220px" />
      </section>

      <section class="rchart">
        <h3 class="rchart__t">
          每日风险变化
          <span
            class="rchart__tip"
            title="按 risk_event.stat_date 逐日计数，横轴只显示 MM-DD。不足 7 天的区间会向前补足 7 天，区间外计 0（即那天没有风险事件）"
            >?</span
          >
        </h3>
        <p class="rchart__s">按风险统计日逐日计数，窗口至少 7 天 · 区间外计 0</p>
        <div v-if="!risksIn.length" class="rchart__empty">窗口内没有风险事件</div>
        <EChart v-else :option="riskDailyOption" height="220px" />
      </section>
    </div>

    <Transition name="notice">
      <div v-if="notice" class="notice" :class="`notice--${notice.type}`">{{ notice.text }}</div>
    </Transition>

    <!-- ==================== 一级业务 Tab ==================== -->
    <nav class="tabs">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="tab"
        :class="{ 'tab--on': tab === t.key }"
        @click="tab = t.key"
      >
        {{ t.label }}<span class="tab__n">{{ t.n }}</span>
      </button>
    </nav>

    <div v-if="loading" class="stack-4">
      <div v-for="i in 3" :key="i" class="skeleton" style="height: 160px; border-radius: 10px" />
    </div>

    <!-- ==================== 风险事件 ==================== -->
    <template v-else-if="tab === 'risks'">
      <div v-if="!filteredRisks.length" class="empty">
        <!-- 空态要说清是"这段没有"还是"全都没有"，否则用户会以为功能坏了 -->
        <div class="empty__title">
          {{ rangeText }} 没有风险事件<template v-if="active">（叠加了当前状态/等级筛选）</template>
        </div>
        <div class="empty__desc">
          {{
            rangeMode === 'ALL'
              ? '规则引擎还没有产出任何事件，点右上角「重新扫描」按当前阈值重跑一次。'
              : '换一个日期区间看看，或点右上角「重新扫描」按当前阈值重跑规则引擎。'
          }}
        </div>
        <div class="empty__acts">
          <button v-if="rangeMode !== 'TODAY'" class="btn btn-ghost btn-sm" @click="resetRange">
            回到今日（{{ baseDate }}）
          </button>
          <button v-if="rangeMode !== 'ALL'" class="btn btn-ghost btn-sm" @click="rangeMode = 'ALL'">
            看全部
          </button>
          <button v-if="active" class="btn btn-ghost btn-sm" @click="active = null">
            清掉状态/等级筛选
          </button>
        </div>
      </div>

      <div v-else class="tablewrap">
        <table class="rt rkt">
          <thead>
            <tr>
              <th class="w-lv">等级</th>
              <th class="w-obj">风险对象</th>
              <th class="w-num">当前指标</th>
              <th class="w-type">风险类型</th>
              <th class="w-st">状态</th>
              <th class="w-code">工单</th>
              <th class="w-code">公告</th>
              <th class="w-fill" />
              <th class="w-act" />
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in filteredRisks"
              :key="r.id"
              :class="{ 'is-sel': drawer?.kind === 'risk' && drawer.id === r.id }"
              @click="openRisk(r)"
            >
              <td>
                <span class="lv" :class="`lv--${r.level.toLowerCase()}`">
                  {{ LEVEL_LABEL[r.level] }}
                </span>
              </td>
              <td>
                <div class="obj">{{ r.poi_name }}</div>
                <div class="obj__sub">
                  {{ r.district || '—' }} ·
                  <span :class="{ 'is-today': dateOnly(r.stat_date) === baseDate }">
                    {{ r.stat_date }}
                  </span>
                  <!-- 区间不是"只看今日"时，给基准日的行加个记号 —— 一眼分出"新的" -->
                  <em v-if="showTodayMark && dateOnly(r.stat_date) === baseDate" class="todaymark">
                    今日
                  </em>
                </div>
              </td>
              <td class="num c-num">{{ metricText(r) }}</td>
              <td class="dim">{{ RISK_TYPE_LABEL[r.type] ?? r.type }}</td>
              <td>
                <span class="st" :class="`st--${riskTone(r.status)}`">
                  {{ RISK_STATUS_LABEL[r.status] }}
                </span>
              </td>
              <td class="c-code dim">{{ r.work_order_id ? `#${r.work_order_id}` : '—' }}</td>
              <td class="c-code">
                <span v-if="noticeOf(r)" class="st" :class="`st--${noticeTone(noticeOf(r)!.status)}`">
                  {{ NOTICE_STATUS_LABEL[noticeOf(r)!.status] }}
                </span>
                <span v-else class="dim">—</span>
              </td>
              <td />
              <td class="c-act">
                <!--
                  一行只留一个按钮。待处理的写「处理」，其余写「查看」——
                  动词不同，运营扫一眼就知道哪几行要动手。

                  ★ 「处理」是**金色实心**、「查看」是**描边**：这一页要回答的
                    问题就是"哪几行要我现在动手"，把可操作的那一行点亮、
                    把只读的那一行压下去，比给所有按钮加装饰有用得多。
                -->
                <button
                  v-if="r.status === 'OPEN'"
                  class="btn btn-act btn-sm"
                  @click.stop="openRisk(r)"
                >
                  处理
                </button>
                <button v-else class="btn btn-view btn-sm" @click.stop="openRisk(r)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- ==================== 工单 ==================== -->
    <template v-else-if="tab === 'orders'">
      <div v-if="!ordersIn.length" class="empty">
        <div class="empty__title">{{ rangeText }} 没有工单</div>
        <div class="empty__desc">
          本页按风险统计日筛。在「风险事件」里对某一条点「处理」建单，它就会出现在这里。
        </div>
        <div class="empty__acts">
          <button v-if="rangeMode !== 'TODAY'" class="btn btn-ghost btn-sm" @click="resetRange">
            回到今日（{{ baseDate }}）
          </button>
          <button v-if="rangeMode !== 'ALL'" class="btn btn-ghost btn-sm" @click="rangeMode = 'ALL'">
            看全部
          </button>
        </div>
      </div>

      <div v-else class="tablewrap">
        <table class="rt rkt">
          <thead>
            <tr>
              <th class="w-code">单号</th>
              <th class="w-obj">风险对象</th>
              <th class="w-type">类型</th>
              <th class="w-lv">等级</th>
              <th class="w-st">状态</th>
              <th class="w-who">处置人</th>
              <th class="w-time">创建时间</th>
              <th class="w-fill" />
              <th class="w-act" />
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="w in ordersIn"
              :key="w.id"
              :class="{ 'is-sel': drawer?.kind === 'order' && drawer.id === w.id }"
              @click="openOrder(w)"
            >
              <td class="c-code mono">{{ w.code }}</td>
              <td>
                <div class="obj">{{ w.risk_poi_name }}</div>
                <!-- 副行里的统计日才是**被筛的那一列**，所以"今日"记号挂在这里 -->
                <div class="obj__sub">
                  来源事件 #{{ w.risk_event_id }} ·
                  <span :class="{ 'is-today': orderDate(w) === baseDate }">{{ w.risk_stat_date }}</span>
                  <em v-if="showTodayMark && orderDate(w) === baseDate" class="todaymark">今日</em>
                </div>
              </td>
              <td class="dim">{{ ACTION_TYPE_LABEL[w.type] ?? w.type }}</td>
              <td>
                <span class="lv" :class="`lv--${w.level.toLowerCase()}`">{{ LEVEL_LABEL[w.level] }}</span>
              </td>
              <td>
                <span class="st" :class="`st--${orderTone(w.status)}`">
                  {{ WORK_ORDER_STATUS_LABEL[w.status] }}
                </span>
              </td>
              <!--
                空处置人写 `—` 而不是"待认领"：左边「状态」列在 PENDING 时
                已经是"待认领"了，同一个词连着出现两遍，读起来像两个字段说了
                同一件事（旧版就有这个毛病）。这里只表示"这个格没有值"。
              -->
              <td class="dim">{{ w.assignee || '—' }}</td>
              <td class="dim c-code">{{ when(w.created_at) }}</td>
              <td />
              <td class="c-act">
                <button
                  v-if="w.status !== 'DONE'"
                  class="btn btn-act btn-sm"
                  @click.stop="openOrder(w)"
                >
                  处置
                </button>
                <button v-else class="btn btn-view btn-sm" @click.stop="openOrder(w)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- ==================== 分流公告 ==================== -->
    <template v-else-if="tab === 'notices'">
      <div v-if="!noticesIn.length" class="empty">
        <div class="empty__title">{{ rangeText }} 没有分流公告</div>
        <div class="empty__desc">
          本页按风险统计日筛。到「风险事件」里找一条带分流方案的事件，
          点「处理」→「生成公告草稿」；公告要人工发布才会出现在游客端。
        </div>
        <div class="empty__acts">
          <button v-if="rangeMode !== 'TODAY'" class="btn btn-ghost btn-sm" @click="resetRange">
            回到今日（{{ baseDate }}）
          </button>
          <button v-if="rangeMode !== 'ALL'" class="btn btn-ghost btn-sm" @click="rangeMode = 'ALL'">
            看全部
          </button>
        </div>
      </div>

      <div v-else class="tablewrap">
        <table class="rt rkt">
          <thead>
            <tr>
              <th class="w-code">编码</th>
              <th class="w-obj">来源点</th>
              <th class="w-st">状态</th>
              <th class="w-title">标题</th>
              <th class="w-cand">可去</th>
              <th class="w-time">有效期至</th>
              <th class="w-fill" />
              <th class="w-act" />
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="n in noticesIn"
              :key="n.id"
              :class="{ 'is-sel': drawer?.kind === 'notice' && drawer.id === n.id }"
              @click="openNotice(n)"
            >
              <td class="c-code mono">{{ n.code }}</td>
              <td>
                <div class="obj">{{ n.from_poi_name }}</div>
                <div class="obj__sub">
                  {{ n.district || '—' }} · 统计日
                  <span :class="{ 'is-today': noticeDate(n) === baseDate }">{{ noticeDate(n) }}</span>
                  <em v-if="showTodayMark && noticeDate(n) === baseDate" class="todaymark">今日</em>
                </div>
              </td>
              <td>
                <span class="st" :class="`st--${noticeTone(n.status)}`">
                  {{ NOTICE_STATUS_LABEL[n.status] }}
                </span>
              </td>
              <td class="c-title">{{ n.title }}</td>
              <td class="num c-num">{{ n.available_count }} 处</td>
              <td class="dim c-code">{{ when(n.expire_at) }}</td>
              <td />
              <td class="c-act">
                <button
                  v-if="n.status === 'DRAFT'"
                  class="btn btn-act btn-sm"
                  @click.stop="openNotice(n)"
                >
                  发布
                </button>
                <button v-else class="btn btn-view btn-sm" @click.stop="openNotice(n)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- ==================== 详情抽屉 ==================== -->
    <Transition name="dw">
      <div v-if="drawer" class="dw" @click.self="closeDrawer">
        <aside class="dw__panel" @click="moreOpen = false">
          <!-- ---------- 风险详情 ---------- -->
          <template v-if="drawer.kind === 'risk' && curRisk">
            <header class="dw__head">
              <div class="dw__eyebrow">
                <span class="lv" :class="`lv--${curRisk.level.toLowerCase()}`">
                  {{ LEVEL_LABEL[curRisk.level] }}
                </span>
                <span class="st" :class="`st--${riskTone(curRisk.status)}`">
                  {{ RISK_STATUS_LABEL[curRisk.status] }}
                </span>
                <span class="dim">{{ RISK_TYPE_LABEL[curRisk.type] ?? curRisk.type }}</span>
              </div>
              <h2 class="dw__title">{{ curRisk.poi_name }}</h2>
              <p class="dw__sub">
                {{ curRisk.title }} · {{ curRisk.district || '—' }} · {{ curRisk.stat_date }}
              </p>
              <button class="dw__x" aria-label="关闭" @click.stop="closeDrawer">×</button>
            </header>

            <div class="dw__body">
              <!-- ① 风险概况 -->
              <section class="sec">
                <h3 class="sec__h">风险概况</h3>
                <dl class="kv">
                  <template v-for="it in riskKv" :key="it.k">
                    <dt>{{ it.k }}</dt>
                    <dd>
                      {{ it.v }}
                      <small v-if="it.hint">{{ it.hint }}</small>
                    </dd>
                  </template>
                </dl>
                <p v-if="showDetail" class="evi">{{ curRisk.detail }}</p>
              </section>

              <!-- ② 分流建议 -->
              <section class="sec">
                <h3 class="sec__h">
                  分流建议
                  <span class="sec__note">就近乡村优先，其次就近景区</span>
                </h3>

                <p v-if="!planOf(curRisk).length" class="none">
                  该规则不涉及分流，处置动作在工单里。
                </p>
                <ul v-else class="cands">
                  <li v-for="(c, i) in planOf(curRisk)" :key="c.poi_id" class="cand">
                    <div class="cand__row">
                      <span class="cand__no">{{ i + 1 }}</span>
                      <span class="cand__name">{{ c.name }}</span>
                      <span class="cand__type">{{ c.business_type === 'RURAL_SPOT' ? '乡村' : '景区' }}</span>
                      <span class="cand__metrics num">
                        {{ kmText(c.km) }} · 当前 {{ pctText(c.current_usage) }}
                      </span>
                    </div>
                    <p class="cand__reason">{{ c.reason }}</p>
                  </li>
                </ul>
              </section>

              <!-- ③ 工单处理 -->
              <section class="sec">
                <h3 class="sec__h">
                  工单处理
                  <button
                    v-if="orderOf(curRisk) && orderOf(curRisk)!.status !== 'DONE'"
                    class="sec__act"
                    @click="openOrder(orderOf(curRisk)!)"
                  >
                    打开工单 →
                  </button>
                </h3>

                <p v-if="!orderOf(curRisk)" class="none">尚未建单。</p>
                <dl v-else class="kv">
                  <dt>单号</dt>
                  <dd class="mono">{{ orderOf(curRisk)!.code }}</dd>
                  <dt>状态</dt>
                  <dd>
                    <span class="st" :class="`st--${orderTone(orderOf(curRisk)!.status)}`">
                      {{ WORK_ORDER_STATUS_LABEL[orderOf(curRisk)!.status] }}
                    </span>
                  </dd>
                  <dt>处置人</dt>
                  <dd>{{ orderOf(curRisk)!.assignee || '—' }}</dd>
                  <dt>创建时间</dt>
                  <dd>{{ when(orderOf(curRisk)!.created_at) }}</dd>
                  <template v-if="orderOf(curRisk)!.status === 'DONE'">
                    <dt>处置反馈</dt>
                    <dd>{{ orderOf(curRisk)!.result || '—' }}</dd>
                    <dt>完结时间</dt>
                    <dd>{{ when(orderOf(curRisk)!.handled_at) }}</dd>
                  </template>
                </dl>
              </section>

              <!-- ④ 分流公告 -->
              <section class="sec">
                <h3 class="sec__h">分流公告</h3>

                <p v-if="!noticeOf(curRisk)" class="none">尚未生成公告。</p>
                <template v-else>
                  <dl class="kv">
                    <dt>编码</dt>
                    <dd class="mono">{{ noticeOf(curRisk)!.code }}</dd>
                    <dt>状态</dt>
                    <dd>
                      <span class="st" :class="`st--${noticeTone(noticeOf(curRisk)!.status)}`">
                        {{ NOTICE_STATUS_LABEL[noticeOf(curRisk)!.status] }}
                      </span>
                      <small v-if="noticeOf(curRisk)!.published_by">
                        {{ noticeOf(curRisk)!.published_by }} 发布
                      </small>
                    </dd>
                    <dt>有效期至</dt>
                    <dd>
                      {{ when(noticeOf(curRisk)!.expire_at) }}
                      <small>当前可去 {{ noticeOf(curRisk)!.available_count }} 处</small>
                    </dd>
                  </dl>
                  <p class="ntitle">{{ noticeOf(curRisk)!.title }}</p>
                  <p class="nmsg">{{ noticeOf(curRisk)!.message }}</p>
                </template>
              </section>
            </div>

            <footer class="dw__foot">
              <span v-if="riskIdleHint && !primaryAct" class="dw__idle">{{ riskIdleHint }}</span>
              <div class="dw__spacer" />
              <div v-if="moreActs.length" class="more">
                <button class="btn btn-ghost btn-sm" @click.stop="moreOpen = !moreOpen">
                  更多
                </button>
                <ul v-if="moreOpen" class="more__menu">
                  <li v-for="a in moreActs" :key="a.key">
                    <button @click.stop="moreOpen = false; a.run()">{{ a.label }}</button>
                  </li>
                </ul>
              </div>
              <button
                v-if="primaryAct"
                class="btn btn-gold btn-sm"
                :disabled="busy"
                @click="primaryAct.run()"
              >
                {{ busy ? '提交中…' : primaryAct.label }}
              </button>
            </footer>
          </template>

          <!-- ---------- 工单详情 ---------- -->
          <template v-else-if="drawer.kind === 'order' && curOrder">
            <header class="dw__head">
              <div class="dw__eyebrow">
                <span class="st" :class="`st--${orderTone(curOrder.status)}`">
                  {{ WORK_ORDER_STATUS_LABEL[curOrder.status] }}
                </span>
                <span class="dim">{{ ACTION_TYPE_LABEL[curOrder.type] ?? curOrder.type }}</span>
              </div>
              <h2 class="dw__title mono">{{ curOrder.code }}</h2>
              <p class="dw__sub">{{ curOrder.title }} · {{ curOrder.risk_poi_name }}</p>
              <button class="dw__x" aria-label="关闭" @click.stop="closeDrawer">×</button>
            </header>

            <div class="dw__body">
              <section class="sec">
                <h3 class="sec__h">工单概况</h3>
                <dl class="kv">
                  <dt>来源事件</dt>
                  <dd class="mono">#{{ curOrder.risk_event_id }} · {{ curOrder.risk_stat_date }}</dd>
                  <dt>等级</dt>
                  <dd>{{ LEVEL_LABEL[curOrder.level] }}风险</dd>
                  <dt>处置人</dt>
                  <dd>{{ curOrder.assignee || '—' }}</dd>
                  <dt>创建时间</dt>
                  <dd>{{ when(curOrder.created_at) }}</dd>
                  <template v-if="curOrder.status === 'DONE'">
                    <dt>完结时间</dt>
                    <dd>{{ when(curOrder.handled_at) }}</dd>
                  </template>
                </dl>
                <p class="evi">{{ curOrder.suggestion }}</p>
              </section>

              <section v-if="curOrder.status === 'DONE'" class="sec">
                <h3 class="sec__h">处置记录</h3>
                <p class="nmsg">{{ curOrder.result || '—' }}</p>
              </section>

              <section v-else class="sec">
                <h3 class="sec__h">填写处置</h3>
                <div class="fld">
                  <label class="fld__l" :for="`as-${curOrder.id}`">处置人</label>
                  <input
                    :id="`as-${curOrder.id}`"
                    v-model="orderForm.assignee"
                    class="fld__i"
                    type="text"
                    maxlength="32"
                    placeholder="如：张工"
                  />
                </div>
                <div class="fld">
                  <label class="fld__l" :for="`rs-${curOrder.id}`">
                    处置反馈{{ curOrder.status === 'PROCESSING' ? '（完结时必填）' : '（可空）' }}
                  </label>
                  <textarea
                    :id="`rs-${curOrder.id}`"
                    v-model="orderForm.result"
                    class="fld__t"
                    rows="3"
                    maxlength="200"
                    placeholder="如：已启动分时预约，向龙湾村定向导流 420 人"
                  />
                </div>
                <p v-if="orderErr" class="err">{{ orderErr }}</p>
              </section>
            </div>

            <footer class="dw__foot">
              <div class="dw__spacer" />
              <button
                v-if="curOrder.status !== 'DONE' && curOrder.status === 'PENDING'"
                class="btn btn-ghost btn-sm"
                :disabled="busy"
                @click="submitProcessing(curOrder)"
              >
                认领并置处置中
              </button>
              <button
                v-if="curOrder.status !== 'DONE'"
                class="btn btn-gold btn-sm"
                :disabled="busy"
                @click="submitDone(curOrder)"
              >
                {{ busy ? '提交中…' : '完结并提交反馈' }}
              </button>
            </footer>
          </template>

          <!-- ---------- 公告详情 ---------- -->
          <template v-else-if="drawer.kind === 'notice' && curNotice">
            <header class="dw__head">
              <div class="dw__eyebrow">
                <span class="st" :class="`st--${noticeTone(curNotice.status)}`">
                  {{ NOTICE_STATUS_LABEL[curNotice.status] }}
                </span>
                <span v-if="curNotice.synthetic" class="dim">演示用仿真数据</span>
              </div>
              <h2 class="dw__title mono">{{ curNotice.code }}</h2>
              <p class="dw__sub">{{ curNotice.title }}</p>
              <button class="dw__x" aria-label="关闭" @click.stop="closeDrawer">×</button>
            </header>

            <div class="dw__body">
              <section class="sec">
                <h3 class="sec__h">公告概况</h3>
                <dl class="kv">
                  <dt>来源点</dt>
                  <dd>
                    {{ curNotice.from_poi_name }}
                    <small>{{ curNotice.district || '—' }}</small>
                  </dd>
                  <dt>有效期至</dt>
                  <dd>
                    {{ when(curNotice.expire_at) }}
                    <small>当前可去 {{ curNotice.available_count }} 处</small>
                  </dd>
                  <dt>发布人</dt>
                  <dd>{{ curNotice.published_by || '—' }}</dd>
                </dl>
              </section>

              <section class="sec">
                <h3 class="sec__h">
                  公告内容
                  <button
                    v-if="!noticeEditing"
                    class="sec__act"
                    @click="noticeEditing = true"
                  >
                    编辑文案
                  </button>
                </h3>

                <template v-if="noticeEditing">
                  <div class="fld">
                    <label class="fld__l" :for="`nt-${curNotice.id}`">标题</label>
                    <input
                      :id="`nt-${curNotice.id}`"
                      v-model="noticeForm.title"
                      class="fld__i"
                      type="text"
                      maxlength="128"
                    />
                  </div>
                  <div class="fld">
                    <label class="fld__l" :for="`nm-${curNotice.id}`">
                      正文（会原样显示在首页，请保留"仿真数据"字样）
                    </label>
                    <textarea
                      :id="`nm-${curNotice.id}`"
                      v-model="noticeForm.message"
                      class="fld__t"
                      rows="3"
                      maxlength="512"
                    />
                  </div>
                  <p v-if="noticeErr" class="err">{{ noticeErr }}</p>
                  <div class="fld__acts">
                    <button class="btn btn-ghost btn-sm" :disabled="busy" @click="saveNotice(curNotice)">
                      保存文案
                    </button>
                    <button class="btn btn-ghost btn-sm" @click="noticeEditing = false">取消</button>
                  </div>
                </template>
                <template v-else>
                  <p class="ntitle">{{ curNotice.title }}</p>
                  <p class="nmsg">{{ curNotice.message }}</p>
                </template>
              </section>

              <!--
                候选两组数都写出来：`推荐时` 是发布那一刻的快照（永远不变），
                `当前` 是打开这一刻重算的。只写一个都是不诚实的 ——
                只写快照等于拿旧数据骗游客，只写当前就答不出"当时为什么推荐它"。
              -->
              <section class="sec">
                <h3 class="sec__h">候选点（发布时快照）</h3>
                <ul class="cands">
                  <li v-for="(c, i) in curNotice.candidates" :key="c.poi_id" class="cand">
                    <div class="cand__row">
                      <span class="cand__no">{{ i + 1 }}</span>
                      <span class="cand__name">{{ c.name }}</span>
                      <span class="cand__type">{{ c.business_type === 'RURAL_SPOT' ? '乡村' : '景区' }}</span>
                      <span class="cand__metrics num">
                        {{ kmText(c.km) }} · 推荐时 {{ pctText(c.usage) }} · 当前
                        {{ pctText(c.current_usage) }}
                      </span>
                      <span v-if="c.available === false" class="cand__off">现已不宽裕</span>
                    </div>
                  </li>
                </ul>
              </section>
            </div>

            <footer class="dw__foot">
              <span v-if="curNotice.status === 'EXPIRED'" class="dw__idle">
                已过期，不能直接发布；重新生成会顶替这一条。
              </span>
              <div class="dw__spacer" />
              <button
                v-if="curNotice.status === 'DRAFT' && noticeEditing"
                class="btn btn-ghost btn-sm"
                :disabled="busy"
                @click="saveNotice(curNotice)"
              >
                保存文案
              </button>
              <button
                v-if="curNotice.status === 'DRAFT' || curNotice.status === 'WITHDRAWN'"
                class="btn btn-gold btn-sm"
                :disabled="busy"
                @click="setNoticeStatus(curNotice, 'PUBLISHED')"
              >
                {{ busy ? '提交中…' : '发布到游客端' }}
              </button>
              <button
                v-else-if="curNotice.status === 'PUBLISHED'"
                class="btn btn-ghost btn-sm"
                :disabled="busy"
                @click="setNoticeStatus(curNotice, 'WITHDRAWN')"
              >
                撤下公告
              </button>
              <button
                v-else-if="curNotice.status === 'EXPIRED' && curNotice.risk_event_id != null"
                class="btn btn-gold btn-sm"
                :disabled="busy"
                @click="regenFromNotice(curNotice)"
              >
                {{ busy ? '生成中…' : '重新生成' }}
              </button>
            </footer>
          </template>
        </aside>
      </div>
    </Transition>
  </div>
</template>

<script lang="ts">
export default {}
</script>

<style scoped>
/* ============================================================
   页头
   ============================================================ */
.rk__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-4);
  margin-bottom: var(--sp-5);
}
.rk__tools {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-shrink: 0;
}

/*
  仿真数据角标。与 Dashboard / Media / Orders 是同一个语义、同一份样式，
  但那些是各自 scoped 里写的，本页不写就只是一个没有样式的 `<span>`。
*/
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

/* ============================================================
   「重新扫描」：页头唯一的高饱和动作
   ============================================================
   金色描边 + 淡金底，而不是沿用 `.btn-ghost` 的中性描边 —— 深绿底上
   中性描边几乎融进背景，它是这一页唯一会"重算一遍全量规则"的按钮，
   找不到就没人会点。

   做成描边而不是实心，是为了给行内的「处理 / 发布」留出实心金的位置：
   一页里只能有一层"最重的金色"，否则金色一多就都不显眼了。
*/
.btn-scan {
  background: rgba(192, 154, 78, 0.14);
  color: var(--gold-300);
  border: 1px solid rgba(192, 154, 78, 0.5);
}
.btn-scan:hover:not(:disabled) {
  background: rgba(192, 154, 78, 0.24);
  border-color: var(--gold-500);
  color: #fff;
}
.btn-scan:disabled {
  cursor: progress;
}
.btn-scan__ico {
  display: inline-block;
  font-size: 15px;
  line-height: 1;
  transform-origin: 50% 50%;
}
.btn-scan__ico.is-spin {
  animation: rk-spin 900ms linear infinite;
}
@keyframes rk-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .btn-scan__ico.is-spin {
    animation-duration: 2.4s;
  }
}

/* ============================================================
   统计条（同时是筛选器）
   ============================================================
   不做成四张卡片：四张卡就是四个边框、四份留白，而它要传达的只是
   四个数字。用一条横向的、无边框的分段，靠"数字大、标签小"形成层级。
*/
.stats {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding-bottom: var(--sp-4);
  border-bottom: 1px solid var(--line);
}
.stats__gap {
  flex: 1;
  min-width: var(--sp-4);
}
.stat {
  display: inline-flex;
  align-items: baseline;
  gap: 7px;
  padding: 6px 12px;
  border: 0;
  border-radius: var(--r-md);
  background: transparent;
  color: var(--text-2);
  cursor: pointer;
  transition: background var(--dur-1) var(--ease), color var(--dur-1) var(--ease);
}
.stat:hover {
  background: rgba(146, 178, 165, 0.08);
  color: var(--text);
}
/*
  选中态：全页统一用品牌绿 + 一圈内描边。

  原来这里是蓝色淡底（`rgba(46,123,196,0.16)`），跟下面分段控件的绿色实心
  不是一套 —— 同一个筛选动作在两种控件里给出两种"选中"颜色，用户得学两遍。
  统一成绿色之后，这一页只剩两种强调色：**绿色 = 选中的筛选**，
  **金色 = 当前页签 / 主操作**。
*/
.stat--on {
  background: rgba(61, 139, 116, 0.22);
  color: #fff;
  box-shadow: inset 0 0 0 1px rgba(113, 169, 150, 0.45);
}
.stat__n {
  font-family: var(--font-num);
  font-size: 22px;
  font-weight: 500;
  line-height: 1;
  letter-spacing: 0.01em;
}
.stat__l {
  font-size: var(--fs-cap);
  color: var(--text-3);
}
.stat--on .stat__l {
  color: var(--text-2);
}

/* 等级筛选：纯文字分段，不加重边框 */
.seg {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: var(--r-md);
  background: rgba(146, 178, 165, 0.07);
}
.seg__b {
  border: 0;
  background: transparent;
  color: var(--text-3);
  font-size: var(--fs-cap);
  padding: 4px 10px;
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: all var(--dur-1) var(--ease);
}
.seg__b:hover {
  color: var(--text);
}
.seg__b--on {
  background: var(--brand-500);
  color: #fff;
}

/* ============================================================
   日期筛选条
   ============================================================
   放在统计条上面，因为它比统计条更"外层"：先决定看哪一段日子，再决定
   看哪一类。统计条与三个 Tab 的角标全都跟着它走。

   做成一条带底色的横条，是为了跟下面的统计条区分开 —— 统计条是**无边框**
   的一排数字（它要像"读数"，不像"控件"），日期这一条要像"控件"。
*/
.datebar {
  display: flex;
  align-items: center;
  /*
    ★ 间距 8px 而不是 12px，是量出来的：1440 下这条只有 1106px 可用，
      非「今日」模式要多塞「区间文本 + 基准日说明 + 回到今日」三样，
      实测溢出 147px（折成两行，条高 50 → 96px）。逐项收紧后各模式都留有余量。
  */
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-top: var(--sp-4);
  padding: 6px var(--sp-3) 6px var(--sp-4);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: rgba(18, 48, 38, 0.55);
}
.datebar__label {
  font-size: var(--fs-cap);
  letter-spacing: 0.08em;
  color: var(--text-3);
}
/* 解析后的实际区间。写在控件右边，回答"我现在到底在看哪几天" */
.datebar__range {
  font-family: var(--font-num);
  font-size: var(--fs-sm);
  font-weight: 600;
  letter-spacing: 0.01em;
  color: var(--text);
  white-space: nowrap;
  cursor: help;
}
/* 基准日 ≠ 系统当天时的说明。是"提示"不是"报错"，所以用金而不是红 */
.datebar__note {
  padding: 2px 8px;
  border-radius: var(--r-pill);
  font-size: var(--fs-cap);
  color: var(--gold-300);
  background: rgba(192, 154, 78, 0.14);
  white-space: nowrap;
  cursor: help;
}
/*
  口径提示。只留一个短词 —— 完整解释在 `title` 里。

  这里曾经写的是「统一按风险统计日归类」（11 字 / 120px），它和区间文本、
  基准日说明、回到今日按钮一起把这条挤到两行；而它本质是一句**解释数据模型**
  的话，不是控件，放 tooltip 比占一整行更合适。

  `margin-left: auto` 把它推到最右，替代了原来那个专门的 `.datebar__spacer`
  元素 —— 分隔本身也是一个子元素，要占 8px 宽和 8px 间距（实测全部模式下
  正是这 16px 让这条溢出）。
*/
.datebar__hint {
  margin-left: auto;
  font-size: var(--fs-cap);
  color: var(--text-3);
  white-space: nowrap;
  cursor: help;
}

/* 日期分段：比等级分段大一号，它是这一页的一级控件 */
.seg--date .seg__b {
  padding: 5px 14px;
  font-size: var(--fs-xs);
}

/*
  自定义区间。
  原生 `<input type="date">` 在深色底上默认是浅色控件（白底、黑字、
   黑日历图标），必须显式适配：
   - `color-scheme: dark` 让弹出的日历面板也走深色，并让日历图标自动反白
   - 文字色/背景自己给，不吃 `color-scheme` 的默认值
*/
.datebar__custom {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 8px 2px 10px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: rgba(0, 0, 0, 0.18);
  transition: border-color var(--dur-1) var(--ease), background var(--dur-1) var(--ease);
}
.datebar__custom.is-on {
  border-color: var(--brand-400);
  background: rgba(61, 139, 116, 0.14);
}
.datebar__d {
  width: 104px;
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: var(--text-3);
  font-family: var(--font-num);
  font-size: var(--fs-cap);
  outline: none;
  cursor: pointer;
  color-scheme: dark;
}
.datebar__custom.is-on .datebar__d {
  color: var(--text);
}
.datebar__tilde {
  font-size: var(--fs-cap);
  color: var(--text-3);
}

/* ============================================================
   行内操作按钮：主操作实心金，其余描边
   ============================================================
   一屏十几行，每行都摆两个同重的按钮，等于没有重点。所以按"这条记录
   还有没有事要做"分两层：
   - `btn-act`  待处置/待认领/草稿 → 金色实心，扫一眼就知道该点哪行
   - `btn-view` 已建单/已完结/已发布 → 中性描边，退到背景里

   沿用 `.btn` / `.btn-sm` 的尺寸（34px 高），只换配色，不另起一套按钮。
*/
.btn-act {
  background: var(--gold-500);
  color: #3a2a0c;
  box-shadow: var(--sh-gold);
}
.btn-act:hover:not(:disabled) {
  background: var(--gold-600);
  color: #fff;
}
.btn-view {
  background: transparent;
  color: var(--text-2);
  border: 1px solid var(--line-strong);
}
.btn-view:hover:not(:disabled) {
  background: rgba(146, 178, 165, 0.12);
  border-color: var(--brand-300);
  color: #fff;
}

/* ---------- 提示条（结构由 useNotice 约定，样式各页自己写） ---------- */
.notice {
  margin: var(--sp-4) 0;
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

/* ============================================================
   一级 Tab
   ============================================================ */
.tabs {
  display: flex;
  gap: var(--sp-5);
  border-bottom: 1px solid var(--line);
  margin: var(--sp-5) 0 var(--sp-4);
}
.tab {
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--text-3);
  font-size: var(--fs-sm);
  font-weight: 500;
  padding: 0 0 var(--sp-3);
  margin-bottom: -1px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: color var(--dur-1) var(--ease);
}
.tab:hover {
  color: var(--text);
}
.tab--on {
  color: #fff;
  border-bottom-color: var(--gold-500);
}
.tab__n {
  font-family: var(--font-num);
  font-size: var(--fs-cap);
  color: var(--text-3);
}
.tab--on .tab__n {
  color: var(--gold-300);
}

/* ============================================================
   表格（与「资源管理」同一套语言）
   ============================================================ */
.tablewrap {
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: rgba(18, 48, 38, 0.42);
}
.rt {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}
.rkt {
  min-width: 1040px;
}
.rt th {
  text-align: left;
  padding: var(--sp-3) var(--sp-4);
  font-size: var(--fs-cap);
  font-weight: 500;
  color: var(--text-3);
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}
.rt td {
  padding: 10px var(--sp-4);
  border-bottom: 1px solid var(--line);
  vertical-align: middle;
}
/*
  数据列不折行。

  下面那个弹性空列（`.w-fill`）声明了 `width: 100%`，浏览器为了给它腾地方，
  会把其余列一路压到**最小内容宽度** —— 允许折行的话「客流超载」会被压成
  "客流超"/"载" 两行，行高从 66px 涨到 78px，一屏少看两条。
  锁成 nowrap 后，最小内容宽度就是它本身的宽度，压不动了。
*/
.rkt tbody td {
  white-space: nowrap;
}
/* 公告标题是唯一该折行的：它可能很长，且不需要跟谁对齐 */
.rkt .c-title {
  white-space: normal;
}
.rt tbody tr {
  cursor: pointer;
  transition: background var(--dur-1) var(--ease);
}
.rt tbody tr:last-child td {
  border-bottom: none;
}
.rt tbody tr:hover td {
  background: rgba(146, 178, 165, 0.06);
}
/* 抽屉里正在看的那一行：整行压一层底色，扫列表时不会跟丢 */
.rt tbody tr.is-sel td {
  background: rgba(46, 123, 196, 0.12);
}

/* 列宽：数据列按内容收窄，多余的宽度全给下面这个弹性空列 —— 1920 下不会散开 */
.w-lv {
  width: 56px;
}
.w-obj {
  min-width: 240px;
}
.w-num {
  width: 96px;
}
.w-type {
  width: 110px;
}
.w-st {
  width: 92px;
}
.w-code {
  width: 96px;
}
.w-who {
  width: 100px;
}
.w-time {
  width: 128px;
}
.w-title {
  min-width: 240px;
}
.w-cand {
  width: 80px;
}
.w-act {
  width: 84px;
}
/*
  弹性空列：把表格多余的宽度全吃在这里。

  没有它，多余宽度会摊到「风险对象」那一列上 —— 1920 下对象名和它的指标之间
  会空出 500px，一行要横着扫一大段才连得起来。有了它，数据列紧挨着对象列，
  余量落在最右（操作列的左边），读起来是一段连续的信息。
*/
.w-fill {
  width: 100%;
  padding: 0;
}

.obj {
  color: #fff;
  font-weight: 600;
}
.obj__sub {
  margin-top: 2px;
  font-size: var(--fs-cap);
  color: var(--text-3);
}
/*
  统计日恰好等于基准日的那一天：文字提亮一档。
  刻意**不用金色** —— 金色留给右边的「今日」小标签，两个都金就互相抵消了。
*/
.obj__sub .is-today {
  color: var(--text);
  font-weight: 500;
}
/*
  「今日」标记只在**看多天的时候**出现（`showTodayMark`）。
  看的就是今天那一天时，每行都挂一个"今日"是纯噪音。
*/
.todaymark {
  display: inline-block;
  margin-left: 6px;
  padding: 0 5px;
  border-radius: var(--r-pill);
  font-size: 10px;
  font-style: normal;
  font-weight: 600;
  line-height: 15px;
  vertical-align: 1px;
  color: #3a2a0c;
  background: var(--gold-300);
}
.c-num {
  text-align: right;
  color: var(--text);
  font-weight: 500;
}
.c-code {
  white-space: nowrap;
}
.c-title {
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 1px;
}
.c-act {
  text-align: right;
  white-space: nowrap;
}
.dim {
  color: var(--text-3);
}
.mono {
  font-family: var(--font-mono, monospace);
  font-size: var(--fs-cap);
}

/* ============================================================
   空态
   ============================================================
   全局的 `.empty` 是给游客端写的（`--warm-500` 暖灰、无边框）。管理端
   深绿底上要两处改写：
   1. 颜色换成本页的 `--text-3` / `--text`，否则暖灰在深绿上发闷
   2. **补一层边框和底色**，让它占住表格本来的那块位置 —— 否则从"有表"
     切到"没表"，下面的内容会整个往上跳一截

   文案本身由模板给：标题带区间、描述按"这段没有 / 全都没有"分两种，
   再加三个快捷出口。空态最怕的不是空，是说不清"为什么空"。
*/
.empty {
  padding: var(--sp-7) var(--sp-5);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: rgba(18, 48, 38, 0.42);
}
.empty__title {
  color: var(--text);
}
.empty__desc {
  color: var(--text-3);
  line-height: 1.7;
}
.empty__acts {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin-top: var(--sp-4);
}

/* ============================================================
   等级 / 状态：着色文字，不做徽标
   ============================================================
   一屏十几行，每行三四个胶囊会把注意力切碎。等级只用一个 18px 的
   色块字母，状态与公告态只用文字着色 —— 颜色承载语义，形状不承载。
*/
.lv {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: var(--r-sm);
  font-size: var(--fs-cap);
  font-weight: 600;
}
.lv--high {
  color: #fdeeea;
  background: rgba(168, 64, 43, 0.8);
}
.lv--mid {
  color: #fdf5e4;
  background: rgba(168, 121, 29, 0.7);
}

.st {
  font-size: var(--fs-cap);
  white-space: nowrap;
}
.st--warn {
  color: #e8c079;
}
.st--info {
  color: #93bfe6;
}
.st--ok {
  color: #7cc4a6;
}
.st--mute {
  color: var(--text-3);
}

/* ============================================================
   详情抽屉
   ============================================================
   右侧滑出而不是居中弹窗：详情里四段内容是**从上往下读**的，
   居中小窗一屏放不下就要内部再滚一次，读起来像在钻管道。
   右抽屉高度吃满，滚动条只有一根。
*/
.dw {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal, 60);
  display: flex;
  justify-content: flex-end;
  background: rgba(4, 14, 10, 0.5);
  backdrop-filter: blur(2px);
}
.dw__panel {
  position: relative;
  width: min(560px, 94vw);
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #0f2a21;
  border-left: 1px solid var(--line-strong);
  box-shadow: -18px 0 48px rgba(0, 0, 0, 0.42);
}
.dw-enter-active,
.dw-leave-active {
  transition: opacity var(--dur-2) var(--ease);
}
.dw-enter-active .dw__panel,
.dw-leave-active .dw__panel {
  transition: transform var(--dur-2) var(--ease);
}
.dw-enter-from,
.dw-leave-to {
  opacity: 0;
}
.dw-enter-from .dw__panel,
.dw-leave-to .dw__panel {
  transform: translateX(24px);
}

.dw__head {
  position: relative;
  padding: var(--sp-5) var(--sp-6) var(--sp-4);
  border-bottom: 1px solid var(--line);
}
.dw__eyebrow {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-cap);
}
.dw__title {
  margin: var(--sp-2) 0 0;
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 600;
  color: #fff;
  line-height: 1.35;
}
.dw__sub {
  margin: 4px 0 0;
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.dw__x {
  position: absolute;
  top: var(--sp-4);
  right: var(--sp-4);
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: var(--r-md);
  background: transparent;
  color: var(--text-3);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  transition: all var(--dur-1) var(--ease);
}
.dw__x:hover {
  background: rgba(146, 178, 165, 0.12);
  color: #fff;
}

.dw__body {
  flex: 1;
  overflow-y: auto;
  padding: 0 var(--sp-6) var(--sp-5);
}

/* 分节：只用一条细线，不给每节加边框 —— 边框一多就没有重点了 */
.sec {
  padding: var(--sp-5) 0;
  border-bottom: 1px solid var(--line);
}
.sec:last-child {
  border-bottom: none;
}
.sec__h {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--text-2);
  letter-spacing: 0.04em;
}
.sec__note {
  font-size: var(--fs-cap);
  font-weight: 400;
  color: var(--text-3);
}
.sec__act {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: var(--gold-300);
  font-size: var(--fs-cap);
  cursor: pointer;
  padding: 0;
}
.sec__act:hover {
  color: var(--gold-500);
}

/* 键值对：左标签右值，不给每行加线 */
.kv {
  display: grid;
  grid-template-columns: 96px 1fr;
  gap: 9px var(--sp-4);
  margin: 0;
}
.kv dt {
  font-size: var(--fs-cap);
  color: var(--text-3);
}
.kv dd {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text);
}
.kv dd small {
  display: block;
  margin-top: 2px;
  font-size: var(--fs-cap);
  color: var(--text-3);
}

.evi {
  margin: var(--sp-3) 0 0;
  padding-left: var(--sp-3);
  border-left: 2px solid var(--line-strong);
  font-size: var(--fs-cap);
  line-height: 1.7;
  color: var(--text-3);
}
.none {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-3);
}

/* ---------- 候选点 ---------- */
.cands {
  margin: 0;
  padding: 0;
  list-style: none;
}
.cand {
  padding: var(--sp-3) 0;
  border-top: 1px solid var(--line);
}
.cand:first-child {
  border-top: none;
  padding-top: 0;
}
.cand__row {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.cand__no {
  width: 16px;
  height: 16px;
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-pill);
  background: var(--gold-500);
  color: #1a211d;
  font-size: 11px;
  font-weight: 600;
}
.cand__name {
  color: #fff;
  font-weight: 600;
}
.cand__type {
  font-size: var(--fs-cap);
  color: var(--text-3);
}
.cand__metrics {
  margin-left: auto;
  font-size: var(--fs-cap);
  color: var(--brand-300);
  white-space: nowrap;
}
.cand__off {
  font-size: var(--fs-cap);
  color: #e8c079;
}
.cand__reason {
  margin: 4px 0 0 24px;
  font-size: var(--fs-cap);
  line-height: 1.65;
  color: var(--text-3);
}

/* ---------- 公告正文 ---------- */
.ntitle {
  margin: var(--sp-3) 0 0;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: #fff;
  line-height: 1.6;
}
.nmsg {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-xs);
  line-height: 1.75;
  color: var(--text-2);
}

/* ---------- 表单 ---------- */
.fld {
  margin-top: var(--sp-3);
}
.fld__l {
  display: block;
  margin-bottom: 5px;
  font-size: var(--fs-cap);
  color: var(--text-3);
}
.fld__i,
.fld__t {
  width: 100%;
  background: rgba(0, 0, 0, 0.24);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--text);
  font-size: var(--fs-sm);
  font-family: inherit;
  padding: 8px 10px;
  outline: none;
}
.fld__i:focus,
.fld__t:focus {
  border-color: var(--brand-400);
}
.fld__t {
  resize: vertical;
  line-height: 1.7;
}
.fld__acts {
  display: flex;
  gap: var(--sp-2);
  margin-top: var(--sp-3);
}
.err {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-cap);
  color: #e8a08c;
}

/* ============================================================
   抽屉底部：一个主操作 + 「更多」
   ============================================================
   主按钮是金色（深绿底上唯一的高饱和色），所以它天然是视觉重点；
   其余动作收进「更多」，不让两个按钮争同一个位置。
*/
.dw__foot {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4) var(--sp-6);
  border-top: 1px solid var(--line);
  background: rgba(9, 26, 20, 0.6);
}
.dw__spacer {
  flex: 1;
}
.dw__idle {
  font-size: var(--fs-cap);
  color: var(--text-3);
  line-height: 1.6;
}
.more {
  position: relative;
}
.more__menu {
  position: absolute;
  right: 0;
  bottom: calc(100% + 6px);
  min-width: 150px;
  margin: 0;
  padding: 4px;
  list-style: none;
  background: #14372b;
  border: 1px solid var(--line-strong);
  border-radius: var(--r-md);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.45);
  z-index: 2;
}
.more__menu button {
  display: block;
  width: 100%;
  text-align: left;
  border: 0;
  background: transparent;
  color: var(--text-2);
  font-size: var(--fs-xs);
  padding: 8px 10px;
  border-radius: var(--r-sm);
  cursor: pointer;
}
.more__menu button:hover {
  background: rgba(146, 178, 165, 0.12);
  color: #fff;
}

/* ============================================================
   窄屏：抽屉吃满宽度，表格横向滚动（不改成卡片 —— 一改回卡片，
   这一页就白重构了）
   ============================================================ */
@media (max-width: 720px) {
  .dw__panel {
    width: 100%;
  }
  .dw__head,
  .dw__body,
  .dw__foot {
    padding-left: var(--sp-4);
    padding-right: var(--sp-4);
  }
  .kv {
    grid-template-columns: 84px 1fr;
  }
  /*
    日期条窄屏会折成两三行。折行之后 `margin-left: auto` 会把提示单独推到
    右侧一整行，反而更乱 —— 收回，让它顺着排。
  */
  .datebar {
    gap: var(--sp-2);
  }
  .datebar__hint {
    margin-left: 0;
  }
  .datebar__d {
    width: 104px;
  }
}

/* ---------- 风险总览三图 ---------- */
.riskcharts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-4);
  margin-bottom: var(--sp-5);
}
@media (max-width: 1100px) {
  .riskcharts {
    grid-template-columns: minmax(0, 1fr);
  }
}

.rchart {
  min-width: 0;
  padding: var(--sp-4);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: rgba(18, 48, 38, 0.5);
}
.rchart__t {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: #fff;
}
.rchart__tip {
  display: inline-grid;
  place-items: center;
  width: 14px;
  height: 14px;
  flex: none;
  font-size: 9px;
  font-weight: 400;
  color: var(--gold-300);
  border: 1px solid rgba(192, 154, 78, 0.5);
  border-radius: 50%;
  cursor: help;
}
.rchart__s {
  margin-top: 4px;
  margin-bottom: var(--sp-2);
  font-size: var(--fs-cap);
  color: var(--text-3);
}
.rchart__empty {
  display: grid;
  place-items: center;
  min-height: 180px;
  font-size: var(--fs-cap);
  color: var(--text-3);
  border: 1px dashed var(--line);
  border-radius: var(--r-md);
}
</style>
