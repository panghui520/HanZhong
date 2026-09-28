<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { askAgent, getAiHealth } from '@/api/ai'
import { clearTripHotel, getCurrentTrip, selectTripHotel } from '@/api/trips'
import PoiImage from '@/components/PoiImage.vue'
import { useAsync } from '@/composables/useAsync'
import { useSessionStore } from '@/stores/session'
import { clearAgentTurns, loadAgentTurns, saveAgentTurns } from '@/utils/agentChat'
import { inlineText } from '@/utils/format'
import {
  AGENT_TOOL_COUNT_UNIT,
  AGENT_TOOL_LABEL,
  HOTEL_BOOKING_LABEL,
  QA_DOC_TYPE_LABEL,
  QA_SOURCE_KIND_LABEL,
  type AgentCard,
  type AgentItineraryDay,
  type AgentSourceCard,
  type AgentToolEvent,
  type AgentTurn,
  type TripContext,
} from '@/types'

/**
 * M4 AI 旅游助手。
 *
 * 与「知识问答」页的分工，这是本页最重要的一件事：
 *
 *   - 知识问答（/assistant）回答的是**汉中这座城市的公开事实**，
 *     依据来自本地知识库，答案带出处；
 *   - 旅游助手（本页）解决的是**"我现在该去哪儿"**——它要联网问高德地图，
 *     拿回真实的酒店 / 餐厅 / 景点，再组织成一段推荐。
 *
 * 所以本页的核心不是"又一个聊天框"，而是**工具调度**：
 * 模型先判断这一问该走哪条路（查本地知识库？查高德？还是直接回答），
 * 再把工具返回的真实数据组织成人话。页面上那条「工具状态条」就是
 * 把这个过程显式地摆出来 —— 让"它真的调了高德"这件事可见、可核对。
 *
 * ============================================================
 * 三条本页必须守住的边界（与后端一致）
 * ============================================================
 * 1. **推荐 ≠ 预订。** 卡片只是推荐，本页不提供下单、支付、库存。
 *    预订要跳出到第三方平台。这是产品定位，不是"还没做"。
 * 2. **卡片数据不经过模型。** 卡片字段（名称/地址/距离/电话）逐字来自高德，
 *    模型只负责说人话。理由见 server-ai/app/tools.py 的模块说明。
 * 3. **不编造。** 模型被约束只能引用工具结果里出现过的地点与距离；
 *    工具没查到时会明说"这次没查到"，而不是编几家酒店出来。
 *
 * ============================================================
 * 阶段说明
 * ============================================================
 * 已实现：阶段一（高德 + 附近搜索 + 酒店卡片）、
 * **阶段二（行程上下文 + 选择酒店 + "这附近"有确定指代）**。
 *
 * 阶段二做的是"让助手记得住"：用户在高德返回的酒店卡片上点「选择酒店」，
 * 服务端把它写进 trip_context，此后他问"这附近有什么好吃的"时，
 * 搜索中心就是那家酒店的坐标 —— **不用再报一次住哪**。
 * 这条链路要登录才有；未登录时本页照常可用，只是没有记忆。
 *
 * 「去预订」（跳转第三方）在阶段五，代码里留了 TODO 锚点。
 * 现在不放这个按钮：它做不了真事，放上去就是假的。
 * 见 docs/M4-第一阶段设计分析.md。
 *
 * ============================================================
 * 对话历史会留下来
 * ============================================================
 * `turns` 从 sessionStorage 恢复，所以**切页、组件重新挂载、刷新页面**之后
 * 对话都还在。存在 sessionStorage 而不是 localStorage 的理由见
 * `utils/agentChat.ts` 的模块说明 —— 一句话：它是"这一次访问的对话"，
 * 不是长期资产，也不该在换个人用同一台机器时留给下一个人看。
 *
 * 落盘时机是**有限的四处**（轮次结束 / 组件卸载 / 整页刷新 / 清空），
 * 不是 `watch` 全量：流式回答每个 token 都改 `turn.answer`，
 * 全量监听等于每吐一个字就序列化一遍整段历史。
 *
 * **已知边界：同一个标签页里换账号登录，仍会看到上一个人的对话。**
 * 上面说的"不留给下一个人"指的是**关掉标签页之后**（sessionStorage 按标签页
 * 隔离）。同标签页内退出再登录，存储不归账号管，得靠「清空对话」。
 * 要做成按账号隔离，得在登录/退出时同步清理 —— 而退出登录会先跳走再卸载组件，
 * 卸载时的落盘会把刚清掉的又写回去，所以不能只在 store 里清。
 */

// ---------------------------------------------------------------- 能力状态

const { data: health, error: healthError } = useAsync(getAiHealth)

/**
 * 高德是否可用。注意写成 `=== true`：health 为 null（AI 服务没起来）时
 * `health?.agent?.amap_configured` 是 undefined，直接当布尔用会得到"不可用"，
 * 这个默认值是对的，但显式写出来能避免以后有人改成 `!health.amap_configured`
 * ——那样在 health 为 null 时会抛异常。
 */
const amapReady = computed(() => health.value?.agent?.amap_configured === true)
const kbReady = computed(() => health.value?.ok === true)

/** 示例问题由后端按**当前可用工具**生成，不在前端写死 */
const samples = computed(() => health.value?.agent?.samples ?? [])

/**
 * 顶部状态。
 *
 * ★ 面向游客：**只在出问题时才说话**，一切正常时整行不渲染。
 *   原来这里是"AI 服务状态 / 高德地图已接入 / 本地知识库可用 / deepseek-chat"四个徽标 ——
 *   那是运维和答辩口径。真实产品不会在页头告诉游客"我们的服务已就绪"、
 *   用了哪家地图、跑的是哪个模型；游客只关心"能不能用"。
 *   所以：正常 → 不显示；异常 → 给一句人话。
 *   （模型名在管理端驾驶舱仍然可见，见 admin/Dashboard.vue 的 AI 解读署名。）
 */
const status = computed(() => {
  if (healthError.value) return { lv: 'off', label: '助手暂时连不上，请稍后再试' }
  if (!health.value) return { lv: 'wait', label: '正在连接…' }
  if (!kbReady.value) return { lv: 'off', label: '本地资料暂时不可用，请稍后再试' }
  return { lv: 'ok', label: '' }
})

// ------------------------------------------------------------------ 对话

/**
 * 对话历史**从 sessionStorage 恢复**。
 *
 * 原本 `turns` 是个裸的 `ref([])`，组件一卸载就没了 —— 切到探索页看看景点
 * 再切回来、或者随手刷新一下，刚问出来的酒店列表和刚排好的行程全没了。
 *
 * 为什么是 sessionStorage（而不是 localStorage、也不是 Pinia）：
 * 见 `utils/agentChat.ts` 的模块说明 —— 一句话，它是"这一次访问的对话"，
 * 不是长期资产，也不该在换人用同一台机器时留给下一个人看。
 */
