<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { askAgent, getAiHealth } from '@/api/ai'
import PoiImage from '@/components/PoiImage.vue'
import { useAsync } from '@/composables/useAsync'
import {
  AGENT_TOOL_LABEL,
  QA_DOC_TYPE_LABEL,
  QA_SOURCE_KIND_LABEL,
  type AgentCard,
  type AgentSourceCard,
  type AgentTurn,
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
 * 本页是 M4 的**第一阶段**（高德 + 附近搜索 + 酒店卡片）。
 * 「选择酒店」（把酒店存进旅行上下文）与「去预订」（跳转第三方）
 * 分别在阶段二与阶段五，代码里对应位置留了 TODO 锚点，
 * 见 docs/M4-第一阶段设计分析.md。
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

/** 顶部状态徽标。三档对应三种真实运行形态，不显示"可能可以"这种模糊状态 */
const status = computed(() => {
  if (healthError.value) return { lv: 'off', label: 'AI 服务不可达' }
  if (!health.value) return { lv: 'wait', label: '正在连接…' }
  if (!kbReady.value) return { lv: 'off', label: '知识库未就绪' }
  return { lv: 'ok', label: '已就绪' }
})

// ------------------------------------------------------------------ 对话

const turns = ref<AgentTurn[]>([])
const question = ref('')
const streaming = ref(false)
const composer = ref<HTMLTextAreaElement | null>(null)
const thread = ref<HTMLElement | null>(null)
let stopStream: (() => void) | null = null
let seq = 0

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
      // 两种卡片必须分开存：`knowledge` 走知识库那条路，下发的是**来源**
      // （名称/出处/链接），其余是高德 POI（地址/距离/电话）。字段几乎不重叠，
      // 混进同一个数组再在模板里判断，迟早会出现"把来源当酒店渲染"的空白卡。
      if (cards.kind === 'knowledge') {
        turn.sources = cards.items
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
}

onBeforeUnmount(() => stop())

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
          <p class="eyebrow">M4 · 工具调度</p>
          <h1 class="agent__title">AI 旅游助手</h1>
          <p class="agent__lead">
            问它「<b>汉中高铁站附近推荐酒店</b>」这类问题，它会先去高德地图查真实地点，
            再组织成一段推荐。推荐来自高德，<b>本页只做推荐、不代订</b>。
          </p>
        </div>

        <div class="agent__caps">
          <span class="cap" :class="`cap--${status.lv}`">
            <i class="cap__dot" />{{ status.label }}
          </span>
          <span class="cap" :class="amapReady ? 'cap--on' : 'cap--mute'">
            高德地图{{ amapReady ? '已接入' : '未配置' }}
          </span>
          <span class="cap" :class="kbReady ? 'cap--on' : 'cap--mute'">
            本地知识库{{ kbReady ? '可用' : '不可用' }}
          </span>
          <span v-if="health?.model" class="cap cap--mute">{{ health.model }}</span>
        </div>
      </div>
    </header>

    <div class="container agent__body">
      <!-- ============ 空态：引导 + 示例问题 ============ -->
      <section v-if="!inSession" class="intro">
        <div class="intro__how">
          <h2 class="intro__h2">它和「知识问答」有什么不同</h2>
          <ol class="intro__steps">
            <li>
              <b>先判断该查哪儿</b>
              <span>是问汉中的公开知识（查本地知识库），还是问"附近有什么"（查高德地图）</span>
            </li>
            <li>
              <b>再真的去查</b>
              <span>酒店、餐厅、景点的名称、地址、距离、电话都来自高德，不是模型想出来的</span>
            </li>
            <li>
              <b>最后组织成人话</b>
              <span>模型只负责把查到的结果说清楚，并指出哪家更近、更适合</span>
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
              <span v-if="t.tool.status === 'done' && t.tool.count != null" class="tstep__meta">
                返回 {{ t.tool.count }} 条
              </span>
              <span v-else-if="t.tool.status === 'error'" class="tstep__meta tstep__meta--err">
                {{ t.tool.error || '调用失败' }}
              </span>
            </div>

            <!-- 卡片：逐字来自高德，不经过模型 -->
            <div v-if="t.cards.length" class="cards">
              <div class="cards__head">
                <span class="cards__t">高德地图返回的 {{ t.cards.length }} 个地点</span>
                <span class="cards__src">数据来源：高德地图</span>
              </div>
              <ul class="cards__list">
                <li v-for="c in t.cards" :key="c.poi_id" class="hcard">
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
                        TODO（阶段二）：这里放「选择酒店」，把该 POI 的
                        {poi_id, name, address, lng, lat, source} 写进 TripContext
                        的 selected_hotel。**必须存经纬度**，只存名字的话
                        "我住这儿，附近有什么好吃的" 就无从算起。
                        TODO（阶段五）：这里放「去预订」，跳到第三方平台，
                        同时把 hotel_booking_status 置为 external_pending。
                        现在不放这两个按钮：它们做不了真事，放上去就是假的。
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
              <p v-for="(para, i) in t.answer.split('\n').filter(Boolean)" :key="i">{{ para }}</p>
              <span v-if="t.streaming" class="ans__caret" />
            </div>
            <div v-else-if="t.streaming && !t.cards.length && !t.sources.length" class="ans__waiting">
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
  margin-top: auto;
  padding-top: 4px;
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
