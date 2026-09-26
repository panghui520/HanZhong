<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ask, getAiHealth, getSuggestions } from '@/api/ai'
import { getCityPack } from '@/api/citypack'
import SceneArt from '@/components/SceneArt.vue'
import { useAsync } from '@/composables/useAsync'
import { useReveal } from '@/composables/useReveal'
import {
  QA_MODE_LABEL,
  QA_ROUTE_HINT,
  QA_ROUTE_LABEL,
  QA_SOURCE_KIND_LABEL,
  type AiHealth,
  type Experience,
  type Product,
  type QaMode,
  type QaRoute,
  type QaSource,
  type QaTurn,
} from '@/types'

/**
 * M3 文旅知识问答。
 *
 * 这个页面刻意不叫"AI 助手"：项目红线之一是"核心不是聊天机器人"。
 * 它回答的是**汉中这座城市的公开事实**——地理、气候、历史、生态、物产，
 * 以及数据包里每个资源点、体验、产品的可核对信息，涉及汉中的回答都带出处。
 *
 * **架构上：模型负责说话，知识库负责依据。** 与汉中有关的问题先检索知识库，
 * 把命中的切片作为资料交给模型综合；与汉中无关的问题（"你是 AI 吗"）直接
 * 交给模型，不检索也不署名来源。答不上来时明说"知识库缺少依据"，
 * 而不是让模型即兴发挥本地事实。
 *
 * 页面结构上刻意做成"欢迎区 → 对话区"两段：
 * 空态时是一个有引导的欢迎区（能做的事、覆盖什么、推荐问题、可推的内容），
 * 一旦开始提问就收成流式对话。这样它看起来是一个"知识服务入口"，
 * 而不是一个打开的聊天窗口。
 */

// ---------------------------------------------------------------- 知识库状态

const health = ref<AiHealth | null>(null)
const healthError = ref('')
const suggestions = ref<string[]>([])

const modeText = computed(() => {
  const h = health.value
  if (!h) return ''
  if (h.demo_mode) return '离线演示（预生成答案）'
  return h.llm_configured ? `大模型生成 · ${h.model ?? ''}` : '原文摘录（未配置大模型）'
})

/** AI 状态的档位：决定徽标的颜色与文案。三档对应三种真实运行形态 */
const aiStatus = computed(() => {
  if (healthError.value) return { lv: 'off', label: '知识库不可达', hint: healthError.value }
  const h = health.value
  if (!h) return { lv: 'wait', label: '正在连接知识库', hint: '读取切片与词表信息…' }
  if (h.stale) return { lv: 'warn', label: '语料已变化', hint: h.stale_hint || '建议重建知识库' }
  if (h.demo_mode) return { lv: 'demo', label: '离线演示模式', hint: '按预生成答案回放，无需联网' }
  if (h.llm_configured) return { lv: 'ok', label: '在线生成', hint: `大模型 ${h.model ?? ''} · 词表 ${h.lexical.terms.toLocaleString()} 词` }
  return { lv: 'extract', label: '原文摘录', hint: '未配置大模型，直接摘录知识库原文' }
})

const scopeText = computed(() => {
  // 用 by_type_docs（文档数）而不是 by_type（切片数）：
  // 这里的单位是"篇/个/项/款"，是**条数**口径。长文档会切成多片，
  // 语料扩到 151 片后 city_doc 有 79 片却只有 20 篇，
  // 用切片数会显示成"79 篇城市知识"。
  const by = health.value?.by_type_docs ?? {}
  const parts = [
    ['poi', '个资源点'],
    ['experience', '项乡村体验'],
    ['product', '款乡村产品'],
    ['city_doc', '篇城市知识'],
  ] as const
  return parts
    .map(([key, unit]) => ({ value: by[key] ?? 0, unit }))
    .filter((x) => x.value > 0)
})

/** 侧栏用不到 0 值的项，但欢迎区要展示完整的覆盖数字 */
const scopeAll = computed(() => {
  const by = health.value?.by_type_docs ?? {}
  return [
    { key: 'city_doc', label: '城市知识', unit: '篇', value: by.city_doc ?? 0 },
    { key: 'poi', label: '统一资源', unit: '处', value: by.poi ?? 0 },
    { key: 'experience', label: '乡村体验', unit: '项', value: by.experience ?? 0 },
    { key: 'product', label: '乡村产品', unit: '款', value: by.product ?? 0 },
  ]
})

// ------------------------------------------------- 推荐内容（来自数据包，非写死）

const { data: pack, loading: packLoading } = useAsync(getCityPack)
const root = ref<HTMLElement | null>(null)

/**
 * 欢迎区展示的"可问到的内容"：从真实数据包里挑，而不是编几条样例。
 *
 * 挑选规则：各取一条乡村体验与一款为其挂靠的农产品 —— 这两者天然成对
 * （产品的 `experience_id` 指向体验），正好演示"体验锚点 → 离境复购"
 * 这条主张，也解释了"为什么知识库里会有产品信息"。
 */
const showcase = computed(() => {
  const exps = pack.value?.experiences ?? []
  const prods = pack.value?.products ?? []
  const pois = pack.value?.pois ?? []

  const exp: Experience | null = exps[0] ?? null
  const prod: Product | null = exp ? (prods.find((p) => p.experience_id === exp.id) ?? prods[0] ?? null) : (prods[0] ?? null)
  return { exp, prod, poiCount: pois.length }
})