const turns = ref<AgentTurn[]>(loadAgentTurns())
const question = ref('')
const streaming = ref(false)
const composer = ref<HTMLTextAreaElement | null>(null)
const thread = ref<HTMLElement | null>(null)
let stopStream: (() => void) | null = null

/**
 * 轮次编号，只用于模板的 `:key`。
 * 从**已恢复的历史长度**接着往下发号，而不是从 0 开始 ——
 * 否则新提问会和历史里的某一轮撞 `:key`，Vue 复用错节点，
 * 表现是"新回答串到了旧问题下面"。
 * （`loadAgentTurns` 已把历史的 id 重排成 1..N，所以长度就是下一个号。）
 */
let seq = turns.value.length

const inSession = computed(() => turns.value.length > 0)

function submit(preset?: string) {
  const text = (preset ?? question.value).trim()
  if (!text || streaming.value) return

  question.value = ''
  turns.value.push({
    id: ++seq,
    question: text,
    answer: '',
    meta: null,
    tool: null,
    cards: [],
    sources: [],
    itinerary: null,
    cardKind: '',
    elapsedMs: 0,
    streaming: true,
    error: '',
  })
  // 取回响应式代理再改：直接改 push 前的那个原始对象不会触发视图更新
  const turn = turns.value[turns.value.length - 1]

  streaming.value = true
  void nextTick(() => {
    composer.value?.focus()
    thread.value?.lastElementChild?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })

  stopStream = askAgent(text, {
    onMeta: (meta) => {
      turn.meta = meta
    },
    onTool: (tool) => {
      // running → done 就地更新同一行，不追加新条目：
      // 追加的话一次查询会留下"正在搜索…"和"已找到 10 家"两条，
      // 用户读到的是一个已经过时的状态。
      turn.tool = tool
      if (tool.status !== 'running') {
        void nextTick(() => scrollTail())
      }
    },
    onCards: (cards) => {
      // 三种卡片必须分开存：`knowledge` 走知识库那条路，下发的是**来源**
      // （名称/出处/链接），`itinerary` 是一份整体行程（哪天去哪几个点），
      // 其余是高德 POI（地址/距离/电话）。字段几乎不重叠，混进同一个数组
      // 再在模板里判断，迟早会出现"把来源当酒店渲染"的空白卡。
      if (cards.kind === 'knowledge') {
        turn.sources = cards.items
      } else if (cards.kind === 'itinerary') {
        // 行程恒为一张卡。取第一项而不是渲染整个数组 ——
        // 让"一份行程"这件事在类型上就只有一个，前端不必处理"多份行程"。
        turn.itinerary = cards.items[0] ?? null
      } else {
        turn.cards = cards.items
      }
      turn.cardKind = cards.kind
      void nextTick(() => scrollTail())
    },
    onDelta: (piece) => {
      turn.answer += piece
      void nextTick(() => scrollTail())
    },
    onDone: (done) => {
      turn.elapsedMs = done.elapsed_ms
      turn.streaming = false
      finish()
    },
    onError: (message) => {
      turn.error = message
      turn.streaming = false
      finish()
    },
  })
}

function finish() {
  streaming.value = false
  stopStream = null
  // 一轮对话结束时落盘。**刻意不写成 `watch(turns, save, { deep: true })`** ——
  // 流式回答每来一个 token 都会改 `turn.answer`，那样等于"每吐一个字
  // 就把整段历史序列化一遍"，几十轮之后会明显卡。
  // 需要落盘的时刻是有限的几处：这里、卸载时、刷新时、清空时。
  saveAgentTurns(turns.value)
}

function stop() {
  stopStream?.()
  const last = turns.value[turns.value.length - 1]
  if (last?.streaming) {
    last.streaming = false
    last.error = last.error || '已停止生成'
  }
  finish()
}

/** 贴底滚动。只在用户本来就贴着底部时滚，避免打断他往上翻看历史 */
function scrollTail() {
  const el = thread.value
  if (!el) return
  const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 260
  if (nearBottom) el.scrollTop = el.scrollHeight
}

function onKeydown(e: KeyboardEvent) {
  // Enter 发送、Shift+Enter 换行。中文输入法组字过程中的 Enter
  // 不该被当成发送（isComposing），否则选词就发出一半
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
  }
}

function clearAll() {
  if (streaming.value) stop()
  turns.value = []
  // 必须**显式**清存储：上面那句 `stop()` 里的落盘会把刚清掉的又写回去，
  // 于是"清空对话 → 刷新"之后旧对话又回来了。
  clearAgentTurns()
}

/**
 * 整页刷新 / 关标签页时也要落一次盘。
 *
 * **`onBeforeUnmount` 在整页刷新时不会触发** —— 浏览器直接把页面丢掉，
 * Vue 没有机会跑卸载钩子。少了这一条，"回答还在生成时按 F5"就会丢掉那一轮
 * （而它恰恰是用户最想找回来的：他想看看刚才答到哪了）。
 */
function onPageHide() {
  saveAgentTurns(turns.value)
}

onMounted(() => {
  window.addEventListener('pagehide', onPageHide)
  // 有历史时**直接贴到底**：用户切回来想看的是最新那一轮。
  // 这里用硬跳而不是 `scrollTail()` —— 那个函数有"只在已贴底时才滚"的守卫，
  // 而刚挂载时 scrollTop 是 0，守卫会拦住它，用户看到的是最老的一轮。
  void nextTick(() => {
    const el = thread.value
    if (el) el.scrollTop = el.scrollHeight
  })
})

// 卸载时 `stop()` 会顺带落盘一次（它内部调 `finish()`），所以这里不用再写一次。
onBeforeUnmount(() => {
  window.removeEventListener('pagehide', onPageHide)
  stop()
})

// ---------------------------------------------------------- 当前行程（阶段二）

const session = useSessionStore()
const router = useRouter()

/** 当前行程与上下文。未登录或读取失败时为 null */
const trip = ref<TripContext | null>(null)
/** 正在写上下文（选酒店 / 取消）。避免连点两次发出两个请求 */
const tripBusy = ref(false)
/** 一次性提示（"已记住您住在…"）。几秒后自己消失，不占版面 */
const tripNote = ref('')

/** 已选住处。没选时为 null */
const selectedHotel = computed(() => trip.value?.hotel ?? null)

/** 预订状态的中文。没有上下文时返回空串，模板据此不渲染那个徽标 */
const bookingText = computed(() =>
  trip.value ? (HOTEL_BOOKING_LABEL[trip.value.hotel_booking_status] ?? '') : '',
)

function isChosen(card: AgentCard) {
  return selectedHotel.value?.poi_id === card.poi_id
}

let noteTimer: number | undefined
function say(text: string) {
  tripNote.value = text
  window.clearTimeout(noteTimer)
  noteTimer = window.setTimeout(() => {
    tripNote.value = ''
  }, 6000)
}

