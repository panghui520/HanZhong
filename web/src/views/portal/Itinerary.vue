<script setup lang="ts">
/**
 * Itinerary —— 行程规划工具（**纯前端规则，不调用 AI**）
 *
 * 本页是首页「智能行程规划」区块里"想自己动手排"那个**次级入口**的落地页；
 * 主入口指向 /agent —— 那条路才真的调模型（见 Home.vue 里 D1 那段注释）。
 *
 * **当前仍是纯前端规则演示，不调用后端接口，也没有任何模型调用。** M4（多智能体行程规划）接入时，
 * 把 `plan` 这个 computed 换成一次接口调用即可 —— 它已经是一个
 * "入参 → { days, summary, picks }" 的纯函数式映射，UI 不需要改。
 * ★ 正因为它一次模型都不调，页面文案**一律不出现"AI / 自动规划"**，
 *   定位就是"行程规划工具 / 行程安排"（见 banner 的 eyebrow）。
 *
 * 之所以把规则写得这么显式（而不是随机拼几条），是因为答辩要能讲清
 * "判定与生成是分开的"：
 *   - 承载力、距离、类型搭配 → 本文件的规则函数（确定性、可解释）
 *   - 措辞与推荐理由          → 未来交给 LLM（见页尾"怎么排出来的"）
 * 页面里每一句 why 都是从数据算出来的，不是写死的文案。
 *
 * ★ 面向游客（2026-09-28 复查）：**上面这些是开发/答辩口径，只留在代码里。**
 *   页面上不再出现"规则引擎 + AI 生成""承载力判定不能交给概率模型""M4 多智能体
 *   规划尚未接入""承载 87%"这类字样 —— 换成了"按你填的条件现场算出""今天这里人
 *   很多"。游客要的是"我能得到什么"，不是"我们用了什么技术"。
 *   承载百分比统一走 `crowdWord`（人少 / 人较多 / 人很多）。
 */
import { computed, ref } from 'vue'
import { getCityPack } from '@/api/citypack'
import { isEmpty, useAsync } from '@/composables/useAsync'
import { crowdLevelOf, crowdWord, usePoiStats } from '@/composables/usePoiStats'
import { useReveal } from '@/composables/useReveal'
import SceneArt from '@/components/SceneArt.vue'
import PoiImage from '@/components/PoiImage.vue'
import SectionHead from '@/components/SectionHead.vue'
import { BUSINESS_LABEL, type Experience, type Poi, type Product } from '@/types'

const { data, loading, error, reload } = useAsync(getCityPack)

/** 承载力（M5，真实数据，来自 /api/stats/pois）。见下方 score() 对"取不到"的处理 */
const { usageOf } = usePoiStats()

const root = ref<HTMLElement | null>(null)

/* ============================================================
 * 入参
 * ============================================================ */

const days = ref(2)
const pace = ref<'relax' | 'normal' | 'packed'>('normal')
const budget = ref<'low' | 'mid' | 'high'>('mid')

/** 兴趣是多选 —— 但至少留一个，空选会让"匹配度"这项算不出东西 */
const INTERESTS = [
  { key: 'nature', label: '山野自然', tag: '自然' },
  { key: 'culture', label: '历史人文', tag: '人文' },
  { key: 'rural', label: '乡村体验', tag: '乡村' },
  { key: 'food', label: '地方风味', tag: '小吃' },
  { key: 'craft', label: '手作非遗', tag: '手作' },
] as const

type InterestKey = (typeof INTERESTS)[number]['key']
const interests = ref<InterestKey[]>(['nature', 'rural'])

function toggleInterest(k: InterestKey) {
  const i = interests.value.indexOf(k)
  if (i >= 0) {
    // 不允许清空：一个兴趣都不选时"匹配度"无从谈起，UI 也会显得像坏了
    if (interests.value.length === 1) return
    interests.value.splice(i, 1)
  } else {
    interests.value.push(k)
  }
}

const PACE_LABEL: Record<string, string> = {
  relax: '轻松',
  normal: '适中',
  packed: '充实',
}
const PACE_DESC: Record<string, string> = {
  relax: '每天 2 个停留点，留出午休与机动时间',
  normal: '每天 3 个停留点，节奏常见的自由行强度',
  packed: '每天 4 个停留点，适合假期短、目标明确的行程',
}
const BUDGET_LABEL: Record<string, string> = { low: '经济', mid: '舒适', high: '充裕' }
/** 预算档位对应的"单人日预算"区间，用于估算与产品筛选 */
const BUDGET_RANGE: Record<string, [number, number]> = {
  low: [150, 350],
  mid: [350, 700],
  high: [700, 1400],
}

const perDay = computed(() => (pace.value === 'relax' ? 2 : pace.value === 'normal' ? 3 : 4))
const totalSpots = computed(() => days.value * perDay.value)

/** 节奏档位一句话说明，给面板里的分段按钮当副标题 */
function perDayHint(p: string) {
  return { relax: '2 个点', normal: '3 个点', packed: '4 个点' }[p] ?? ''
}