// ------------------------------------------------------------------ 问答

const turns = ref<QaTurn[]>([])
const question = ref('')
const streaming = ref(false)
const composer = ref<HTMLTextAreaElement | null>(null)
const thread = ref<HTMLElement | null>(null)
let stopStream: (() => void) | null = null
let seq = 0

/** 是否已进入对话态 —— 决定显示欢迎区还是对话区 */
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

  stopStream = ask(text, {
    onMeta: (meta) => {
      turn.meta = meta
    },
    onDelta: (piece) => {
      turn.answer += piece
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

function onKeydown(e: KeyboardEvent) {
  // Enter 发送、Shift+Enter 换行。中文输入法组合期间的 Enter 不能当发送
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
  }
}

/** 回到欢迎区重新开始。会话是纯前端的，不通知后端 —— 服务端本就无状态 */
function reset() {
  stop()
  turns.value = []
  seq = 0
  void nextTick(() => composer.value?.focus())
}

/**
 * 极简内联格式：先转义 HTML，再把 **加粗** 换成 strong。
 * 不是 Markdown 渲染器——只处理模型实际会用的这一种标记。
 * 先转义再插标签，所以模型输出的尖括号不会变成标签。
 */
function renderText(text: string) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
}

const SOURCE_KIND: Record<string, string> = {
  city_doc: '城市知识',
  poi: '资源点',
  experience: '乡村体验',
  product: '乡村产品',
}

function kindOf(source: QaSource) {
  return SOURCE_KIND[source.doc_type] ?? source.doc_type
}

/** 来源性质标签。后端没给这一格时退回中性文案 —— 不能默认显示成"原文可查" */
function sourceKindLabel(source: QaSource) {
  return QA_SOURCE_KIND_LABEL[source.source_kind] ?? '来源'
}

/** 徽标配色：可核对（绿）/ 站点级（金）/ 数据包自有（中性） */
function sourceKindTone(source: QaSource) {
  if (source.source_kind === 'detail') return 'ok'
  if (source.source_kind === 'site') return 'warn'
  return 'plain'
}

function modeClass(mode: QaMode | undefined) {
  if (mode === 'no_answer') return 'tag tag-danger'
  if (mode === 'extractive') return 'tag tag-warn'
  if (mode === 'cache') return 'tag tag-tech'
  return 'tag tag-brand'
}

/** 链路徽标：金色 = 这一条回答有知识库依据（本项目想让人看见的就是这个），
 *  中性灰 = 通用问答，没用知识库。 */
function routeClass(route: QaRoute | undefined) {
  return route === 'general' ? 'tag' : 'tag tag-gold'
}

// ------------------------------------------------------------------ 生命周期

onMounted(async () => {
  try {
    health.value = await getAiHealth()
  } catch (e) {
    healthError.value = e instanceof Error ? e.message : '无法读取知识库状态'
  }
  try {
    suggestions.value = await getSuggestions()
  } catch {
    // 推荐问题拿不到不影响提问，静默忽略
  }
  composer.value?.focus()
})

onBeforeUnmount(() => stopStream?.())

// 「知识库里有这些」那一段依赖 getCityPack() 的异步数据，数据到达前它并不存在。
// 所以必须把 loading 交给 useReveal，让它在数据就绪后重新扫一遍 .reveal ——
// 否则那一段会永远停在 opacity: 0（表现为"页面中间空了一块"）。
useReveal(root, packLoading)
</script>

<template>
  <div ref="root" class="assistant">
    <!-- ============ 1. 欢迎区（Hero）：仅在未提问时铺满 ============ -->
    <header v-if="!inSession" class="hero">
      <SceneArt variant="ancient" ratio="auto" class="hero__art" />
      <div class="hero__veil" />

      <div class="container hero__inner">
        <!-- AI 状态徽标：放在最显眼处，不用翻侧栏就知道现在跑在哪种模式 -->
        <span class="aistat" :class="`aistat--${aiStatus.lv}`">
          <i class="aistat__dot" />
          {{ aiStatus.label }}
        </span>

        <h1 class="display hero__title">先查过资料，<br />再开口回答</h1>
        <p class="hero__desc">
          涉及汉中的问题先在城市知识库中检索，回答依据检索到的公开资料组织，并附上出处；
          与汉中无关的问题直接回答。知识库没有写过的本地事实，系统会直接说明，
          不会替它补一个听起来合理的答案。
        </p>

        <!-- 覆盖范围：把"这个知识库里有什么"讲清楚，而不是只给一个聊天框 -->
        <dl v-if="!healthError" class="scope">
          <div v-for="s in scopeAll" :key="s.key" class="scope__i">
            <dt>{{ s.label }}</dt>
            <dd><span class="num">{{ s.value }}</span><i>{{ s.unit }}</i></dd>
          </div>
        </dl>

        <!-- 首屏提问框 -->
        <div class="hero__ask">
          <textarea
            ref="composer"
            v-model="question"
            class="hero__input"
            rows="1"
            placeholder="问点具体的，比如「朱鹮是在哪里发现的」「南郑有什么可以带走的特产」"
            @keydown="onKeydown"
          />
          <button
            class="btn btn-gold"
            type="button"
            :disabled="streaming || !question.trim()"
            @click="submit()"
          >
            提问
          </button>
        </div>
        <p class="hero__hint">Enter 发送 · Shift+Enter 换行 · 汉中问题先查知识库，本地事实查不到会直说</p>
      </div>
    </header>

    <!-- ============ 2. 快捷问题 ============ -->
    <section v-if="!inSession && suggestions.length" class="container section">
      <div class="quick reveal">
        <div class="quick__head">
          <span class="eyebrow">快捷问题</span>
          <h2 class="h2 quick__title">这些问题命中率最高</h2>
          <p class="quick__desc">
            推荐问题来自知识库实际覆盖的内容，不是预设的演示脚本 ——
            点一下就能看到完整的检索与出典过程。
          </p>
        </div>
        <div class="quick__chips">
          <button
            v-for="(s, i) in suggestions"
            :key="s"
            class="qchip"
            type="button"
            :disabled="streaming"
            @click="submit(s)"
          >
            <span class="qchip__no num">{{ String(i + 1).padStart(2, '0') }}</span>
            <span class="qchip__text">{{ s }}</span>
            <span class="qchip__go">→</span>
          </button>
        </div>
      </div>
    </section>

    <!-- ============ 3. 可视化知识库内容（真实数据，非写死） ============ -->
    <section v-if="!inSession" class="container section-0">
      <div class="know reveal">
        <div class="know__copy">
          <span class="eyebrow">知识库里有这些</span>
          <h2 class="h2 know__title">不只答常识，也答得上具体的一处、一项、一款</h2>
          <p class="know__desc">
            除城市概况等公开资料外，数据包里的每一处资源、每一项乡村体验、
            每一款挂靠农产品都已切片入库。所以可以问到很细的程度 ——
            某条体验适合什么季节、某款特产的产区在哪里，都有据可查。
          </p>
          <ul class="know__list">
            <li>
              <span class="know__k">原文可核对</span>
              <span class="know__v">涉及汉中的回答附来源，可点开对照原文</span>
            </li>
            <li>
              <span class="know__k">答不上就直说</span>
              <span class="know__v">知识库没有依据的本地事实，明确说明，不编造</span>
            </li>
            <li>
              <span class="know__k">离线可演示</span>
              <span class="know__v">断网时按预生成答案回放或摘录原文，链路不中断</span>
            </li>
          </ul>
        </div>

        <div class="know__cards">
          <article v-if="showcase.exp" class="kcard">
            <div class="kcard__art">
              <SceneArt variant="terrace" ratio="16 / 10" class="kcard__scene" />
              <span class="kcard__kind">乡村体验</span>
            </div>
            <div class="kcard__body">
              <h3 class="kcard__name">{{ showcase.exp.name }}</h3>
              <p class="kcard__meta">
                {{ showcase.exp.poi_name }} · {{ showcase.exp.duration_min }} 分钟
                <template v-if="showcase.exp.season"> · 适宜 {{ showcase.exp.season }}</template>
              </p>
              <p class="kcard__desc">{{ showcase.exp.desc }}</p>
              <div class="kcard__tags">
                <span v-for="t in showcase.exp.tags" :key="t" class="tag">{{ t }}</span>
                <span class="num kcard__price">¥{{ showcase.exp.price }}</span>
              </div>
            </div>
          </article>

          <article v-if="showcase.prod" class="kcard kcard--good">
            <div class="kcard__body">
              <span class="kcard__kind kcard__kind--flat">挂靠农产品</span>
              <h3 class="kcard__name">{{ showcase.prod.name }}</h3>
              <p class="kcard__meta">
                {{ showcase.prod.category }} · {{ showcase.prod.spec }} ·
                产地 {{ showcase.prod.origin_village }}
              </p>
              <p class="kcard__desc">{{ showcase.prod.story }}</p>
              <div class="kcard__tags">
                <span v-for="t in showcase.prod.tags" :key="t" class="tag">{{ t }}</span>
                <span class="num kcard__price">¥{{ showcase.prod.price }}</span>
              </div>
              <p class="kcard__chain">
                ↑ 与上面那项体验同源（<code>{{ showcase.prod.experience_name }}</code>）
              </p>
            </div>
          </article>
        </div>
      </div>
    </section>

    <!-- ============ 4. 对话区（提问后） ============ -->
    <div class="container session" :class="{ 'session--on': inSession }">
      <div class="session__bar">
        <div class="row session__left">
          <span class="aistat aistat--sm" :class="`aistat--${aiStatus.lv}`">
            <i class="aistat__dot" />
            {{ aiStatus.label }}
          </span>
          <span v-if="health" class="cap muted">
            检索 {{ health.chunks }} 切片 / {{ health.docs }} 文档 · {{ modeText }}
          </span>
        </div>
        <button v-if="inSession" class="btn btn-ghost btn-sm" type="button" @click="reset">
          重新开始
        </button>
      </div>

      <div class="layout">
        <!-- 主区：问答 -->
        <section class="thread" ref="thread">
          <article v-for="turn in turns" :key="turn.id" class="turn fade-up">
            <div class="turn__q">
              <span class="turn__mark">问</span>
              <p class="turn__qtext">{{ turn.question }}</p>
            </div>

            <div class="turn__a">
              <div class="turn__bar">
                <span class="turn__mark turn__mark--a">答</span>
                <span v-if="turn.meta" :class="modeClass(turn.meta.mode)">
                  {{ QA_MODE_LABEL[turn.meta.mode] }}
                </span>
                <span
                  v-if="turn.meta"
                  :class="routeClass(turn.meta.route)"
                  :title="QA_ROUTE_HINT[turn.meta.route]"
                >
                  {{ QA_ROUTE_LABEL[turn.meta.route] }}
                </span>
                <!-- 只在**真的检索了、而且来源卡片给得出来**的时候报条数。
                     两个条件必须一致，否则会出现悬空声明：
                     `general` 不检索（retrieved=0）→ 照旧显示会变成"检索 0 条"；
                     离线时 `constrained` 检索了 5 条但一条来源都不给
                     （那些切片没被用来回答）→ 显示"检索 5 条"却没有任何卡片可看，
                     用户/评委问"哪 5 条"界面答不出。 -->
                <span v-if="turn.meta && turn.meta.sources.length > 0" class="cap muted">
                  检索 {{ turn.meta.retrieved }} 条 · 首条相关度
                  {{ (turn.meta.top_score * 100).toFixed(0) }}%
                </span>
                <span v-if="turn.elapsedMs" class="cap muted turn__ms">
                  {{ turn.elapsedMs }} ms
                </span>
              </div>

              <p v-if="turn.answer" class="turn__body body" v-html="renderText(turn.answer)" />
              <p v-else-if="turn.streaming" class="turn__body body turn__pending">
                <span class="dots"><i /><i /><i /></span>
                <!-- meta 先于 delta 到达，所以这里已经知道走的是哪条链路；
                     `general` 不检索，说"正在检索知识库"就是假动作 -->
                {{ turn.meta?.route === 'general' ? '正在生成回答…' : '正在检索知识库…' }}
              </p>

              <p v-if="turn.error" class="turn__err small">{{ turn.error }}</p>

              <!-- 来源卡片：把"这段话从哪儿来"从一行小字升级成可点开的内容卡片。
                   所有字段都来自知识库元数据（后端 _source_payload 组装），
                   模型只负责上面的正文 —— 网址交给模型写就有编造的可能。 -->
              <div v-if="turn.meta?.sources.length" class="sources">
                <div class="sources__head">
                  <!-- 写"检索命中"而不是"回答依据"：取的是 top-5 候选，
                       模型实际用到的往往只有前一两张。说成"依据"是过度声称 ——
                       用户核对时会以为每条都该在正文里找到对应内容。 -->
                  <span class="cap">检索命中 {{ turn.meta.sources.length }} 条</span>
                  <span class="cap sources__note">名称与链接取自知识库元数据，非模型生成</span>
                </div>
                <ul class="sources__list">
                  <li v-for="(src, i) in turn.meta.sources" :key="i" class="scard">
                    <div class="scard__top">
                      <span class="scard__kind cap">{{ kindOf(src) }}</span>
                      <span class="scard__title">{{ src.title }}</span>
                    </div>

                    <p v-if="src.snippet" class="scard__intro">{{ src.snippet }}</p>

                    <div class="scard__foot">
                      <span class="scard__from small">
                        <span class="scard__fromname">{{ src.source_name || '未标注来源' }}</span>
                        <span class="scard__badge" :class="`scard__badge--${sourceKindTone(src)}`">
                          {{ sourceKindLabel(src) }}
                        </span>
                      </span>
                      <!-- 两个链接都开新标签页：本页对话只存在内存里，
                           跳走再回来就清空了，而核对来源恰恰需要来回看 -->
                      <span class="scard__links">
                        <RouterLink
                          v-if="src.poi_id"
                          class="scard__link"
                          :to="`/poi/${src.poi_id}`"
                          target="_blank"
                          rel="noopener"
                        >
                          站内详情 →
                        </RouterLink>
                        <a
                          v-if="src.source_url"
                          class="scard__link scard__link--ext"
                          :href="src.source_url"
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
            </div>
          </article>

          <!-- 输入区 -->
          <div class="composer">
            <textarea
              ref="composer"
              v-model="question"
              class="composer__input"
              rows="2"
              placeholder="继续问，比如「这两处离得远吗」「还有什么适合带走的」"
              @keydown="onKeydown"
            />
            <div class="composer__actions">
              <span class="cap muted">
                Enter 发送 · Shift+Enter 换行 · 汉中问题先查知识库，本地事实查不到会直说
              </span>
              <div class="row">
                <button v-if="streaming" class="btn btn-ghost btn-sm" type="button" @click="stop">
                  停止
                </button>
                <button
                  class="btn btn-primary btn-sm"
                  type="button"
                  :disabled="streaming || !question.trim()"
                  @click="submit()"
                >
                  提问
                </button>
              </div>
            </div>
          </div>
        </section>

        <!-- 侧栏：知识库状态 -->
        <aside class="rail">
          <div class="rail__block">
            <div class="rail__title">知识库状态</div>

            <p v-if="healthError" class="rail__alert small">{{ healthError }}</p>
            <template v-else-if="health">
              <div class="stat">
                <span class="stat__k">切片 / 文档</span>
                <span class="stat__v num">{{ health.chunks }} / {{ health.docs }}</span>
              </div>
              <div class="stat">
                <span class="stat__k">检索词表</span>
                <span class="stat__v num">{{ health.lexical.terms.toLocaleString() }}</span>
              </div>
              <div class="stat">
                <span class="stat__k">回答模式</span>
                <span class="stat__v stat__v--text">{{ modeText }}</span>
              </div>
              <div class="stat">
                <span class="stat__k">预生成答案</span>
                <span class="stat__v num">{{ health.cache_entries }} 条</span>
              </div>
              <div class="stat stat--stack">
                <span class="stat__k">向量化</span>
                <code class="stat__code">{{ health.embedder }}</code>
              </div>
              <p v-if="health.stale" class="rail__alert small">
                {{ health.stale_hint || '语料已变化，建议重建知识库' }}
              </p>
            </template>
            <p v-else class="small muted">正在读取…</p>
          </div>

          <div class="rail__block">
            <div class="rail__title">覆盖范围</div>
            <ul class="scope-list">
              <li v-for="s in scopeText" :key="s.unit" class="scope-list__item">
                <span class="scope-list__v num">{{ s.value }}</span>
                <span class="scope-list__u small muted">{{ s.unit }}</span>
              </li>
            </ul>
            <p class="cap muted scope-list__note">
              城市知识来自汉中市人民政府《汉中概况》等公开资料，资源点信息来自数据包。
              涉及汉中的回答，来源都可点开核对；与汉中无关的问题不查知识库，也就不署名来源。
            </p>
          </div>

          <div class="rail__block">
            <div class="rail__title">推荐问题</div>
            <ul class="asks">
              <li v-for="s in suggestions" :key="s">
                <button class="ask" type="button" :disabled="streaming" @click="submit(s)">
                  {{ s }}
                </button>
              </li>
            </ul>
          </div>
        </aside>
      </div>
    </div>

    <!-- ============ 5. 免责与方法说明 ============ -->
    <section class="container section">
      <div class="foot reveal">
        <div class="foot__col">
          <span class="eyebrow">为什么会答得上</span>
          <p>
            知识库由公开资料与项目数据包共同构建：城市常识来自政府公开页面，
            具体资源信息来自 <code>citypack/</code>。两类都带出处，检索时按
            <b>词法 + 向量混合召回</b>（当前词表
            {{ health?.lexical.terms.toLocaleString() ?? '—' }} 词）取相关片段，
            再由模型或摘录器组织成回答。
          </p>
        </div>
        <div class="foot__col">
          <span class="eyebrow">它不做什么</span>
          <p>
            不做闲聊，不给主观推荐排序，不预测天气与票价 ——
            这些都不是知识库里写着的。它只回答可核对的事实，
            并把"不确定"如实说出来。
          </p>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* ============ 1. 欢迎区 Hero ============ */
.hero {
  position: relative;
  overflow: hidden;
  background: var(--brand-900);
  padding: var(--sp-9) 0 var(--sp-8);
  min-height: min(66vh, 600px);
  display: flex;
  align-items: center;
}
.hero__art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
  opacity: 0.55;
}
.hero__veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    100deg,
    rgba(11, 33, 25, 0.92) 0%,
    rgba(11, 33, 25, 0.78) 46%,
    rgba(11, 33, 25, 0.5) 100%
  );
}
.hero__inner {
  position: relative;
  max-width: 940px;
}
.hero__title {
  margin-top: var(--sp-5);
  color: #fff;
  font-size: var(--fs-mega);
  line-height: 1.15;
}
.hero__desc {
  margin-top: var(--sp-5);
  max-width: 46em;
  font-size: var(--fs-hero-sub);
  line-height: 1.8;
  color: rgba(233, 240, 236, 0.85);
}