/**
 * 读当前行程。
 *
 * **未登录时一次都不调。** 这是本页最容易踩的坑：`/api/trips/**` 是受保护
 * 接口，未登录返回 4001，而 http.ts 对受保护路径的 4001 处理是
 * "清会话 + 跳登录页" —— 一个只是想问问路的游客会被弹走，
 * 整个助手页对他就不再可用。
 *
 * 所以这里先判登录态。没有记忆只是少一个能力，不该把页面本身拿走。
 */
async function loadTrip() {
  if (!session.isLoggedIn) {
    trip.value = null
    return
  }
  try {
    trip.value = await getCurrentTrip()
  } catch {
    // 读失败（后端没起来 / 令牌刚失效）就当没有上下文。
    // 不打断对话，也不弹错 —— 助手的主功能是问答，上下文是叠加项。
    trip.value = null
  }
}

onMounted(loadTrip)
// 登录 / 退出后重新取。用户在另一个标签页登录完切回来也会命中这一条
watch(() => session.isLoggedIn, loadTrip)

/**
 * 把这家酒店记为本次行程的住处。
 *
 * 只做一件事：写进 TripContext。**不做预订** —— 没有库存、没有支付、
 * 没有订单，也不调美团/携程的内部接口。"去预订"要到阶段五才有。
 *
 * 必须把**经纬度**一起存下去（不能只存名字）：后面那句
 * "这附近有什么好吃的"就是拿这个坐标去问高德的，只有名字是算不出"附近"的。
 */
async function chooseHotel(card: AgentCard) {
  if (!session.isLoggedIn) {
    void router.push({ path: '/login', query: { redirect: '/agent' } })
    return
  }
  if (card.lng == null || card.lat == null) {
    // 极少数 POI 高德不返回坐标。存下去只会让下一轮搜索的中心落空，
    // 所以在这里拦住并说清楚，而不是存一个没有坐标的"已选酒店"。
    say('这家酒店高德没有返回坐标，没法作为"附近"的中心，换一家试试。')
    return
  }
  if (tripBusy.value) return
  tripBusy.value = true
  try {
    trip.value = await selectTripHotel({
      poi_id: card.poi_id,
      name: card.name,
      address: card.address,
      longitude: card.lng,
      latitude: card.lat,
      source: card.source,
    })
    say(`已记住：您住在「${card.name}」。现在直接问"这附近有什么好吃的"就行。`)
  } catch (e) {
    say(e instanceof Error ? e.message : '保存失败，请稍后再试')
  } finally {
    tripBusy.value = false
  }
}

/** 取消已选住处。只清空酒店，行程本身还在 */
async function dropHotel() {
  if (tripBusy.value) return
  tripBusy.value = true
  try {
    trip.value = await clearTripHotel()
    say('已取消已选住处。')
  } catch (e) {
    say(e instanceof Error ? e.message : '取消失败，请稍后再试')
  } finally {
    tripBusy.value = false
  }
}

onBeforeUnmount(() => window.clearTimeout(noteTimer))

// -------------------------------------------------------------- 卡片渲染

/** 卡片按类选回落插画。高德的图挂了/被防盗链挡了就画插画，不留天窗 */
const SCENE_BY_KIND: Record<string, string> = {
  hotel: 'ancient',
  restaurant: 'ancient',
  attraction: 'qinling',
  transport: 'hantai',
}

function sceneOf(kind: string) {
  return SCENE_BY_KIND[kind] ?? 'qinling'
}

/**
 * 工具状态条右侧"条数"那一格的文案。**空串 = 不渲染**（模板的 `v-if` 挡掉）。
 *
 * 为什么不能只看 `count != null`：`get_route` 的 count 恒为 **0**
 * （路线没有"几条"这回事，距离与时长在正文里），只看 count 就会渲染成
 * **"返回 0 条"**。这个错**协议层完全看不出来** —— SSE 事件里
 * `{"name":"get_route","status":"done","count":0}` 每一项都合法，
 * 只有真浏览器里才看得见（`probe_route_ui.mjs`）。
 *
 * 单位表在 `types` 里（`AGENT_TOOL_COUNT_UNIT`），加工具时跟着一起加。
 * 这里按 `tool.name` 分流而不是 `t.cardKind`：`name` 在 tool 事件里就有，
 * 不必等 cards 事件到了才显示对，也就没有"先显示错再改对"的一帧。
 */
function countMeta(tool: AgentToolEvent): string {
  if (tool.count == null) return ''
  const unit = AGENT_TOOL_COUNT_UNIT[tool.name]
  if (unit === null) return ''
  return unit ? `共 ${tool.count} ${unit}` : `返回 ${tool.count} 条`
}

// ---------------------------------------------------------- 来源卡片（知识库分支）

/** 文档类型中文名。表在 types 里，与知识问答页共用一份 */
function docTypeLabel(s: AgentSourceCard): string {
  return QA_DOC_TYPE_LABEL[s.doc_type] ?? s.doc_type
}

/** 来源性质标签。后端没给这一格时退回中性文案 —— 不能默认显示成"原文可查" */
function sourceKindLabel(s: AgentSourceCard): string {
  return QA_SOURCE_KIND_LABEL[s.source_kind] ?? '来源'
}

/** 徽标配色：可核对（绿）/ 站点级（金）/ 数据包自有（中性） */
function sourceKindTone(s: AgentSourceCard): string {
  if (s.source_kind === 'detail') return 'ok'
  if (s.source_kind === 'site') return 'warn'
  return 'plain'
}

/** 距离。高德给的是米；超过 1 公里换成公里，避免出现"12340 米"这种读不动的数 */
function dist(m: number | null): string {
  if (m == null) return ''
  if (m < 1000) return `${m} 米`
  return `${(m / 1000).toFixed(1)} 公里`
}

/** 电话。高德可能返回 "固话;手机" 这种分号分隔的多条 */
function tels(raw: string): string[] {
  return (raw || '')
    .split(';')
    .map((s) => s.trim())
    .filter(Boolean)
}

/** 卡片主标题：优先完整地址，没有则区县 + 商圈 */
function subline(card: AgentCard): string {
  const parts = [card.district, card.business_area].filter(Boolean)
  return parts.join(' · ')
}

// ---------------------------------------------------------- 行程卡片（阶段三）

/**
 * 门票。**0 是免费、null 是未知，两者必须分开说** —— 与后端
 * `tools._price_text` 同一套口径。把 null 显示成"0 元"会让用户以为这个景区不要钱。
 */
function ticketText(value: number | string | null): string {
  if (value === null || value === '') return ''
  const n = Number(value)
  if (!Number.isFinite(n)) return `门票 ${value}`
  return n <= 0 ? '免费' : `¥${n}`
}

/** 建议时长：分钟 -> "3.5 小时" / "90 分钟"。用户读"210 分钟"要在心里除一遍 */
function minutesText(m: number): string {
  if (m <= 0) return ''
  if (m < 60) return `${m} 分钟`
  const hours = m / 60
  return Number.isInteger(hours) ? `${hours} 小时` : `${hours.toFixed(1)} 小时`
}

