<script setup lang="ts">
import { computed, ref } from 'vue'
import EChart from '@/components/EChart.vue'
import { createWorkOrder, getOpsSnapshot } from '@/api/ops'
import { analyzeOps } from '@/api/ai'
import { ApiError } from '@/api/http'
import { useAsync } from '@/composables/useAsync'
import { useNotice } from '@/composables/useNotice'
import { inlineText, when } from '@/utils/format'
import { OPS_FOCUSES, OPS_MODE_LABEL, RISK_TYPE_LABEL, type OpsAnalysis } from '@/types'

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
const { data, loading, error, reload } = useAsync(getOpsSnapshot)

const ops = computed(() => data.value)

const { notice, say } = useNotice()

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
    await reload()
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
    analysis.value = await analyzeOps(key)
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
        barWidth: 18,
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
        itemStyle: { color: '#2e7bc4', borderRadius: [0, 3, 3, 0] },
      },
      {
        name: '乡村承载',
        type: 'bar',
        data: rows.map((r) => r.rural),
        barWidth: 8,
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

/** 带符号的百分比/百分点，保留一位小数 */
function signed(n: number, suffix: string) {
  return `${n >= 0 ? '+' : ''}${n.toFixed(1)}${suffix}`
}

const kpis = computed(() => {
  const o = ops.value
  if (!o) return []
  // 每个 KPI 的标签都带上自己的周期（"今日" / "近 7 日"）与口径。
  //
  // 这里曾经写的是"今日全市到访"，而那个数其实是**当日核心景区**到访人次
  // （不含乡村、餐饮、住宿，也不是独立游客数 —— 是人次）。
  // 一个"全市"就让数字的含义大了一圈，而屏幕上没人能看出来。
  // 详见 OpsSnapshotVO 的字段注释。
  return [
    {
      label: '今日核心景区到访',
      value: o.total_visitors.toLocaleString(),
      unit: '人次',
      delta: `${signed(o.deltas.visitors_pct, '%')} 环比`,
      up: o.deltas.visitors_pct >= 0,
    },
    {
      label: '今日乡村到访占比',
      value: (o.rural_ratio * 100).toFixed(1),
      unit: '%',
      delta: `${signed(o.deltas.rural_ratio_pt, 'pt')} 环比`,
      up: o.deltas.rural_ratio_pt >= 0,
    },
    {
      label: '近 7 日农产品销售额',
      value: `¥${(o.product_sales / 10000).toFixed(1)}`,
      unit: '万元',
      delta: `${signed(o.deltas.sales_pct, '%')} 环比`,
      up: o.deltas.sales_pct >= 0,
    },
    {
      label: '近 7 日离境复购率',
      value: (o.repurchase_rate * 100).toFixed(1),
      unit: '%',
      delta: `${signed(o.deltas.repurchase_pt, 'pt')} 环比`,
      up: o.deltas.repurchase_pt >= 0,
    },
    {
      label: '待处置风险',
      value: String(o.open_risks),
      unit: '件',
      delta: o.open_risks > 0 ? '需处置' : '运行正常',
      up: false,
      alert: o.open_risks > 0,
    },
  ]
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
          周期文案**由后端给**（`period_label`），不在这里拼：
          前端自己写"近 7 日"，后端哪天把窗口改成 14 天，这行字不会跟着变，
          而屏幕上的数字已经变了 —— 这种不一致不会报错，只会一直错下去。
        -->
        <span class="dash__time">数据周期：{{ ops?.period_label ?? '—' }}</span>
        <span class="badge-sim">SIMULATED</span>
      </div>
    </header>

    <!-- 操作提示（建单成功 / 失败）。结构由 useNotice 约定，样式在下方 -->
    <Transition name="notice">
      <div v-if="notice" class="notice" :class="`notice--${notice.type}`">{{ notice.text }}</div>
    </Transition>

    <!-- 加载 -->
    <div v-if="loading" class="dash__loading">
      <div v-for="i in 5" :key="i" class="skeleton" style="height: 96px; border-radius: 10px" />
      <div class="skeleton" style="height: 300px; border-radius: 10px; grid-column: span 2" />
      <div class="skeleton" style="height: 300px; border-radius: 10px" />
    </div>

    <div v-else-if="error" class="state-error">
      <p>{{ error }}</p>
      <button class="btn btn-ghost btn-sm" @click="reload">重新加载</button>
    </div>

    <template v-else-if="ops">
      <!-- KPI -->
      <div class="kpis">
        <div v-for="k in kpis" :key="k.label" class="kpi">
          <span class="kpi__label">{{ k.label }}</span>
          <div class="kpi__row">
            <span class="num kpi__value">{{ k.value }}</span>
            <span class="kpi__unit">{{ k.unit }}</span>
          </div>
          <span class="kpi__delta" :class="{ 'kpi__delta--down': !k.up, 'kpi__delta--alert': k.alert }">
            {{ k.delta }}
          </span>
        </div>
      </div>

      <!-- 趋势 + 业态构成 -->
      <div class="grid grid-3 dash__row">
        <section class="panel panel--span2">
          <div class="panel__head">
            <h2 class="h3 panel__title">客流与承载趋势</h2>
            <span class="muted small">柱：核心景区到访 · 线：承载占用率</span>
          </div>
          <EChart :option="trendOption" height="272px" />
        </section>

        <section class="panel">
          <div class="panel__head">
            <h2 class="h3 panel__title">业态客流构成</h2>
          </div>
          <EChart :option="mixOption" height="272px" />
        </section>
      </div>

      <!-- 冷热失衡 + 农产品 -->
      <div class="grid grid-2 dash__row">
        <section class="panel">
          <div class="panel__head">
            <h2 class="h3 panel__title">冷热失衡：景区高位 vs 乡村闲置</h2>
            <span class="muted small">按区县</span>
          </div>
          <EChart :option="imbalanceOption" height="260px" />
        </section>

        <section class="panel">
          <div class="panel__head">
            <!--
              标题写"按产地归集"，是因为合成数据只支持到**乡村点级**销售额，
              没有单品级的销量 —— 图上每一根柱子是一个产地，不是一款产品。
              不写清的话，看屏的人会以为是单品排行（见 OpsSnapshotVO 的字段注释）。
            -->
            <h2 class="h3 panel__title">乡村好物销售额 Top 6</h2>
            <span class="muted small">按产地归集</span>
          </div>
          <EChart :option="productOption" height="260px" />
        </section>
      </div>

      <!-- 风险与 AI 归因 -->
      <div class="grid grid-3 dash__row">
        <section class="panel panel--span2">
          <div class="panel__head">
            <h2 class="h3 panel__title">风险事件（规则引擎判定）</h2>
            <span class="muted small">{{ ops.open_risks }} 件待处置</span>
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
}
</style>