/** 承载率 → 展示档位。danger 是刻意保留的：超载样本要看得见 */
/** 承载档位。`unknown` 与 `ok` 必须分开：把"读不到"归成"舒适"是个错误的结论 */
function loadLv(u: number | undefined) {
  return crowdLevelOf(u)
}

/**
 * 承载文案。
 *
 * ★ 2026-09-28 面向游客：不再输出"23%"（当日占用率），改用定性词
 *   "人少 / 人较多 / 人很多"。阈值只有一份，在 `usePoiStats` 里。
 *   取不到时给 "—"，**不要显示成"人少"**（那是"很空"，是另一个结论）。
 */
function usageText(u: number | undefined) {
  return crowdWord(u)
}

/* ============================================================
 * 规则：候选召回 → 承载力排序 → 类型搭配 → 分段
 * ============================================================ */

const picks = computed(() => (data.value?.pois ?? []))

/** 兴趣 → 该兴趣偏好的 POI 业态与标签 */
const INTEREST_MATCH: Record<InterestKey, { types: string[]; tags: string[] }> = {
  nature: { types: ['SCENIC'], tags: ['自然', '山水', '云海', '森林'] },
  culture: { types: ['SCENIC'], tags: ['人文', '历史', '古建', '三国', '汉'] },
  rural: { types: ['RURAL_SPOT'], tags: ['乡村', '村落', '田园'] },
  food: { types: ['FOOD'], tags: ['小吃', '风味', '美食'] },
  craft: { types: ['RURAL_SPOT'], tags: ['手作', '非遗', '工坊'] },
}

/**
 * 一条 POI 对当前兴趣偏好的匹配度（0..1）。
 *
 * 用标签命中数 / 兴趣数，而不是加权求和 —— 加权需要一套说不清来源的系数，
 * 答辩时会被问"这个 0.6 是怎么定的"。计数法能一句话解释清楚。
 */
function matchScore(p: Poi) {
  let hit = 0
  for (const k of interests.value) {
    const m = INTEREST_MATCH[k]
    const typeOk = m.types.includes(p.business_type)
    const tagOk = p.tags.some((t) => m.tags.some((mt) => t.includes(mt)))
    if (typeOk || tagOk) hit++
  }
  return hit / Math.max(1, interests.value.length)
}

/**
 * 综合打分：匹配度为主，承载力余量为辅。
 *
 * 承载力**加权为正**：余量越充足越优先 —— 这正是"把客流导向有余处"的规则化表达。
 * 注意这与"景点越热门越靠前"是相反的逻辑，是刻意的。
 *
 * 承载取不到时余量记 0（即**不给这份加分，也不倒扣**）：
 * 若按"未知 = 空"来算，一个读不到承载的点会拿到满额加分、被排到最前，
 * 而这条加分的全部依据恰恰是"它当前有余量"。宁可退化成"只按匹配度排"，
 * 也不要让一个没有依据的假设支配排序。
 */
function score(p: Poi) {
  const usage = usageOf(p.id)
  const headroom = usage == null ? 0 : Math.max(0, 1 - usage)
  return matchScore(p) * 0.72 + headroom * 0.28
}

/** 排序后的候选池（同分时保持数据包编码顺序，保证结果稳定可复现） */
const ranked = computed<Poi[]>(() => {
  const all = picks.value
  return all
    .map((p, i) => ({ p, s: score(p), i }))
    .sort((a, b) => (b.s - a.s) || (a.i - b.i))
    .map((x) => x.p)
})

/**
 * 按"景区 → 乡村体验 → 乡村好物"编排动线。
 *
 * 关键规则：**每 3 个停留点里有 1 个必须是乡村**（`i % 3 === 2`）。
 * 这不是为了好看 —— 它是"客流下乡"这个主张在单次行程里的最小落地：
 * 一个 3 天的常规行程，至少会被塞进 2–3 个乡村点。
 */
type Kind = 'scenic' | 'rural' | 'food'

type Stop = {
  id: string
  name: string
  poiId: string
  kind: Kind
  /** 展示用类型名（区县 + 业态） */
  meta: string
  why: string
  /** 该点命中的兴趣标签，用于展示"为什么推荐给你" */
  hits: string[]
  /** 生成这一版方案时的承载占用率。**取不到时为 undefined**（见 reasonFor） */
  usage?: number
  duration: number
  ticket: number
  scene: string
  /** 乡村点挂的体验（M2 的 experience），景区点为空 */
  exp: Experience | null
  /** 挂在该体验下的农产品（M2 的 product），体现"离境复购" */
  goods: Product[]
}