/** 级别配色：A 级景区用品牌色，乡村业态（"省级乡村旅游示范村"这类）用中性色 ——
 *  它们不是"低等级"，只是另一个体系，给成灰色会让用户觉得是次品 */
function levelClass(level: string): string {
  return /^[2-5]A$/.test(level) ? 'tag tag-brand' : 'tag'
}

/** 一天的副标题。合计时长要显示出来：它是"这天装不装得下"的唯一依据 */
function dayMeta(day: AgentItineraryDay): string {
  const stops = `${day.stops.length} 个点`
  const minutes = minutesText(day.minutes)
  return minutes ? `${stops} · 约 ${minutes}` : stops
}

const copied = ref('')
async function copyAddress(card: AgentCard) {
  const text = `${card.name} ${card.address || ''}`.trim()
  try {
    await navigator.clipboard.writeText(text)
    copied.value = card.poi_id
    window.setTimeout(() => {
      if (copied.value === card.poi_id) copied.value = ''
    }, 1600)
  } catch {
    // 剪贴板在非 https / 无权限时会失败。不弹错，把地址选中让用户自己复制
    copied.value = ''
  }
}
</script>

<template>
  <div class="agent">
    <!-- ============ 顶部：能力状态 + 说明 ============ -->
    <header class="agent__head">
      <div class="container agent__head-in">
        <div class="agent__head-l">
          <p class="eyebrow">汉中本地推荐</p>
          <h1 class="agent__title">AI 旅游助手</h1>
          <p class="agent__lead">
            问它「<b>汉中高铁站附近推荐酒店</b>」这类问题，它会去查真实地点再给出建议，
            地址、距离、电话都能核对。看到合适的可以直接打电话或导航过去。
          </p>
        </div>

        <div class="agent__caps">
          <!--
            ★ 只保留"出问题时"的一句话。原来这里还有三个徽标：
              「高德地图已接入 / 未配置」「本地知识库可用 / 不可用」和模型名（deepseek-chat）。
              那是运维与答辩要看的信息，不是游客要看的 —— 已全部移除。
          -->
          <span v-if="status.lv !== 'ok'" class="cap" :class="`cap--${status.lv}`">
            <i class="cap__dot" />{{ status.label }}
          </span>
        </div>
      </div>
    </header>

    <div class="container agent__body">
      <!--
        ============ 行程上下文条（阶段二）============
        这一行是"助手记得住"的可见证据：已选住处会一直显示在这里，
        而它同时也在服务端的 trip_context 里 —— 下一轮问"这附近"时，
        高德的搜索中心就是它，不需要用户再说一遍。

        未登录时它不是隐藏，而是明说"助手不会记住你的住处"并给登录入口：
        直接藏起来会让用户以为这个功能不存在。
      -->
      <div class="tripbar">
        <div class="tripbar__l">
          <span class="tripbar__k">本次行程</span>
          <template v-if="session.isLoggedIn && trip">
            <code class="tripbar__code">{{ trip.trip_code }}</code>
            <span class="tripbar__dest">{{ trip.destination }}</span>
          </template>
          <span v-else-if="!session.isLoggedIn" class="tripbar__mute">
            未登录 · 助手不会记住你的住处
          </span>
          <span v-else class="tripbar__mute">正在读取…</span>
        </div>

        <div class="tripbar__r">
          <RouterLink
            v-if="!session.isLoggedIn"
            class="btn btn-sm btn-line"
            :to="{ path: '/login', query: { redirect: '/agent' } }"
          >
            登录后启用
          </RouterLink>
          <template v-else-if="selectedHotel">
            <span class="tripbar__hotel">
              住处 <b>{{ selectedHotel.name }}</b>
              <span v-if="bookingText" class="tripbar__badge">{{ bookingText }}</span>
            </span>
            <button class="btn-text" type="button" :disabled="tripBusy" @click="dropHotel">
              取消住处
            </button>
          </template>
          <span v-else-if="trip" class="tripbar__mute">
            还没选住处 —— 在高德返回的酒店卡片上点「选择酒店」
          </span>
        </div>

        <p v-if="tripNote" class="tripbar__note">{{ tripNote }}</p>
      </div>

      <!-- ============ 空态：引导 + 示例问题 ============ -->
      <section v-if="!inSession" class="intro">
        <div class="intro__how">
          <!--
            ★ 原标题是「它和「知识问答」有什么不同」—— 那是拿我们自己的两个页面做比较，
              游客没逛过全站，不知道"知识问答"是什么。改成说它能帮你做什么。
              四条里的"查本地知识库 / 查高德地图 / 不是模型想出来的 / 模型只负责…"
              也全部改成游客语言。
          -->
          <h2 class="intro__h2">它能帮你做什么</h2>
          <ol class="intro__steps">
            <li>
              <b>找到真实的地方</b>
              <span>酒店、餐厅、景点都能查，名称、地址、距离、电话都可以核对，也能直接导航过去</span>
            </li>
            <li>
              <b>替你比一比</b>
              <span>同样问一句，它会告诉你哪家更近、哪个更适合，省得自己一个个搜</span>
            </li>
            <li>
              <b>答得清楚</b>
              <span>把查到的结果整理成一段能直接看懂的推荐，不用在几个页面之间来回翻</span>
            </li>
            <li>
              <b>记住你住哪儿</b>
              <span>
                在酒店卡片上点「选择酒店」，之后直接问"这附近有什么好吃的"就行 ——
                不用再报一次住哪（需登录）
              </span>
            </li>
          </ol>
        </div>

        <div v-if="samples.length" class="intro__samples">
          <h2 class="intro__h2">试试这样问</h2>
          <div v-for="g in samples" :key="g.title" class="sgroup">
            <p class="sgroup__t">{{ g.title }}</p>
            <div class="sgroup__list">
              <button
                v-for="q in g.items"
                :key="q"
                class="sbtn"
                type="button"
                @click="submit(q)"
              >
                {{ q }}
              </button>
            </div>
          </div>
        </div>
      </section>

      <!-- ============ 对话区 ============ -->
      <section v-else ref="thread" class="thread">
        <div class="thread__tools">
          <span class="thread__count">共 {{ turns.length }} 轮</span>
          <button class="btn-text thread__clear" type="button" @click="clearAll">清空对话</button>
        </div>

        <article v-for="t in turns" :key="t.id" class="turn">
          <!-- 用户 -->
          <div class="ask">
            <span class="ask__who">你</span>
            <p class="ask__text">{{ t.question }}</p>
          </div>

          <!-- 助手 -->
          <div class="ans">
            <div class="ans__head">
              <span class="ans__who">旅游助手</span>
              <span v-if="t.tool && t.tool.name !== 'none'" class="ans__tool">
                {{ AGENT_TOOL_LABEL[t.tool.name] ?? t.tool.name }}
              </span>
              <span v-if="t.elapsedMs" class="ans__ms">{{ (t.elapsedMs / 1000).toFixed(1) }}s</span>
            </div>

            <!-- 工具状态条：把"它真的调了工具"摆出来 -->
            <div
              v-if="t.tool"
              class="tstep"
              :class="`tstep--${t.tool.status}`"
            >
              <span class="tstep__icon" />
              <span class="tstep__label">{{ t.tool.label }}</span>
              <!--
                "条数"那一格：文案与"要不要显示"都由 `countMeta` 决定。
                行程的 count 是**天数**（写成"返回 2 条"会让用户以为只查到两个点），
                路线的 count 恒为 0 且没有"条数"含义（显示成"返回 0 条"是错的）
                —— 两种都写在 `AGENT_TOOL_COUNT_UNIT` 里，别在这里就地判断。
              -->
              <span v-if="t.tool.status === 'done' && countMeta(t.tool)" class="tstep__meta">
                {{ countMeta(t.tool) }}
              </span>
              <span v-else-if="t.tool.status === 'error'" class="tstep__meta tstep__meta--err">
                {{ t.tool.error || '调用失败' }}
              </span>
            </div>

            <!--
              行程卡片：一份按天的方案。
              与下面的地点卡片是两种东西 —— 它是一份**整体**（哪天去哪几个点），
              不是一个"可选择的点"，所以这里不逐张渲染、也没有任何按钮。
            -->
            <div v-if="t.itinerary" class="itin">
              <div class="cards__head">
                <span class="cards__t">{{ t.itinerary.title }}</span>
                <span class="cards__src">方案按你填的条件排出，非模型生成</span>
              </div>

              <!--
                数据包里的点不够时算法会少排。**如实显示**，不把 2 天冒充成 3 天 ——
                "宁可少排一天，也不重复推荐同一个点"这条规则要能看见。
              -->
              <p v-if="t.itinerary.days.length < t.itinerary.requested_days" class="itin__short">
                您要的是 {{ t.itinerary.requested_days }} 天，目前可排的游览点只够
                {{ t.itinerary.days.length }} 天。
              </p>

              <ol class="itin__days">
                <li v-for="d in t.itinerary.days" :key="d.day" class="iday">
                  <div class="iday__head">
                    <span class="iday__no">第 {{ d.day }} 天</span>
                    <span class="iday__district">{{ d.district }}</span>
                    <span class="iday__meta">{{ dayMeta(d) }}</span>
                  </div>
                  <ul class="iday__stops">
                    <li v-for="(s, i) in d.stops" :key="s.poi_id" class="istop">
                      <span class="istop__no">{{ i + 1 }}</span>
                      <div class="istop__main">
                        <div class="istop__top">
                          <h4 class="istop__name">{{ s.name }}</h4>
                          <span v-if="s.level" :class="levelClass(s.level)">{{ s.level }}</span>
                        </div>
                        <div class="istop__facts">
                          <span v-if="s.duration_min" class="istop__fact">
                            建议游览 {{ minutesText(s.duration_min) }}
                          </span>
                          <span v-if="ticketText(s.ticket_price)" class="istop__fact">
                            {{ ticketText(s.ticket_price) }}
                          </span>
                          <span v-if="s.open_hours" class="istop__fact">开放 {{ s.open_hours }}</span>
                        </div>
                        <p v-if="s.summary" class="istop__desc">{{ s.summary }}</p>
                        <div v-if="s.tags.length" class="istop__tags">
                          <span v-for="tag in s.tags" :key="tag" class="tag">{{ tag }}</span>
                        </div>
                      </div>
                    </li>
                  </ul>
                </li>
              </ol>

              <p v-for="(note, i) in t.itinerary.notes" :key="`itin-note-${i}`" class="itin__note">
                {{ note }}
              </p>

              <!--
                免责说明不是客套：门票与开放时间是数据包里的**静态整理值**，
                出行前可能已经变了。写出来就必须标注来源与时效性。
              -->
              <p class="cards__note">
                以上名称、级别、建议时长、门票与开放时间来自本站整理的静态信息，
                <b>不是实时信息</b>，出行前请以景区公告为准。本行程只排游览点，
                不含餐饮、住宿与交通方式；同一天的点按地理位置就近排列。
              </p>
            </div>

            <!-- 卡片：逐字来自高德，不经过模型 -->
            <div v-if="t.cards.length" class="cards">
              <div class="cards__head">
                <span class="cards__t">高德地图返回的 {{ t.cards.length }} 个地点</span>
                <span class="cards__src">数据来源：高德地图</span>
              </div>
              <ul class="cards__list">
                <li
                  v-for="c in t.cards"
                  :key="c.poi_id"
                  class="hcard"
                  :class="{ 'hcard--chosen': isChosen(c) }"
                >
                  <div class="hcard__pic">
                    <PoiImage :src="c.photo" :scene="sceneOf(t.cardKind)" ratio="4 / 3" :alt="c.name" />
                  </div>
                  <div class="hcard__main">
                    <div class="hcard__top">
                      <h3 class="hcard__name">{{ c.name }}</h3>
                      <span v-if="c.category" class="tag tag-brand hcard__cat">{{ c.category }}</span>
                      <span v-if="c.rating" class="hcard__rate">
                        <b>{{ c.rating }}</b> 分
                      </span>
                    </div>

                    <p class="hcard__addr">
                      <span v-if="subline(c)" class="hcard__sub">{{ subline(c) }}</span>
                      {{ c.address || '（高德未提供详细地址）' }}
                    </p>

                    <div class="hcard__facts">
                      <span v-if="c.distance_m != null" class="hcard__fact">
                        距搜索中心 <b>{{ dist(c.distance_m) }}</b>
                      </span>
                      <span v-if="c.cost" class="hcard__fact">人均 ¥{{ c.cost }}</span>
                      <span v-if="c.tag" class="hcard__fact">{{ c.tag }}</span>
                    </div>

                    <div v-if="tels(c.tel).length" class="hcard__tels">
                      <a
                        v-for="tel in tels(c.tel)"
                        :key="tel"
                        class="hcard__tel"
                        :href="`tel:${tel}`"
                      >
                        {{ tel }}
                      </a>
                    </div>

                    <div class="hcard__acts">
                      <button class="btn btn-sm btn-ghost" type="button" @click="copyAddress(c)">
                        {{ copied === c.poi_id ? '已复制' : '复制名称与地址' }}
                      </button>

                      <!--
                        「选择酒店」只出现在**酒店**卡片上。
                        类别取自卡片自己的 kind（后端按 typecode 逐张标注），
                        不是这一批结果的整体类别 —— 混合结果里第一条是餐厅时，
                        整批都会被判成非酒店，那张酒店卡片就没有按钮了。

                        点它只做一件事：把这家的 poi_id + 名称 + 地址 + **经纬度**
                        写进本次行程的上下文。**不预订**，本页也不提供预订。
                        未登录时按钮改成「登录后选择」，点了去登录页而不是直接调接口
                        （受保护接口的 4001 会触发全局跳登录，那对游客是误伤）。
                      -->
                      <template v-if="c.kind === 'hotel'">
                        <button
                          v-if="isChosen(c)"
                          class="btn btn-sm btn-primary hcard__chosen"
                          type="button"
                          disabled
                        >
                          ✓ 已选为住处
                        </button>
                        <button
                          v-else
                          class="btn btn-sm btn-line"
                          type="button"
                          :disabled="tripBusy"
                          @click="chooseHotel(c)"
                        >
                          {{ session.isLoggedIn ? '选择酒店' : '登录后选择' }}
                        </button>
                      </template>
                      <!--
                        TODO（阶段五）：这里加「去预订」，跳到第三方平台，
                        同时把 hotel_booking_status 置为 external_pending。
                        现在不放：它做不了真事，放上去就是假的。
                      -->
                    </div>
                  </div>
                </li>
              </ul>
              <p class="cards__note">
                以上为高德地图返回的真实地点信息。本页只做推荐，不提供预订；
                如需预订请到第三方平台搜索该地点名称。
              </p>
            </div>

            <!--
              来源：走知识库那条路时下发。
              与上面的地点卡片是两种东西，所以分开渲染 ——
              写"检索命中"而不是"回答依据"：取的是 top-5 候选，
              模型实际用到的往往只有前一两张，说成"依据"是过度声称。
            -->
            <div v-if="t.sources.length" class="cards">
              <div class="cards__head">
                <span class="cards__t">检索命中 {{ t.sources.length }} 条</span>
                <span class="cards__src">名称与链接取自知识库元数据，非模型生成</span>
              </div>
              <ul class="cards__list cards__list--src">
                <li v-for="(s, i) in t.sources" :key="i" class="scard">
                  <div class="scard__top">
                    <span class="scard__kind">{{ docTypeLabel(s) }}</span>
                    <span class="scard__title">{{ s.title }}</span>
                  </div>

                  <p v-if="s.snippet" class="scard__intro">{{ s.snippet }}</p>

                  <div class="scard__foot">
                    <span class="scard__from">{{ s.source_name || '未标注来源' }}</span>
                    <span class="scard__badge" :class="`scard__badge--${sourceKindTone(s)}`">
                      {{ sourceKindLabel(s) }}
                    </span>
                    <!-- 两个链接都开新标签页：本页对话只存在内存里，
                         跳走再回来就清空了，而核对来源恰恰需要来回看 -->
                    <span class="scard__links">
                      <RouterLink
                        v-if="s.poi_id"
                        class="scard__link"
                        :to="`/poi/${s.poi_id}`"
                        target="_blank"
                        rel="noopener"
                      >
                        站内详情 →
                      </RouterLink>
                      <a
                        v-if="s.source_url"
                        class="scard__link"
                        :href="s.source_url"
                        target="_blank"
                        rel="noopener noreferrer"
                      >
                        查看原文 ↗
                      </a>
                    </span>
                  </div>
                </li>
              </ul>
            </div>

            <!-- 回答正文 -->
            <div v-if="t.answer" class="ans__text">
              <!--
                `inlineText` 与知识问答页共用一份（见 utils/format.ts）：
                先转义 HTML 再把 `**加粗**` 换成 strong。**这是 v-html，
                所以顺序不能反** —— 先插标签再转义就是一个注入点。
              -->
              <p
                v-for="(para, i) in t.answer.split('\n').filter(Boolean)"
                :key="i"
                v-html="inlineText(para)"
              />
              <span v-if="t.streaming" class="ans__caret" />
            </div>
            <div
              v-else-if="t.streaming && !t.cards.length && !t.sources.length && !t.itinerary"
              class="ans__waiting"
            >
              <span class="dot" /><span class="dot" /><span class="dot" />
            </div>

            <p v-if="t.error" class="ans__err">{{ t.error }}</p>
          </div>
        </article>
      </section>

      <!-- ============ 输入区 ============ -->
      <div class="composer" :class="{ 'composer--solo': !inSession }">
        <div class="composer__box">
          <textarea
            ref="composer"
            v-model="question"
            class="composer__ta"
            rows="1"
            placeholder="问点什么，例如：汉中高铁站附近推荐酒店"
            @keydown="onKeydown"
          />
          <button v-if="streaming" class="btn btn-line composer__send" type="button" @click="stop">
            停止
          </button>
          <button
            v-else
            class="btn btn-primary composer__send"
            type="button"
            :disabled="!question.trim()"
            @click="submit()"
          >
            发送
          </button>
        </div>
        <p class="composer__hint">
          Enter 发送 · Shift + Enter 换行
          <template v-if="!amapReady">
            · <b>高德未配置</b>，只能回答知识库里的问题
          </template>
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.agent {
  min-height: 100vh;
  background: var(--paper);
}