/* AI 状态徽标 —— 三档颜色对应三种真实运行形态 */
.aistat {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 13px;
  font-size: var(--fs-cap);
  font-weight: 600;
  letter-spacing: 0.03em;
  border-radius: var(--r-pill);
  border: 1px solid transparent;
}
.aistat--sm {
  font-size: 11px;
  padding: 3px 10px;
}
.aistat__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}
.aistat--ok {
  color: #cdeadd;
  background: rgba(42, 111, 91, 0.35);
  border-color: rgba(113, 169, 150, 0.5);
}
.aistat--demo {
  color: #f0e2c4;
  background: rgba(192, 154, 78, 0.24);
  border-color: rgba(226, 202, 145, 0.5);
}
.aistat--warn {
  color: #f7e6c6;
  background: rgba(168, 121, 29, 0.3);
  border-color: rgba(226, 202, 145, 0.5);
}
.aistat--extract {
  color: #cfe4f7;
  background: rgba(46, 123, 196, 0.24);
  border-color: rgba(147, 191, 230, 0.45);
}
.aistat--off {
  color: #f6dcd5;
  background: rgba(168, 64, 43, 0.3);
  border-color: rgba(231, 156, 136, 0.5);
}
.aistat--wait {
  color: rgba(255, 255, 255, 0.8);
  background: rgba(255, 255, 255, 0.12);
  border-color: rgba(255, 255, 255, 0.25);
}