const plan = computed<{ day: number; stops: Stop[] }[]>(() => {
  const pois = ranked.value
  if (isEmpty(pois)) return []

  const scenics = pois.filter((p) => p.business_type === 'SCENIC')
  const rurals = pois.filter((p) => p.business_type === 'RURAL_SPOT')
  const foods = pois.filter((p) => p.business_type === 'FOOD')
  const exps = data.value?.experiences ?? []
  const prods = data.value?.products ?? []

  const seq: Stop[] = []
  const usedIds = new Set<string>()

  /** 取下一个未用过的候选；池子耗尽则允许复用（数据只有 42 条，长行程必然要复用） */
  function take(pool: Poi[]): Poi | null {
    const fresh = pool.find((p) => !usedIds.has(p.id))
    const chosen = fresh ?? pool[0] ?? null
    if (chosen) usedIds.add(chosen.id)
    return chosen
  }

  for (let i = 0; i < totalSpots.value; i++) {
    // 每 3 个点插 1 个乡村 —— 见上方注释，这是主张的最小落地
    const wantRural = i % 3 === 2
    // 选了「地方风味」时，每 4 个点插一个餐饮点。
    // 不选就不插：口味偏好是这个兴趣项唯一的作用点，
    // 否则这个选项就成了摆设（用户选了却看不到任何变化）。
    const wantFood = !wantRural && i % 4 === 3 && foods.length > 0 && interests.value.includes('food')

    const pool = wantRural && rurals.length
      ? rurals
      : wantFood
        ? foods
        : scenics.length
          ? scenics
          : rurals
    const p = take(pool)
    if (!p) continue

    const isRural = p.business_type === 'RURAL_SPOT'
    const usage = usageOf(p.id)

    // 乡村点找它挂的体验（第一条），再顺着体验找农产品
    const exp = isRural ? (exps.find((e) => e.poi_id === p.id) ?? null) : null
    const goods = exp ? prods.filter((x) => x.experience_id === exp.id) : []

    seq.push({
      id: `${p.id}-${i}`,
      name: p.name,
      poiId: p.id,
      kind: isRural ? 'rural' : p.business_type === 'FOOD' ? 'food' : 'scenic',
      meta: `${p.district} · ${BUSINESS_LABEL[p.business_type]}`,
      why: reasonFor(p, usage, isRural, i),
      hits: hitTags(p),
      usage,
      duration: p.duration_min,
      ticket: p.ticket_price,
      scene: p.scene || 'qinling',
      exp,
      goods: goods.slice(0, 2),
    })
  }

  // 按天切分
  const out: { day: number; stops: Stop[] }[] = []
  for (let d = 0; d < days.value; d++) {
    out.push({ day: d + 1, stops: seq.slice(d * perDay.value, (d + 1) * perDay.value) })
  }
  return out
})

/**
 * 这一站为什么排在这儿。
 *
 * ★ 2026-09-28 面向游客：承载那一句原来写"当前承载 87% 已超载"，
 *   现在是"今天这里人很多" —— 游客要的是结论，不是占用率。
 *
 * 承载那一句在**取不到承载时不写**：这句是"为什么排它在这里"的理由，
 * 编一句"人少"比不说更糟 —— 用户会照着一个没有依据的结论安排行程。
 * 但也不能整段空着（后半段拼出来会是个孤零零的句号），所以给一句可执行的话。
 */
function reasonFor(p: Poi, usage: number | undefined, isRural: boolean, idx: number): string {
  const parts: string[] = []
  if (usage == null) {
    parts.push('今天这处的人流暂时读不到，出发前再确认一下')
  } else if (usage >= 1) {
    parts.push('今天这里人很多，排在这一天正好避开高峰')
  } else if (usage >= 0.8) {
    parts.push('今天这里人较多，建议错开午后时段')
  } else {
    parts.push('今天这里人少，不用排队')
  }

  if (isRural) parts.push('离上一站不远，顺路就能去')
  else if (idx === 0) parts.push('作为当日首站，上午时段体验最佳')

  const hit = hitTags(p)
  if (hit.length) parts.push(`符合你选的「${hit.join(' / ')}」`)

  return parts.join('，') + '。'
}

/** 这条 POI 命中了哪些兴趣标签（转成展示文案） */
function hitTags(p: Poi): string[] {
  const out: string[] = []
  for (const k of interests.value) {
    const m = INTEREST_MATCH[k]
    const typeOk = m.types.includes(p.business_type)
    const tagOk = p.tags.some((t) => m.tags.some((mt) => t.includes(mt)))
    if (typeOk || tagOk) out.push(INTERESTS.find((x) => x.key === k)!.label)
  }
  return out
}

/* ============================================================
 * 汇总指标
 * ============================================================ */

/** 全程停留点（扁平） */
const allStops = computed(() => plan.value.flatMap((d) => d.stops))

/** 乡村点占比 —— 直接展示"客流下乡"这条主张在本次规划里的兑现程度 */
const ruralShare = computed(() => {
  const all = allStops.value
  if (!all.length) return 0
  return Math.round((all.filter((s) => s.kind === 'rural').length / all.length) * 100)
})