/* ---------------- 顶部 ---------------- */
.agent__head {
  background: var(--brand-900);
  padding: var(--sp-8) 0 var(--sp-6);
}
.agent__head-in {
  display: flex;
  gap: var(--sp-7);
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
}
.agent__head-l {
  max-width: 640px;
}
.agent__title {
  margin-top: var(--sp-4);
  color: #fff;
  font-size: var(--fs-h1);
}
.agent__lead {
  margin-top: var(--sp-4);
  font-size: var(--fs-body);
  line-height: 1.85;
  color: rgba(233, 240, 236, 0.82);
}
.agent__lead b {
  color: var(--gold-300);
  font-weight: 600;
}
.agent__caps {
  display: flex;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding-bottom: 4px;
}
.cap {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 13px;
  font-size: var(--fs-cap);
  font-weight: 600;
  border-radius: var(--r-pill);
  border: 1px solid transparent;
}
.cap__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}
.cap--ok {
  color: #cdeadd;
  background: rgba(42, 111, 91, 0.35);
  border-color: rgba(113, 169, 150, 0.5);
}
.cap--on {
  color: #f0e2c4;
  background: rgba(192, 154, 78, 0.22);
  border-color: rgba(226, 202, 145, 0.45);
}
.cap--off {
  color: #f6dcd5;
  background: rgba(168, 64, 43, 0.3);
  border-color: rgba(231, 156, 136, 0.5);
}
.cap--wait,
.cap--mute {
  color: rgba(255, 255, 255, 0.72);
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.2);
}