/* 覆盖范围数字条 */
.scope {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-7);
  margin-top: var(--sp-8);
  padding-top: var(--sp-5);
  border-top: 1px solid rgba(219, 233, 227, 0.18);
}
.scope__i dt {
  font-size: var(--fs-cap);
  letter-spacing: 0.14em;
  color: rgba(255, 255, 255, 0.55);
}
.scope__i dd {
  margin-top: 6px;
  display: flex;
  align-items: baseline;
  gap: 4px;
  color: var(--gold-300);
}
.scope__i dd .num {
  font-size: 30px;
  font-weight: 600;
  line-height: 1;
}
.scope__i dd i {
  font-style: normal;
  font-size: var(--fs-cap);
  color: rgba(255, 255, 255, 0.6);
}

/* 首屏提问框 */
.hero__ask {
  display: flex;
  gap: var(--sp-3);
  align-items: flex-end;
  margin-top: var(--sp-7);
  padding: var(--sp-3);
  background: rgba(255, 255, 255, 0.07);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: var(--r-lg);
  backdrop-filter: blur(6px);
  max-width: 780px;
  transition: border-color var(--dur-2) var(--ease), background var(--dur-2) var(--ease);
}
.hero__ask:focus-within {
  border-color: rgba(226, 202, 145, 0.6);
  background: rgba(255, 255, 255, 0.1);
}
.hero__input {
  flex: 1;
  min-height: 40px;
  max-height: 132px;
  padding: var(--sp-2) var(--sp-3);
  color: #fff;
  font-size: var(--fs-body);
  font-family: inherit;
  line-height: 1.6;
  background: transparent;
  border: none;
  outline: none;
  resize: none;
}
.hero__input::placeholder {
  color: rgba(255, 255, 255, 0.46);
}
.hero__hint {
  margin-top: var(--sp-3);
  font-size: var(--fs-cap);
  color: rgba(255, 255, 255, 0.5);
}