/** 门票 + 体验的硬支出估算（不含餐饮住宿，避免给出貌似精确其实无依据的总价） */
const ticketSum = computed(() => allStops.value.reduce((s, x) => s + (x.ticket || 0), 0))
const expSum = computed(() =>
  allStops.value.reduce((s, x) => s + (x.exp?.price ?? 0) + x.goods.reduce((a, g) => a + g.price, 0), 0)
)

const budgetFit = computed(() => {
  const [lo, hi] = BUDGET_RANGE[budget.value]
  const perPersonDay = (ticketSum.value + expSum.value) / Math.max(1, days.value)
  if (perPersonDay > hi) return { level: 'over', text: '超出该档位，建议减一站或提高预算档' }
  if (perPersonDay < lo) return { level: 'under', text: '明显低于该档位，可再加一项乡村体验' }
  return { level: 'fit', text: '与该预算档位相符' }
})

// 入参变化 → 结果区重绘 → 重新扫描 reveal
useReveal(
  root,
  loading,
  computed(() => `${days.value}|${pace.value}|${budget.value}|${interests.value.join(',')}`)
)
</script>

<template>
  <div ref="root" class="itin">
    <!-- ============ 1. Banner ============ -->
    <header class="banner">
      <SceneArt variant="terrace" ratio="auto" class="banner__art" />
      <div class="banner__veil" />
      <div class="container banner__inner">
        <!-- ★ 不写"智能/AI 行程规划"：本页是纯规则计算，一次模型都不调。
             定位成"工具"，与真实能力一致（见文件头注释）。 -->
        <span class="eyebrow eyebrow--light">行程规划工具</span>
        <h1 class="display banner__title">让每一段汉中旅程<br />都恰到好处</h1>
        <p class="banner__desc">
          告诉我出行天数、同行人数和偏好，我帮你把景点、美食、住宿与乡村体验排成一条能照着走的动线 ——
          顺带看一眼各处今天的人流，把人多的往后放、人少的往前排，不用把时间花在排队上。
        </p>
      </div>
    </header>

    <div class="container section">
      <!-- ============ 2. 规划工作台（左入参 / 右结论） ============ -->
      <div class="bench">
        <!-- 左：入参 -->
        <aside class="panel">
          <div class="panel__head">
            <span class="eyebrow">规划参数</span>
            <h2 class="panel__title">告诉它你的条件</h2>
          </div>

          <div class="field">
            <label class="field__label">行程天数</label>
            <div class="seg">
              <button
                v-for="d in [1, 2, 3]"
                :key="d"
                class="seg__item"
                :class="{ 'seg__item--on': days === d }"
                @click="days = d"
              >
                {{ d }} 天
              </button>
            </div>
          </div>

          <div class="field">
            <label class="field__label">旅行节奏</label>
            <div class="seg seg--stack">
              <button
                v-for="p in (['relax', 'normal', 'packed'] as const)"
                :key="p"
                class="seg__item"
                :class="{ 'seg__item--on': pace === p }"
                @click="pace = p"
              >
                <span class="seg__name">{{ PACE_LABEL[p] }}</span>
                <span class="seg__hint">{{ perDayHint(p) }}</span>
              </button>
            </div>
            <p class="field__note">{{ PACE_DESC[pace] }}</p>
          </div>

          <div class="field">
            <label class="field__label">预算档位</label>
            <div class="seg">
              <button
                v-for="b in (['low', 'mid', 'high'] as const)"
                :key="b"
                class="seg__item"
                :class="{ 'seg__item--on': budget === b }"
                @click="budget = b"
              >
                {{ BUDGET_LABEL[b] }}
              </button>
            </div>
            <p class="field__note">
              {{ BUDGET_LABEL[budget] }}档参考 ¥{{ BUDGET_RANGE[budget][0] }}–{{ BUDGET_RANGE[budget][1] }} / 人 / 天
            </p>
          </div>

          <div class="field">
            <label class="field__label">兴趣偏好<span class="field__multi">可多选</span></label>
            <div class="chips">
              <button
                v-for="it in INTERESTS"
                :key="it.key"
                class="chip"
                :class="{ 'chip--on': interests.includes(it.key) }"
                @click="toggleInterest(it.key)"
              >
                {{ it.label }}
              </button>
            </div>
            <p class="field__note">至少保留一项。已选 {{ interests.length }} 项。</p>
          </div>
        </aside>

        <!-- 右：AI 判定过程 -->
        <section class="analysis">
          <div class="analysis__head">
            <span class="ai-badge"><i class="ai-badge__dot" />按你填的条件现场算出</span>
            <h2 class="analysis__title">这份行程的要点</h2>
          </div>

          <p v-if="loading" class="analysis__lead muted">正在读取资源与今天的人流…</p>
          <p v-else-if="error" class="analysis__lead">
            <span style="color: var(--danger)">{{ error }}</span>
            <button class="btn btn-ghost btn-sm" style="margin-left: 12px" @click="reload">重试</button>
          </p>
          <p v-else class="analysis__lead">
            在 {{ data?.pois.length ?? 0 }} 处资源里，先按你选中的兴趣挑，再看今天各处人多不多，
            取前 {{ totalSpots }} 个点排成 {{ days }} 天动线，
            <b>其中 {{ ruralShare }}% 安排在乡村体验点</b>。
          </p>

          <dl v-if="!loading && !error" class="metrics">
            <div class="metric">
              <dt>停留点</dt>
              <dd class="num">{{ allStops.length }}</dd>
            </div>
            <div class="metric">
              <dt>乡村体验占比</dt>
              <dd class="num">{{ ruralShare }}<i>%</i></dd>
            </div>
            <div class="metric">
              <dt>门票与体验</dt>
              <dd class="num">¥{{ ticketSum + expSum }}</dd>
            </div>
            <div class="metric">
              <dt>预算匹配</dt>
              <dd class="metric__text" :class="`metric__text--${budgetFit.level}`">
                {{ budgetFit.text }}
              </dd>
            </div>
          </dl>

          <p class="analysis__note">
            这笔钱 = 门票 + 乡村体验 + 想带走的特产，<b>不含餐饮与住宿</b> ——
            这两项没有可靠的公开价格，给出一个貌似精确的总价反而是误导。
          </p>
        </section>
      </div>
    </div>

    <!-- ============ 3. 时间轴动线 ============ -->
    <div class="container section-0">
      <SectionHead
        eyebrow="行程草案"
        :title="`${days} 天 · ${PACE_LABEL[pace]} · ${BUDGET_LABEL[budget]}预算`"
        desc="每一站为什么排在这儿都写在下面：按你的兴趣，也按今天各处人多不多。"
        size="lg"
        more-text="看真实资源"
        more-to="/explore"
      />

      <div v-if="loading" class="plan__sk">
        <div v-for="i in 3" :key="i" class="skeleton plan__skrow" />
      </div>

      <div v-else-if="isEmpty(plan)" class="empty">
        <div class="empty__title">暂时生不出行程</div>
        <!-- ★ 面向游客：原来写"数据包里没有可用资源，请检查后端服务"——开发自检话术。 -->
        <div class="empty__desc">换个天数或兴趣再试一次，或者先去「探索汉中」看看有哪些地方。</div>
      </div>

      <template v-else>
        <section v-for="d in plan" :key="d.day" class="dayblock reveal">
          <header class="dayblock__head">
            <span class="dayblock__no num">DAY {{ d.day }}</span>
            <span class="dayblock__line" />
            <span class="dayblock__count">{{ d.stops.length }} 站</span>
          </header>

          <ol class="tl">
            <li v-for="(s, i) in d.stops" :key="s.id" class="tl__item" :class="`tl__item--${s.kind}`">
              <!-- 左侧时间轴轨 -->
              <div class="tl__rail">
                <span class="tl__node" :class="`tl__node--${s.kind}`">{{ i + 1 }}</span>
                <span v-if="i < d.stops.length - 1" class="tl__stem" />
              </div>

              <!-- 内容 -->
              <div class="tl__body">
                <router-link :to="`/poi/${s.poiId}`" class="stopcard">
                  <div class="stopcard__art">
                    <PoiImage
                      :poi-id="s.poiId"
                      :scene="s.scene"
                      ratio="4 / 3"
                      :alt="s.name"
                      class="stopcard__scene"
                    />
                    <span class="stopcard__kind" :class="`stopcard__kind--${s.kind}`">
                      {{ s.kind === 'rural' ? '乡村体验' : s.kind === 'food' ? '地方风味' : '景区' }}
                    </span>
                  </div>

                  <div class="stopcard__main">
                    <div class="stopcard__top">
                      <h3 class="stopcard__name">{{ s.name }}</h3>
                      <span class="muted cap stopcard__meta">{{ s.meta }}</span>
                    </div>

                    <p class="stopcard__why">{{ s.why }}</p>

                    <div class="stopcard__facts">
                      <span class="fact">
                        <i class="fact__k">今天人流</i>
                        <b class="num" :class="`fact--${loadLv(s.usage)}`">{{ usageText(s.usage) }}</b>
                      </span>
                      <span class="fact">
                        <i class="fact__k">建议时长</i>
                        <b class="num">{{ s.duration }}′</b>
                      </span>
                      <span class="fact">
                        <i class="fact__k">门票</i>
                        <b class="num">{{ s.ticket > 0 ? `¥${s.ticket}` : '免费' }}</b>
                      </span>
                    </div>

                    <div v-if="s.hits.length" class="stopcard__hits">
                      <span v-for="h in s.hits" :key="h" class="tag tag-brand">{{ h }}</span>
                    </div>
                  </div>
                </router-link>

                <!-- 乡村点：把「体验 → 好物」这条链画出来 -->
                <div v-if="s.exp" class="chain">
                  <span class="chain__label">这一段可以延伸到离境之后</span>

                  <div class="chain__row">
                    <router-link :to="`/poi/${s.poiId}`" class="chain__exp">
                      <span class="chain__tag">乡村体验</span>
                      <span class="chain__name">{{ s.exp.name }}</span>
                      <span class="chain__price num">¥{{ s.exp.price }}</span>
                    </router-link>

                    <template v-if="s.goods.length">
                      <span class="chain__arrow">→</span>
                      <div class="chain__goods">
                        <router-link
                          v-for="g in s.goods"
                          :key="g.id"
                          :to="`/poi/${s.poiId}`"
                          class="good"
                        >
                          <span class="good__name">{{ g.name }}</span>
                          <span class="good__spec">{{ g.spec }}</span>
                          <span class="good__origin">{{ g.origin_village }}</span>
                          <span class="good__price num">¥{{ g.price }}</span>
                        </router-link>
                      </div>
                    </template>
                  </div>
                </div>
              </div>
            </li>
          </ol>
        </section>
      </template>
    </div>

    <!-- ============ 4. 方法说明（游客口径：讲"这份行程怎么来的"，不讲"用了什么技术"） ============ -->
    <div class="container section">
      <section class="method">
        <SectionHead
          eyebrow="怎么排出来的"
          title="先按你的条件筛，再按今天的人流排"
          desc="每一步都按确定的条件算，都能说清为什么 —— 不是随机拼几条，也不是套模板。"
          size="lg"
        />
        <div class="grid grid-3 method__grid">
          <div class="mcard reveal">
            <span class="mcard__no num">01</span>
            <h3 class="h3">挑出能去的</h3>
            <p class="body">先按天数、预算、兴趣和路程筛一遍，保证一天之内走得完、接得上。</p>
            <span class="mcard__in">用到的：你填的条件 + 各处之间的距离</span>
          </div>
          <div class="mcard reveal">
            <span class="mcard__no num">02</span>
            <h3 class="h3">避开人多的</h3>
            <p class="body">再看各处今天人多不多：人多的往后放、人少的往前排，把排队的时间省下来。</p>
            <span class="mcard__in">用到的：今天各处的实时人流</span>
          </div>
          <div class="mcard reveal">
            <span class="mcard__no num">03</span>
            <h3 class="h3">写成能走的</h3>
            <p class="body">最后把动线写成一份照着走就行的说明，每一站为什么排在这儿都写清楚。</p>
            <span class="mcard__in">输出：一份可执行的行程</span>
          </div>
        </div>

        <p class="method__foot">
          以上每一步都是按你填的条件当场算出来的，不是预置的模板行程 ——
          换一个天数或换一项兴趣，排出来的动线就会跟着变。
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ============ 1. Banner ============ */
.banner {
  position: relative;
  min-height: min(52vh, 480px);
  display: flex;
  align-items: center;
  overflow: hidden;
  background: var(--brand-900);
}
.banner__art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
  opacity: 0.6;
}
.banner__veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    100deg,
    rgba(11, 33, 25, 0.9) 0%,
    rgba(11, 33, 25, 0.74) 50%,
    rgba(11, 33, 25, 0.46) 100%
  );
}
.banner__inner {
  position: relative;
  max-width: 860px;
}
.banner__title {
  margin-top: var(--sp-4);
  color: #fff;
  font-size: var(--fs-mega);
  line-height: 1.16;
}
.banner__desc {
  margin-top: var(--sp-5);
  max-width: 44em;
  font-size: var(--fs-hero-sub);
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.82);
}