/* ---------------- 行程上下文条（阶段二） ---------------- */
.tripbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
  margin: var(--sp-5) 0 0;
  padding: 11px var(--sp-4);
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
  border-radius: var(--r-md);
  font-size: var(--fs-xs);
}
.tripbar__l,
.tripbar__r {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.tripbar__k {
  color: var(--ink-500);
}
.tripbar__code {
  font-family: var(--font-num);
  font-size: var(--fs-xs);
  color: var(--brand-700);
  background: #fff;
  border: 1px solid var(--brand-100);
  border-radius: var(--r-sm);
  padding: 1px 7px;
}
.tripbar__dest {
  font-weight: 600;
  color: var(--ink-900);
}
.tripbar__mute {
  color: var(--ink-500);
}
.tripbar__hotel {
  color: var(--ink-600);
}
.tripbar__hotel b {
  color: var(--brand-700);
  font-weight: 600;
}
.tripbar__badge {
  margin-left: 6px;
  padding: 1px 7px;
  font-size: 11px;
  color: var(--gold-600);
  background: var(--gold-50);
  border-radius: var(--r-pill);
}
/* 提示独占一行：它是"刚刚发生了什么"的反馈，与左侧的稳定状态不是一类信息 */
.tripbar__note {
  flex-basis: 100%;
  color: var(--brand-700);
}

/* ---------------- 主体 ---------------- */
.agent__body {
  padding: var(--sp-7) 0 var(--sp-9);
  display: flex;
  flex-direction: column;
  gap: var(--sp-6);
  min-height: 60vh;
}

/* ---------------- 空态引导 ---------------- */
.intro {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--sp-7);
}
@media (max-width: 900px) {
  .intro {
    grid-template-columns: minmax(0, 1fr);
  }
}
.intro__h2 {
  font-size: var(--fs-h3);
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--line);
}
.intro__steps {
  margin-top: var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
  counter-reset: s;
}
.intro__steps li {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-left: 38px;
  position: relative;
}
.intro__steps li::before {
  counter-increment: s;
  content: counter(s);
  position: absolute;
  left: 0;
  top: 1px;
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  font-size: var(--fs-cap);
  font-weight: 700;
  color: var(--brand-700);
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
  border-radius: 50%;
}
.intro__steps b {
  font-size: var(--fs-body);
  color: var(--ink-900);
}
.intro__steps span {
  font-size: var(--fs-sm);
  line-height: 1.75;
  color: var(--ink-500);
}