/* ============ 2. 快捷问题 ============ */
.quick {
  display: grid;
  grid-template-columns: 340px 1fr;
  gap: var(--sp-7);
  align-items: start;
}
.quick__title {
  margin-top: var(--sp-3);
}
.quick__desc {
  margin-top: var(--sp-4);
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: var(--ink-500);
}
.quick__chips {
  display: flex;
  flex-direction: column;
}
/* 用上下细线分隔的列表，不套卡片 —— 与管理端那种"卡片墙"区分开 */
.qchip {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  width: 100%;
  padding: var(--sp-4) var(--sp-2);
  text-align: left;
  border-bottom: 1px solid var(--line-soft);
  transition: padding-left var(--dur-2) var(--ease), border-color var(--dur-2) var(--ease);
}
.qchip:first-child {
  border-top: 1px solid var(--line-soft);
}
.qchip:hover:not(:disabled) {
  padding-left: var(--sp-4);
  border-color: var(--line);
}
.qchip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.qchip__no {
  flex: none;
  font-size: var(--fs-cap);
  font-weight: 700;
  letter-spacing: 0.08em;
  color: var(--gold-500);
}
.qchip__text {
  flex: 1;
  font-size: var(--fs-body);
  color: var(--ink-700);
  line-height: 1.6;
}
.qchip:hover:not(:disabled) .qchip__text {
  color: var(--brand-700);
}
.qchip__go {
  flex: none;
  color: var(--warm-400);
  transition: transform var(--dur-2) var(--ease), color var(--dur-2) var(--ease);
}
.qchip:hover:not(:disabled) .qchip__go {
  color: var(--brand-600);
  transform: translateX(4px);
}