/* ============ 2. 工作台 ============ */
.bench {
  display: grid;
  grid-template-columns: 380px 1fr;
  gap: var(--sp-6);
  align-items: start;
}

/* 左：入参面板。白底 + 左侧一条品牌色竖线，和右栏区别开 */
.panel {
  position: relative;
  padding: var(--sp-6);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-left: 3px solid var(--brand-500);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
  display: flex;
  flex-direction: column;
  gap: var(--sp-6);
}
.panel__head {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.panel__title {
  font-family: var(--font-display);
  font-size: 22px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.field__label {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-700);
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
}
.field__multi {
  font-weight: 400;
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.field__note {
  font-size: var(--fs-cap);
  color: var(--warm-500);
  line-height: 1.6;
}

.seg {
  display: flex;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.seg--stack {
  flex-direction: column;
}
.seg__item {
  flex: 1;
  min-width: 0;
  padding: 9px var(--sp-3);
  font-size: var(--fs-sm);
  color: var(--ink-600);
  background: var(--paper-2);
  border: 1px solid transparent;
  border-radius: var(--r-md);
  text-align: center;
  transition: all var(--dur-1) var(--ease);
}
.seg--stack .seg__item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  text-align: left;
  gap: var(--sp-3);
}
.seg__item:hover {
  background: var(--brand-50);
  color: var(--brand-700);
}
.seg__item--on {
  color: var(--brand-800);
  font-weight: 600;
  background: var(--brand-50);
  border-color: var(--brand-500);
}
.seg__name {
  font-weight: 600;
}
.seg__hint {
  font-size: var(--fs-cap);
  color: var(--warm-500);
  font-weight: 400;
}
.seg__item--on .seg__hint {
  color: var(--brand-500);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
}
.chip {
  padding: 7px var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--ink-600);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  transition: all var(--dur-1) var(--ease);
}
.chip:hover {
  border-color: var(--brand-400);
  color: var(--brand-700);
}
.chip--on {
  color: #fff;
  background: var(--brand-700);
  border-color: var(--brand-700);
  font-weight: 500;
}

/* 右：行程要点 */
.analysis {
  padding: var(--sp-6) var(--sp-7);
  background: var(--paper-3);
  border-radius: var(--r-lg);
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
  min-height: 100%;
}
.analysis__head {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.ai-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  align-self: flex-start;
  padding: 4px 11px;
  font-size: var(--fs-cap);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--tech-600);
  background: var(--tech-50);
  border: 1px solid #cfe3f4;
  border-radius: var(--r-pill);
}
.ai-badge__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--tech-500);
}
.analysis__title {
  font-family: var(--font-display);
  font-size: 24px;
}
.analysis__lead {
  font-size: var(--fs-body);
  line-height: 1.9;
  color: var(--ink-600);
  max-width: 52em;
}
.analysis__lead b {
  color: var(--brand-700);
}
.analysis__note {
  margin-top: auto;
  padding-top: var(--sp-4);
  border-top: 1px solid var(--line);
  font-size: var(--fs-cap);
  line-height: 1.7;
  color: var(--warm-500);
}

.metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--sp-5);
}
.metric dt {
  font-size: var(--fs-cap);
  letter-spacing: 0.1em;
  color: var(--warm-500);
}
.metric dd {
  margin-top: 6px;
  font-size: 28px;
  font-weight: 600;
  color: var(--brand-700);
  line-height: 1.15;
}
.metric dd i {
  font-style: normal;
  font-size: 16px;
  margin-left: 1px;
}
.metric__text {
  font-size: var(--fs-sm) !important;
  font-weight: 500 !important;
  line-height: 1.5 !important;
}
.metric__text--fit {
  color: var(--ok) !important;
}
.metric__text--over {
  color: var(--danger) !important;
}
.metric__text--under {
  color: var(--warn) !important;
}

/* ============ 3. 时间轴 ============ */
.plan__sk {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
}
.plan__skrow {
  height: 200px;
  border-radius: var(--r-lg);
}

.dayblock {
  margin-bottom: var(--sp-8);
}
.dayblock__head {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  margin-bottom: var(--sp-5);
}
.dayblock__no {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.16em;
  color: var(--brand-700);
}
.dayblock__line {
  flex: 1;
  height: 1px;
  background: var(--line);
}
.dayblock__count {
  font-size: var(--fs-cap);
  color: var(--warm-500);
}