.intro__samples {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
}
.sgroup__t {
  font-size: var(--fs-cap);
  letter-spacing: 0.06em;
  color: var(--warm-500);
  margin-bottom: var(--sp-3);
}
.sgroup__list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
}
.sbtn {
  padding: 9px 15px;
  font-size: var(--fs-sm);
  color: var(--ink-700);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  cursor: pointer;
  transition: border-color var(--dur-1) var(--ease), color var(--dur-1) var(--ease),
    box-shadow var(--dur-1) var(--ease);
}
.sbtn:hover {
  color: var(--brand-600);
  border-color: var(--brand-300);
  box-shadow: var(--sh-1);
}

/* ---------------- 对话 ---------------- */
.thread {
  display: flex;
  flex-direction: column;
  gap: var(--sp-6);
  max-height: none;
}
.thread__tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--line-soft);
}
.thread__count {
  font-size: var(--fs-cap);
  letter-spacing: 0.06em;
  color: var(--warm-500);
}

.turn {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

/* 用户气泡：右对齐，窄一点，视觉上"轻" */
.ask {
  display: flex;
  gap: var(--sp-3);
  align-items: flex-start;
  justify-content: flex-end;
}
.ask__who {
  order: 2;
  flex: none;
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  font-size: var(--fs-cap);
  font-weight: 600;
  color: var(--warm-500);
  background: var(--paper-3);
  border-radius: 50%;
}
.ask__text {
  max-width: 66%;
  padding: 11px 16px;
  font-size: var(--fs-body);
  line-height: 1.7;
  color: var(--ink-900);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-lg) var(--r-sm) var(--r-lg) var(--r-lg);
}

/* 助手区：左对齐，占满，承载工具条与卡片 */
.ans {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.ans__head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.ans__who {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--brand-700);
}
.ans__tool {
  padding: 2px 9px;
  font-size: 11px;
  font-weight: 600;
  color: var(--tech-600);
  background: var(--tech-50);
  border: 1px solid var(--tech-300);
  border-radius: var(--r-pill);
}
.ans__ms {
  font-size: var(--fs-cap);
  color: var(--warm-400);
  font-family: var(--font-num);
}

/* 工具状态条 */
.tstep {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 9px 14px;
  font-size: var(--fs-sm);
  background: var(--paper-2);
  border: 1px solid var(--line-soft);
  border-left: 3px solid var(--warm-400);
  border-radius: var(--r-md);
}
.tstep__icon {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--warm-400);
}
.tstep--running {
  border-left-color: var(--tech-500);
}
.tstep--running .tstep__icon {
  background: var(--tech-500);
  animation: pulse 1.1s var(--ease) infinite;
}
.tstep--done {
  border-left-color: var(--brand-500);
}
.tstep--done .tstep__icon {
  background: var(--brand-500);
}
.tstep--error {
  border-left-color: var(--danger);
  background: var(--danger-50);
}
.tstep--error .tstep__icon {
  background: var(--danger);
}
.tstep__label {
  color: var(--ink-700);
}
.tstep__meta {
  margin-left: auto;
  font-size: var(--fs-cap);
  color: var(--warm-500);
  font-family: var(--font-num);
}
.tstep__meta--err {
  color: var(--danger);
  font-family: inherit;
}
@keyframes pulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.35;
    transform: scale(0.75);
  }
}

/* 卡片 */
.cards {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.cards__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.cards__t {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-700);
}
.cards__src {
  font-size: var(--fs-cap);
  color: var(--warm-400);
}
.cards__list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--sp-4);
}
.cards__note {
  font-size: var(--fs-cap);
  line-height: 1.7;
  color: var(--warm-500);
  padding-top: var(--sp-2);
  border-top: 1px dashed var(--line);
}

/* 行程卡片：一份整体方案，所以是**一条时间轴**而不是网格 ——
   网格会让人以为这些点可以任意挑，而行程的关键恰恰是"哪天去哪几个"。 */
.itin {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.itin__short {
  font-size: var(--fs-cap);
  line-height: 1.7;
  color: var(--warn);
  background: var(--warn-50);
  border-radius: var(--r-sm);
  padding: var(--sp-2) var(--sp-3);
}
.itin__days {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.iday {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: #fff;
  overflow: hidden;
}
.iday__head {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  flex-wrap: wrap;
  padding: var(--sp-3) var(--sp-4);
  background: var(--paper-3);
  border-bottom: 1px solid var(--line);
}
.iday__no {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--brand-700);
}
.iday__district {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-700);
}
.iday__meta {
  margin-left: auto;
  font-size: var(--fs-cap);
  color: var(--warm-400);
}
.iday__stops {
  display: flex;
  flex-direction: column;
}
/* 站点之间用虚线分隔，不各做一个卡片 —— 同一天的点是"一条线上的几站" */
.istop {
  display: flex;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
}
.istop + .istop {
  border-top: 1px dashed var(--line);
}
.istop__no {
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--brand-50);
  color: var(--brand-700);
  font-size: var(--fs-cap);
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
}
.istop__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.istop__top {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.istop__name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-700);
}
.istop__facts {
  display: flex;
  gap: var(--sp-3);
  flex-wrap: wrap;
  font-size: var(--fs-cap);
  color: var(--ink-600);
}
.istop__fact {
  position: relative;
}
.istop__fact + .istop__fact::before {
  content: '·';
  position: absolute;
  left: -9px;
  color: var(--warm-400);
}
.istop__desc {
  font-size: var(--fs-cap);
  line-height: 1.75;
  color: var(--warm-500);
  /* 限行宽：卡片有 1240px 宽，不限的话简介一行能排到 90 多个字，读不动。
     46em 与知识问答页的正文同一个量度（见 Assistant.vue）。 */
  max-width: 46em;
}
.istop__tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.itin__note {
  font-size: var(--fs-cap);
  line-height: 1.7;
  color: var(--warm-500);
  padding-left: var(--sp-3);
  border-left: 2px solid var(--line);
}