/* ============ 3. 知识库内容展示 ============ */
.know {
  display: grid;
  grid-template-columns: 1fr 1.08fr;
  gap: var(--sp-8);
  align-items: start;
  padding-top: var(--sp-7);
  border-top: 1px solid var(--line);
}
.know__title {
  margin-top: var(--sp-3);
  max-width: 18em;
}
.know__desc {
  margin-top: var(--sp-4);
  font-size: var(--fs-sm);
  line-height: 1.9;
  color: var(--ink-500);
}
.know__list {
  margin-top: var(--sp-6);
  display: flex;
  flex-direction: column;
}
.know__list li {
  display: grid;
  grid-template-columns: 110px 1fr;
  gap: var(--sp-4);
  padding: var(--sp-3) 0;
  border-top: 1px solid var(--line-soft);
}
.know__k {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--brand-700);
}
.know__v {
  font-size: var(--fs-sm);
  color: var(--ink-500);
  line-height: 1.7;
}

.know__cards {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.kcard {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: var(--sp-5);
  padding: var(--sp-4);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-left: 3px solid var(--brand-500);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
}
/* 农产品卡不带图，改为上描边 —— 与体验卡区分开，也避免"又是一张卡片" */
.kcard--good {
  display: block;
  border-left-color: var(--gold-500);
  background: var(--gold-50);
}
.kcard__art {
  position: relative;
  overflow: hidden;
  border-radius: var(--r-md);
}
.kcard__scene {
  border-radius: 0;
}
.kcard__kind {
  position: absolute;
  left: var(--sp-3);
  top: var(--sp-3);
  padding: 3px 9px;
  font-size: var(--fs-cap);
  font-weight: 600;
  color: #fff;
  background: rgba(11, 33, 25, 0.66);
  border-radius: var(--r-sm);
  backdrop-filter: blur(3px);
}
.kcard__kind--flat {
  position: static;
  display: inline-block;
  color: var(--gold-600);
  background: rgba(192, 154, 78, 0.14);
}
.kcard__body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.kcard__name {
  font-family: var(--font-display);
  font-size: 19px;
  line-height: 1.35;
}
.kcard__meta {
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.kcard__desc {
  margin-top: var(--sp-2);
  font-size: var(--fs-sm);
  line-height: 1.75;
  color: var(--ink-500);
}
.kcard__tags {
  margin-top: var(--sp-2);
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.kcard__price {
  margin-left: auto;
  font-size: var(--fs-h3);
  font-weight: 700;
  color: var(--gold-600);
}
.kcard__chain {
  margin-top: var(--sp-3);
  padding-top: var(--sp-3);
  border-top: 1px dashed rgba(192, 154, 78, 0.4);
  font-size: var(--fs-cap);
  color: var(--gold-600);
  line-height: 1.6;
}
.kcard__chain code {
  padding: 1px 5px;
  font-size: 11px;
  background: rgba(255, 255, 255, 0.7);
  border-radius: var(--r-sm);
}

/* ============ 4. 对话区 ============ */
.session {
  padding-top: var(--sp-5);
}
.session--on {
  padding-top: var(--sp-7);
}
.session__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
  padding-bottom: var(--sp-4);
  margin-bottom: var(--sp-6);
  border-bottom: 1px solid var(--line);
}
.session__left {
  gap: var(--sp-4);
  flex-wrap: wrap;
}
/* 对话区内的徽标改用浅底配色（深色版在白底上看不清） */
.session__bar .aistat {
  color: var(--brand-700);
  background: var(--brand-50);
  border-color: var(--brand-100);
}
.session__bar .aistat--demo,
.session__bar .aistat--warn {
  color: var(--gold-600);
  background: var(--gold-50);
  border-color: var(--gold-300);
}
.session__bar .aistat--extract {
  color: var(--tech-600);
  background: var(--tech-50);
  border-color: var(--tech-300);
}
.session__bar .aistat--off {
  color: var(--danger);
  background: var(--danger-50);
  border-color: #e7c4ba;
}

.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: var(--sp-7);
  align-items: start;
}

/* 对话区：不再套一层卡片，直接铺在页面底色上 */
.thread {
  display: flex;
  flex-direction: column;
  gap: var(--sp-7);
  min-width: 0;
}

.turn {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.turn__q {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-3);
}
.turn__qtext {
  font-family: var(--font-display);
  font-size: 21px;
  line-height: 1.5;
  color: var(--ink-900);
  padding-top: 2px;
}
.turn__mark {
  flex: none;
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  font-size: var(--fs-cap);
  font-weight: 700;
  border-radius: var(--r-sm);
  color: var(--gold-600);
  background: var(--gold-50);
  border: 1px solid var(--gold-300);
  margin-top: 3px;
}
.turn__mark--a {
  color: var(--brand-700);
  background: var(--brand-50);
  border-color: var(--brand-100);
}

.turn__a {
  padding-left: 39px;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.turn__bar {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.turn__ms {
  margin-left: auto;
}
.turn__body {
  font-size: var(--fs-body);
  line-height: 1.95;
  color: var(--ink-700);
  white-space: pre-wrap;
  max-width: 62em;
}
.turn__body :deep(strong) {
  color: var(--brand-800);
  font-weight: 600;
}
.turn__pending {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  color: var(--warm-500);
}
.turn__err {
  color: var(--danger);
}
.dots {
  display: inline-flex;
  gap: 4px;
}
.dots i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--brand-400);
  animation: blink 1.1s infinite ease-in-out;
}
.dots i:nth-child(2) {
  animation-delay: 0.16s;
}
.dots i:nth-child(3) {
  animation-delay: 0.32s;
}
@keyframes blink {
  0%,
  70%,
  100% {
    opacity: 0.25;
  }
  35% {
    opacity: 1;
  }
}

/* 来源卡片 */
.sources {
  margin-top: var(--sp-2);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--line-soft);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.sources__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
  letter-spacing: 0.04em;
  color: var(--warm-500);
}
.sources__note {
  color: var(--warm-400);
}
/* 自动填充的网格：一条来源时不至于拉成一整行，五条时也不会挤成一列 */
.sources__list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(288px, 1fr));
  gap: var(--sp-3);
}
.scard {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-3) var(--sp-4);
  background: #fff;
  border: 1px solid var(--line-soft);
  /* 左侧绿线：与"可点开核对"的语义呼应，比整块投影更克制 */
  border-left: 3px solid var(--brand-400);
  border-radius: var(--r-md);
  transition: border-color var(--dur-2) var(--ease), box-shadow var(--dur-2) var(--ease);
}
.scard:hover {
  border-color: var(--line);
  box-shadow: var(--sh-1);
}
.scard__top {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.scard__kind {
  flex: none;
  padding: 2px 7px;
  color: var(--brand-600);
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
  font-size: var(--fs-cap);
  line-height: 1.7;
  color: var(--ink-500);
}
.scard__foot {
  margin-top: auto;
  padding-top: var(--sp-2);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.scard__from {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: var(--warm-500);
}
.scard__fromname {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.scard__badge {
  flex: none;
  padding: 1px 6px;
  font-size: 10px;
  letter-spacing: 0.04em;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
}
.scard__badge--ok {
  color: var(--brand-600);
  background: var(--brand-50);
  border-color: var(--brand-100);
}
.scard__badge--warn {
  color: var(--gold-600);
  background: var(--gold-50);
  border-color: var(--gold-300);
}
.scard__badge--plain {
  color: var(--ink-500);
  background: var(--paper-2);
  border-color: var(--line-soft);
}
.scard__links {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-3);
  flex: none;
}
.scard__link {
  font-size: var(--fs-cap);
  font-weight: 500;
  color: var(--brand-600);
  white-space: nowrap;
}
.scard__link--ext {
  color: var(--tech-600);
}
.scard__link:hover {
  text-decoration: underline;
}

/* 输入区：去掉卡片感，只留一条上边线与输入框 */
.composer {
  position: sticky;
  bottom: 0;
  padding: var(--sp-4) 0 var(--sp-5);
  background: linear-gradient(180deg, rgba(250, 248, 243, 0) 0%, var(--paper) 22%);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.composer__input {
  width: 100%;
  padding: var(--sp-4);
  font-size: var(--fs-body);
  font-family: inherit;
  line-height: 1.7;
  color: var(--ink-800, var(--ink-700));
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
  resize: vertical;
  transition: border-color var(--dur-1) var(--ease), box-shadow var(--dur-1) var(--ease);
}
.composer__input:focus {
  outline: none;
  border-color: var(--brand-500);
  box-shadow: 0 0 0 3px var(--brand-50);
}
.composer__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
}

/* 侧栏 */
.rail {
  position: sticky;
  top: calc(var(--nav-h) + var(--sp-5));
  display: flex;
  flex-direction: column;
  gap: var(--sp-6);
}
.rail__block {
  padding-top: var(--sp-4);
  border-top: 2px solid var(--brand-500);
}
.rail__title {
  font-size: var(--fs-sm);
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--ink-900);
  margin-bottom: var(--sp-4);
}
.rail__alert {
  padding: var(--sp-3);
  color: var(--warn);
  background: var(--warn-50);
  border-radius: var(--r-md);
  line-height: 1.6;
}

.stat {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-3) 0;
  border-bottom: 1px solid var(--line-soft);
}
.stat--stack {
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}
.stat__k {
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.stat__v {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--brand-700);
}
.stat__v--text {
  font-weight: 500;
  color: var(--ink-700);
  text-align: right;
}
.stat__code {
  font-size: 11px;
  padding: 3px 7px;
  color: var(--ink-600);
  background: var(--paper-2);
  border-radius: var(--r-sm);
  word-break: break-all;
}