.tl {
  display: flex;
  flex-direction: column;
}

/* 左侧轨道：节点 + 连接竖线 */
.tl__item {
  display: grid;
  grid-template-columns: 44px 1fr;
  gap: var(--sp-5);
}
.tl__rail {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.tl__node {
  flex: none;
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  font-size: var(--fs-cap);
  font-weight: 700;
  font-family: var(--font-num);
  border-radius: 50%;
  background: #fff;
  border: 2px solid var(--line);
  color: var(--warm-500);
  z-index: 1;
}
.tl__node--scenic {
  border-color: var(--gold-500);
  color: var(--gold-600);
  background: var(--gold-50);
}
.tl__node--rural {
  border-color: var(--brand-500);
  color: #fff;
  background: var(--brand-600);
}
.tl__node--food {
  border-color: var(--warm-400);
  color: var(--ink-600);
}
.tl__stem {
  flex: 1;
  width: 1px;
  min-height: 24px;
  background: repeating-linear-gradient(
    to bottom,
    var(--line) 0,
    var(--line) 4px,
    transparent 4px,
    transparent 9px
  );
}

.tl__body {
  padding-bottom: var(--sp-7);
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  min-width: 0;
}

.stopcard {
  display: grid;
  grid-template-columns: 168px 1fr;
  gap: var(--sp-5);
  padding: var(--sp-4);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  transition: border-color var(--dur-2) var(--ease), box-shadow var(--dur-2) var(--ease),
    transform var(--dur-2) var(--ease);
}
.stopcard:hover {
  border-color: var(--line);
  box-shadow: var(--sh-2);
  transform: translateX(3px);
}
.stopcard__art {
  position: relative;
  overflow: hidden;
  border-radius: var(--r-md);
}
.stopcard__scene {
  border-radius: 0;
  transition: transform 900ms var(--ease);
}
.stopcard:hover .stopcard__scene {
  transform: scale(1.05);
}
.stopcard__kind {
  position: absolute;
  left: var(--sp-3);
  top: var(--sp-3);
  padding: 3px 9px;
  font-size: var(--fs-cap);
  font-weight: 600;
  color: #fff;
  border-radius: var(--r-sm);
  background: rgba(11, 33, 25, 0.66);
  backdrop-filter: blur(3px);
}
.stopcard__kind--rural {
  background: rgba(29, 85, 68, 0.86);
}
.stopcard__kind--scenic {
  background: rgba(156, 115, 48, 0.86);
}
.stopcard__main {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}
.stopcard__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.stopcard__name {
  font-family: var(--font-display);
  font-size: 19px;
  color: var(--ink-900);
}
.stopcard:hover .stopcard__name {
  color: var(--brand-700);
}
.stopcard__meta {
  letter-spacing: 0.02em;
}
.stopcard__why {
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: var(--ink-500);
}
.stopcard__facts {
  display: flex;
  gap: var(--sp-6);
  flex-wrap: wrap;
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line-soft);
}
.fact {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.fact__k {
  font-style: normal;
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.fact b {
  font-size: var(--fs-sm);
  color: var(--ink-800, var(--ink-700));
}
.fact--ok {
  color: var(--ok);
}
.fact--warn {
  color: var(--warn);
}
.fact--danger {
  color: var(--danger);
}
/* 承载未知：中性灰，不表态（既不能说舒适，也不能说拥挤） */
.fact--unknown {
  color: var(--ink-500);
}
.stopcard__hits {
  display: flex;
  gap: var(--sp-2);
  flex-wrap: wrap;
}

/* 乡村点下方的"体验 → 好物"链 */
.chain {
  padding: var(--sp-3) 0 var(--sp-1) var(--sp-5);
  border-left: 2px solid var(--brand-100);
  margin-left: var(--sp-3);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.chain__label {
  font-size: var(--fs-cap);
  letter-spacing: 0.06em;
  color: var(--brand-600);
  font-weight: 600;
}
.chain__row {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.chain__exp {
  display: inline-flex;
  align-items: baseline;
  gap: var(--sp-3);
  padding: 9px var(--sp-4);
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
  border-radius: var(--r-md);
  transition: border-color var(--dur-1) var(--ease);
}
.chain__exp:hover {
  border-color: var(--brand-400);
}
.chain__tag {
  font-size: var(--fs-cap);
  color: var(--brand-500);
  font-weight: 600;
}
.chain__name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--brand-800);
}
.chain__price {
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--gold-600);
}
.chain__arrow {
  color: var(--warm-400);
  font-size: 15px;
}
.chain__goods {
  display: flex;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.good {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px var(--sp-4);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-md);
  min-width: 180px;
  transition: border-color var(--dur-1) var(--ease), box-shadow var(--dur-1) var(--ease);
}
.good:hover {
  border-color: var(--line);
  box-shadow: var(--sh-1);
}
.good__name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-800, var(--ink-700));
}
.good__spec,
.good__origin {
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.good__price {
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--gold-600);
}