/* 来源卡片：比地点卡片轻（它是"依据"，不是"推荐"），一列排下来便于逐条核对 */
.cards__list--src {
  grid-template-columns: minmax(0, 1fr);
  gap: var(--sp-2);
}
.scard {
  padding: var(--sp-3) var(--sp-4);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-left: 3px solid var(--brand-100);
  border-radius: var(--r-md);
}
.scard__top {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
}
.scard__kind {
  flex: none;
  padding: 1px 8px;
  font-size: 11px;
  font-weight: 600;
  color: var(--brand-700);
  background: var(--brand-50);
  border-radius: var(--r-sm);
}
.scard__title {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-900);
  line-height: 1.5;
}
.scard__intro {
  margin-top: 6px;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--ink-500);
}
.scard__foot {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-3);
  margin-top: var(--sp-3);
  padding-top: var(--sp-2);
  border-top: 1px solid var(--line-soft);
}
.scard__from {
  font-size: var(--fs-xs);
  color: var(--warm-500);
}
.scard__badge {
  padding: 1px 8px;
  font-size: 11px;
  font-weight: 600;
  border-radius: var(--r-pill);
}
.scard__badge--ok {
  color: var(--ok);
  background: var(--ok-50);
}
.scard__badge--warn {
  color: var(--warn);
  background: var(--warn-50);
}
.scard__badge--plain {
  color: var(--warm-500);
  background: var(--paper-2);
}
.scard__links {
  margin-left: auto;
  display: flex;
  gap: var(--sp-4);
}
.scard__link {
  font-size: var(--fs-xs);
  color: var(--brand-600);
  text-decoration: underline;
  text-decoration-color: var(--brand-100);
  text-underline-offset: 3px;
}
.scard__link:hover {
  text-decoration-color: var(--brand-400);
}

.hcard {
  display: flex;
  gap: var(--sp-4);
  padding: var(--sp-4);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  transition: box-shadow var(--dur-2) var(--ease), border-color var(--dur-2) var(--ease);
}
.hcard:hover {
  border-color: var(--brand-300);
  box-shadow: var(--sh-2);
}
/* 已选为住处的卡片。用左边框 + 底色，而不是只靠按钮文案 ——
   用户往上翻历史时要能一眼看出"我选的是哪一家" */
.hcard--chosen {
  border-color: var(--brand-300);
  background: var(--brand-50);
  box-shadow: inset 3px 0 0 var(--brand-600);
}
.hcard__pic {
  flex: none;
  width: 108px;
  border-radius: var(--r-md);
  overflow: hidden;
}
.hcard__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.hcard__top {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.hcard__name {
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--ink-900);
  line-height: 1.4;
}
.hcard__cat {
  font-size: 11px;
}
.hcard__rate {
  margin-left: auto;
  font-size: var(--fs-cap);
  color: var(--gold-600);
  font-family: var(--font-num);
  white-space: nowrap;
}
.hcard__rate b {
  font-size: var(--fs-sm);
}
.hcard__addr {
  font-size: var(--fs-xs);
  line-height: 1.65;
  color: var(--ink-500);
}
.hcard__sub {
  display: inline-block;
  margin-right: 6px;
  padding: 1px 7px;
  font-size: 11px;
  color: var(--brand-700);
  background: var(--brand-50);
  border-radius: var(--r-sm);
}
.hcard__facts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--ink-600);
}
.hcard__fact b {
  color: var(--brand-600);
  font-family: var(--font-num);
}
.hcard__tels {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
}
.hcard__tel {
  font-size: var(--fs-xs);
  color: var(--tech-600);
  font-family: var(--font-num);
  text-decoration: underline;
  text-decoration-color: var(--tech-300);
  text-underline-offset: 3px;
}
.hcard__acts {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin-top: auto;
  padding-top: 4px;
}
/* 已选中：保持主色但降低不透明度，让"这是状态"与"这是按钮"区分开 */
.hcard__chosen {
  opacity: 1;
  cursor: default;
}

/* 回答正文 */
.ans__text {
  font-size: var(--fs-body);
  line-height: 1.9;
  color: var(--ink-700);
  max-width: 78ch;
}
.ans__text p + p {
  margin-top: var(--sp-3);
}
.ans__caret {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--brand-500);
  animation: blink 0.9s steps(2) infinite;
}
@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
.ans__waiting {
  display: flex;
  gap: 5px;
  padding: 6px 0;
}
.ans__waiting .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-300);
  animation: pulse 1.1s var(--ease) infinite;
}
.ans__waiting .dot:nth-child(2) {
  animation-delay: 0.15s;
}
.ans__waiting .dot:nth-child(3) {
  animation-delay: 0.3s;
}
.ans__err {
  padding: 10px 14px;
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-50);
  border: 1px solid rgba(168, 64, 43, 0.2);
  border-radius: var(--r-md);
}

/* ---------------- 输入区 ---------------- */
.composer {
  position: sticky;
  bottom: 0;
  padding-top: var(--sp-4);
  background: linear-gradient(to bottom, transparent, var(--paper) 26%);
}
.composer--solo {
  padding-top: var(--sp-6);
  border-top: 1px solid var(--line-soft);
  position: static;
  background: none;
}
.composer__box {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-3);
  padding: var(--sp-3);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-2);
  transition: border-color var(--dur-1) var(--ease);
}
.composer__box:focus-within {
  border-color: var(--brand-400);
}
.composer__ta {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  font: inherit;
  font-size: var(--fs-body);
  line-height: 1.7;
  color: var(--ink-900);
  background: transparent;
  padding: 8px 6px;
  max-height: 168px;
  min-height: 40px;
}
.composer__ta::placeholder {
  color: var(--warm-400);
}
.composer__send {
  flex: none;
}
.composer__hint {
  margin-top: var(--sp-2);
  font-size: var(--fs-cap);
  color: var(--warm-400);
}
.composer__hint b {
  color: var(--warn);
}
</style>