.scope-list {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-3) var(--sp-4);
}
.scope-list__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.scope-list__v {
  font-size: 22px;
  font-weight: 600;
  color: var(--brand-700);
  line-height: 1.1;
}
.scope-list__note {
  margin-top: var(--sp-4);
  line-height: 1.7;
}

.asks {
  display: flex;
  flex-direction: column;
}
.ask {
  width: 100%;
  padding: var(--sp-3) 0;
  text-align: left;
  font-size: var(--fs-sm);
  line-height: 1.6;
  color: var(--ink-600);
  border-bottom: 1px solid var(--line-soft);
  transition: color var(--dur-1) var(--ease), padding-left var(--dur-1) var(--ease);
}
.ask:hover:not(:disabled) {
  color: var(--brand-700);
  padding-left: 6px;
}
.ask:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ============ 5. 页尾 ============ */
.foot {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-8);
  padding-top: var(--sp-7);
  border-top: 1px solid var(--line);
}
.foot__col p {
  margin-top: var(--sp-4);
  font-size: var(--fs-sm);
  line-height: 1.9;
  color: var(--ink-500);
}
.foot__col p b {
  color: var(--ink-700);
}
.foot__col code {
  font-size: var(--fs-cap);
  padding: 2px 6px;
  background: var(--paper-2);
  border-radius: var(--r-sm);
}