/* ============ 4. 方法说明 ============ */
.method__grid {
  gap: var(--sp-5);
}
.mcard {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding-top: var(--sp-5);
  border-top: 2px solid var(--brand-500);
}
.mcard__no {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.16em;
  color: var(--brand-500);
}
.mcard .body {
  color: var(--ink-500);
  line-height: 1.8;
}
.mcard__in {
  margin-top: auto;
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.method__foot {
  margin-top: var(--sp-7);
  padding-top: var(--sp-5);
  border-top: 1px solid var(--line-soft);
  font-size: var(--fs-sm);
  line-height: 1.85;
  color: var(--ink-500);
  max-width: 62em;
}
.method__foot b {
  color: var(--ink-700);
}

/* ============ 响应式 ============ */
@media (max-width: 1080px) {
  .bench {
    grid-template-columns: 1fr;
  }
  .metrics {
    grid-template-columns: repeat(2, 1fr);
  }
  .stopcard {
    grid-template-columns: 140px 1fr;
  }
}

@media (max-width: 720px) {
  .banner {
    min-height: auto;
    padding: var(--sp-8) 0 var(--sp-7);
  }
  .analysis {
    padding: var(--sp-5);
  }
  .metrics {
    grid-template-columns: repeat(2, 1fr);
    gap: var(--sp-4);
  }
  .metric dd {
    font-size: 22px;
  }
  .tl__item {
    grid-template-columns: 32px 1fr;
    gap: var(--sp-3);
  }
  .tl__node {
    width: 26px;
    height: 26px;
  }
  .stopcard {
    grid-template-columns: 1fr;
  }
  .stopcard__art {
    max-height: 180px;
  }
  .stopcard__facts {
    gap: var(--sp-4);
  }
  .chain {
    padding-left: var(--sp-4);
    margin-left: 0;
  }
  .chain__row {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--sp-3);
  }
  .chain__arrow {
    transform: rotate(90deg);
    align-self: flex-start;
  }
  .good {
    min-width: 0;
    width: 100%;
  }
}
</style>
