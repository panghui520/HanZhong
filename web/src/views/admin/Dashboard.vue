<script setup lang="ts">
import { computed } from 'vue'
import EChart from '@/components/EChart.vue'
import { getCityPack } from '@/api/citypack'
import { useAsync } from '@/composables/useAsync'
import { buildOpsSnapshot } from '@/mock/ops'

const { data, loading, error, reload } = useAsync(getCityPack)

/** 风险类型的中文标注（不要把枚举值直接摆在界面上） */
const RISK_TYPE_LABEL: Record<string, string> = {
  OVERLOAD: '客流超载',
  RURAL_IDLE: '乡村闲置',
  REVIEW: '差评激增',
  CONVERSION: '转化偏低',
}

const ops = computed(() => {
  if (!data.value) return null
  return buildOpsSnapshot(data.value.pois, data.value.products)
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
      data: ['全市到访', '承载占用率'],
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
        name: '全市到访',
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
  const rows = [...o.productTop].reverse()
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
  return [
    {
      label: '今日全市到访',
      value: o.totalVisitors.toLocaleString(),
      unit: '人次',
      delta: `${signed(o.deltas.visitorsPct, '%')} 环比`,
      up: o.deltas.visitorsPct >= 0,
    },
    {
      label: '乡村到访占比',
      value: (o.ruralRatio * 100).toFixed(1),
      unit: '%',
      delta: `${signed(o.deltas.ruralRatioPt, 'pt')} 环比`,
      up: o.deltas.ruralRatioPt >= 0,
    },
    {
      label: '农产品销售额',
      value: `¥${(o.productSales / 10000).toFixed(1)}`,
      unit: '万元',
      delta: `${signed(o.deltas.salesPct, '%')} 环比`,
      up: o.deltas.salesPct >= 0,
    },
    {
      label: '离境复购率',
      value: (o.repurchaseRate * 100).toFixed(1),
      unit: '%',
      delta: `${signed(o.deltas.repurchasePt, 'pt')} 环比`,
      up: o.deltas.repurchasePt >= 0,
    },
    {
      label: '待处置风险',
      value: String(o.openRisks),
      unit: '件',
      delta: o.openRisks > 0 ? '需处置' : '运行正常',
      up: false,
      alert: o.openRisks > 0,
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
        <span class="dash__time">数据周期：近 7 日（仿真）</span>
        <span class="badge-sim">SIMULATED</span>
      </div>
    </header>

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
            <span class="muted small">柱：全市到访 · 线：承载占用率</span>
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
            <h2 class="h3 panel__title">乡村好物销售额 Top 6</h2>
            <span class="muted small">体验驱动</span>
          </div>
          <EChart :option="productOption" height="260px" />
        </section>
      </div>

      <!-- 风险与 AI 归因 -->
      <div class="grid grid-3 dash__row">
        <section class="panel panel--span2">
          <div class="panel__head">
            <h2 class="h3 panel__title">风险事件（规则引擎判定）</h2>
            <span class="muted small">{{ ops.openRisks }} 件待处置</span>
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
                <div class="risk__title">{{ r.title }} · {{ r.poi }}</div>
                <div class="risk__detail">{{ r.detail }}</div>
              </div>
              <span class="risk__type">{{ RISK_TYPE_LABEL[r.type] ?? r.type }}</span>
            </li>
          </ul>
        </section>

        <section class="panel panel--ai">
          <div class="panel__head">
            <h2 class="h3 panel__title">AI 运营建议</h2>
            <span class="tag tag-gold">规则 + LLM</span>
          </div>
          <p class="ai__text">
            近 7 日全市到访持续抬升，周末峰值已使
            <b>{{ ops.hotScenicCount }} 处核心景区</b>进入高位运行；
            同期全市乡村点平均承载仅
            <b>{{ Math.round(ops.ruralAvgUsage * 100) }}%</b><template
              v-if="ops.idleDistricts.length"
              >，其中 <b>{{ ops.idleDistricts.join('、') }}</b> 的景区高位与乡村低位并存</template
            >，存在明显可承接空间。
          </p>
          <p class="ai__text">
            建议：对高位景区启动分时预约，并生成
            <b>乡村分流工单</b>，将乡村点在行程规划中的推荐权重上调；
            同步把茶园采制、稻田体验与对应产品打包曝光，
            把溢出客流转为乡村体验与产品消费。
          </p>
          <div class="ai__foot">
            <span class="muted small">判定由规则引擎给出，解释与建议由 LLM 生成</span>
            <button class="btn btn-gold btn-sm">生成分流工单</button>
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
.ai__text + .ai__text {
  margin-top: var(--sp-3);
}
.ai__text b {
  color: var(--gold-300);
  font-weight: 600;
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