/* ============ 响应式 ============ */
@media (max-width: 1080px) {
  .quick {
    grid-template-columns: 1fr;
    gap: var(--sp-5);
  }
  .know {
    grid-template-columns: 1fr;
    gap: var(--sp-6);
  }
  .layout {
    grid-template-columns: 1fr;
  }
  .rail {
    position: static;
  }
  .foot {
    grid-template-columns: 1fr;
    gap: var(--sp-6);
  }
}

@media (max-width: 720px) {
  .hero {
    min-height: auto;
    padding: var(--sp-8) 0 var(--sp-7);
  }
  .scope {
    gap: var(--sp-5) var(--sp-6);
    margin-top: var(--sp-6);
  }
  .scope__i dd .num {
    font-size: 24px;
  }
  .hero__ask {
    flex-direction: column;
    align-items: stretch;
  }
  .kcard {
    grid-template-columns: 1fr;
  }
  .know__list li {
    grid-template-columns: 1fr;
    gap: var(--sp-1);
  }
  .turn__a {
    padding-left: 0;
  }
  .turn__qtext {
    font-size: 18px;
  }
  .sources__list {
    grid-template-columns: 1fr;
  }
  .scard__from {
    flex: 1 0 100%;
  }
  .stat__v--text {
    text-align: left;
  }
}
</style>
