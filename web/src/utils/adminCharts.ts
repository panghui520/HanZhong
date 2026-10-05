/**
 * 管理端图表统一样式与常用 option 构造器（2026-10-04）
 *
 * 为什么单独抽一个文件：
 *
 * 重构后 5 个业务页面（驾驶舱 / 农产品 / 景点 / 餐饮 / 住宿）加起来要画 20+ 张图。
 * 如果每张图各写一遍 `axisLabel: { color: '#8aa398' }`，会出现两个后果：
 *   1. 改配色要改 20 处，漏一处不会报错，只会有一张图的轴线比别的亮一点；
 *   2. 图表会长得**一模一样** —— 全是"默认柱状图换了个颜色"，这正是要避免的
 *      "看起来像 Vue 表格后台"。
 *
 * 所以这里做两件事：
 *   · 定死**一套**主题常量（深绿 + 金，轴线与网格线弱化，动画关掉）；
 *   · 提供**形状不同**的构造器 —— 横向条 / 趋势面 / 双系列柱 / 环 / 散点 / 分布条，
 *     每个页面按"这张图要回答什么问题"挑形状，而不是全用 bar。
 *
 * 单位与格式统一走 `fmtInt` / `fmtMoney` / `fmtPct`，与后端 `OpsBusinessVO`
 * 里 `num()` / `money()` / `percent()` 的输出格式对齐 —— 同一屏上"¥49,717.50"
 * 和"¥49717.5"混着出现会显得是两个系统。
 */

import type { OpsBusinessRow } from '@/types'

/** 深色主题下的轴线 / 刻度：都压到很淡，让数据本身成为视觉重心 */
export const ADM_AXIS = {
  axisLine: { lineStyle: { color: 'rgba(146,178,165,0.22)' } },
  axisTick: { show: false },
  axisLabel: { color: '#8aa398', fontSize: 11 },
  splitLine: { lineStyle: { color: 'rgba(146,178,165,0.10)' } },
}

/** tooltip：深底 + 细金边，字号比正文小一档 */
export const ADM_TOOLTIP = {
  backgroundColor: 'rgba(11,33,25,0.94)',
  borderColor: 'rgba(146,178,165,0.28)',
  textStyle: { color: '#eef3f0', fontSize: 12 },
  padding: [8, 12],
}

/** 图例：只在真的需要区分多系列时才出现，且永远很小 */
export const ADM_LEGEND = {
  textStyle: { color: '#8aa398', fontSize: 11 },
  itemWidth: 10,
  itemHeight: 6,
}

/**
 * 调色板。深绿为主、金为强调、科技蓝只做第三色。
 *
 * 顺序是刻意的：第一个颜色用得最多（主图），越往后越少。
 * 蓝色排第三而不是第二 —— 深绿+金是本项目的品牌对，
 * 蓝色一多整个页面就偏"通用 BI 大屏"了。
 */
export const ADM_PALETTE = [
  '#2a6f5b',
  '#c09a4e',
  '#2e7bc4',
  '#71a996',
  '#3d8b74',
  '#e2ca91',
  '#8aa398',
  '#1d5544',
]

/** 单色梯度：给"按数值深浅"的分布图用，不引入新色相 */
export const ADM_RAMP = ['#1d5544', '#2a6f5b', '#3d8b74', '#71a996', '#a8c8ba']

/** 关掉花哨动画：管理端看的是数字，不是过场 */
const NO_ANIM = { animationDuration: 320, animationEasing: 'cubicOut' as const }

export function fmtInt(n: number): string {
  return Number(n ?? 0).toLocaleString('zh-CN')
}

export function fmtMoney(n: number): string {
  return `¥${Number(n ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

export function fmtPct(ratio: number, digits = 1): string {
  return `${(Number(ratio ?? 0) * 100).toFixed(digits)}%`
}

/** 点击回调统一签名：拿到"点了哪一项"，交给页面决定高亮谁 */
export interface ChartPick {
  name: string
  value: number
  id?: string
  index: number
}

/**
 * 横向条形（排行）。**用它而不是竖柱做排行** ——
 * 中文名称长（"留坝县火烧店镇中西沟村"），竖柱的 x 轴标签必然斜排或截断。
 *
 * @param money 值是否为金额（决定 tooltip 与标签的格式）
 */
export function hBarOption(
  rows: OpsBusinessRow[],
  opts: { color?: string; money?: boolean; unit?: string; max?: number } = {}
) {
  const color = opts.color ?? '#3d8b74'
  const fmt = opts.money ? fmtMoney : fmtInt
  // 从下往上画（ECharts 的 y 轴类目是从下往上），所以先反转 ——
  // 不反转的话"第一名"会出现在图的最下面，读起来反直觉
  const data = [...rows].reverse()
  return {
    ...NO_ANIM,
    grid: { left: 8, right: 56, top: 8, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'item',
      ...ADM_TOOLTIP,
      formatter: (p: any) =>
        `${p.name}<br/><b style="color:#e2ca91">${fmt(p.value)}</b>${opts.unit ?? ''}`,
    },
    xAxis: {
      type: 'value',
      max: opts.max,
      ...ADM_AXIS,
      axisLabel: { show: false },
      splitLine: { show: false },
      axisLine: { show: false },
    },
    yAxis: {
      type: 'category',
      data: data.map((r) => r.name),
      ...ADM_AXIS,
      axisLine: { show: false },
      splitLine: { show: false },
      axisLabel: { ...ADM_AXIS.axisLabel, fontSize: 11 },
    },
    series: [
      {
        type: 'bar',
        data: data.map((r) => ({ value: r.value, name: r.name, id: r.id })),
        barWidth: 11,
        // 点击选中：选中的那条变成金色。ECharts 自己管选中态，
        // 回调只用来换旁边的详情文案，不重绘整张图
        selectedMode: 'single',
        select: { itemStyle: { color: '#e0c07a' } },
        itemStyle: { color, borderRadius: [0, 3, 3, 0] },
        // 数值直接显示在图右侧 —— 管理端不想为了读一个数字去 hover
        label: {
          show: true,
          position: 'right',
          color: '#8aa398',
          fontSize: 11,
          formatter: (p: any) => fmt(p.value),
        },
      },
    ],
  }
}

/**
 * 趋势（线 + 面）。面积用渐变而不是纯色块：纯色块在深绿底上会糊成一块，
 * 渐变能保住"这是一条趋势"的读法。
 */
export function areaLineOption(
  points: { date: string; value: number }[],
  opts: { color?: string; money?: boolean; unit?: string } = {}
) {
  const color = opts.color ?? '#c09a4e'
  const fmt = opts.money ? fmtMoney : fmtInt
  return {
    ...NO_ANIM,
    grid: { left: 8, right: 12, top: 16, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      ...ADM_TOOLTIP,
      axisPointer: { type: 'line', lineStyle: { color: 'rgba(192,154,78,0.4)' } },
      formatter: (ps: any[]) => {
        const p = ps[0]
        return `${p.axisValue}<br/><b style="color:#e2ca91">${fmt(p.value)}</b>${opts.unit ?? ''}`
      },
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: points.map((p) => p.date),
      ...ADM_AXIS,
      splitLine: { show: false },
    },
    yAxis: { type: 'value', ...ADM_AXIS },
    series: [
      {
        type: 'line',
        data: points.map((p) => p.value),
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        // 只在 hover 时显数值点，常态保持线条干净
        showSymbol: points.length <= 14,
        lineStyle: { color, width: 2 },
        itemStyle: { color },
        emphasis: { focus: 'series', scale: 1.6 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: `${color}55` },
              { offset: 1, color: `${color}00` },
            ],
          },
        },
      },
    ],
  }
}

/**
 * 双系列柱（对比）。用它做"购买 vs 复购"这类**同一量纲、要看出比例**的对比。
 * 两根柱并排而不是堆叠 —— 堆叠会把"复购占比"读成"总高度"，是错的读法。
 */
export function groupBarOption(
  names: string[],
  series: { name: string; values: number[]; color: string }[],
  opts: { unit?: string } = {}
) {
  const barW = Math.max(6, Math.min(16, Math.floor(120 / Math.max(1, names.length))))
  return {
    ...NO_ANIM,
    grid: { left: 8, right: 12, top: 28, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      ...ADM_TOOLTIP,
      // 把单位拼进数值后面，否则"3"是 3 笔还是 3 元要靠读图的人自己猜
      valueFormatter: (v: any) => `${fmtInt(Number(v ?? 0))}${opts.unit ?? ''}`,
    },
    legend: { right: 0, top: 0, ...ADM_LEGEND },
    xAxis: { type: 'category', data: names, ...ADM_AXIS, splitLine: { show: false } },
    yAxis: { type: 'value', ...ADM_AXIS },
    series: series.map((s) => ({
      name: s.name,
      type: 'bar',
      data: s.values,
      barWidth: barW,
      barGap: '18%',
      selectedMode: 'single',
      select: { itemStyle: { color: '#e0c07a' } },
      itemStyle: { color: s.color, borderRadius: [3, 3, 0, 0] },
      emphasis: { focus: 'series' },
    })),
  }
}

/**
 * 环（构成）。中心放总数 —— 环的空心位置不用白不用，
 * 比在旁边再写一行"合计：xxx"省一块地方。
 */
export function doughnutOption(
  rows: OpsBusinessRow[],
  opts: { centerLabel?: string; money?: boolean; unit?: string } = {}
) {
  const fmt = opts.money ? fmtMoney : fmtInt
  const total = rows.reduce((s, r) => s + r.value, 0)
  return {
    ...NO_ANIM,
    tooltip: {
      trigger: 'item',
      ...ADM_TOOLTIP,
      formatter: (p: any) =>
        `${p.name}<br/><b style="color:#e2ca91">${fmt(p.value)}</b> · ${p.percent}%`,
    },
    legend: {
      bottom: 0,
      ...ADM_LEGEND,
      // 分类多的时候图例横排会挤成两行，改成竖排更稳
      orient: rows.length > 6 ? 'vertical' : 'horizontal',
      right: rows.length > 6 ? 0 : undefined,
      top: rows.length > 6 ? 'middle' : undefined,
    },
    series: [
      {
        type: 'pie',
        radius: ['50%', '72%'],
        center: rows.length > 6 ? ['38%', '46%'] : ['50%', '44%'],
        avoidLabelOverlap: true,
        // 点击选中：扇形向外挪 6px。默认 10px 在这个半径下会顶到图例
        selectedMode: 'single',
        selectedOffset: 6,
        select: { itemStyle: { borderColor: '#e0c07a', borderWidth: 3 } },
        itemStyle: { borderColor: '#123026', borderWidth: 2 },
        label: {
          show: true,
          position: 'center',
          formatter: () =>
            rows.length
              ? `{v|${fmt(total)}}\n{l|${opts.centerLabel ?? '合计'}}`
              : '{l|暂无数据}',
          rich: {
            v: { color: '#ffffff', fontSize: 18, fontWeight: 700, lineHeight: 24 },
            l: { color: '#8aa398', fontSize: 11 },
          },
        },
        labelLine: { show: false },
        data: rows.map((r, i) => ({
          name: r.name,
          value: r.value,
          id: r.id,
          itemStyle: { color: ADM_PALETTE[i % ADM_PALETTE.length] },
        })),
      },
    ],
  }
}

/**
 * 冷热分布散点（X = 客流，Y = 平均承载率，气泡大小 = 客流）。
 *
 * 这是景点页的**视觉主角**：它一眼回答"哪些点人不多但已经很挤"
 * （左上角 —— 低客流、高承载 = 承载力是瓶颈），
 * 而单看排行或单看承载率表都答不出这个问题。
 *
 * 参考线画在 80%（`HIGH_USAGE`，与后端规则引擎同一阈值）——
 * 线以上即"高负荷资源"，与 KPI 里的口径是同一个数。
 */
export function scatterOption(
  points: { id: string; name: string; district?: string; x: number; y: number; size: number }[],
  opts: { highLine?: number } = {}
) {
  const high = opts.highLine ?? 0.8
  return {
    ...NO_ANIM,
    grid: { left: 8, right: 20, top: 20, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'item',
      ...ADM_TOOLTIP,
      formatter: (p: any) => {
        const d = p.data
        return `${d.name}<br/>${d.district ?? ''}<br/>到访 <b>${fmtInt(d.x)}</b> 人次<br/>平均承载率 <b style="color:#e2ca91">${fmtPct(d.y)}</b>`
      },
    },
    xAxis: {
      type: 'value',
      name: '窗口到访（人次）',
      nameTextStyle: { color: '#8aa398', fontSize: 11 },
      nameGap: 24,
      ...ADM_AXIS,
    },
    yAxis: {
      type: 'value',
      name: '平均承载率',
      nameTextStyle: { color: '#8aa398', fontSize: 11 },
      min: 0,
      ...ADM_AXIS,
      axisLabel: { ...ADM_AXIS.axisLabel, formatter: (v: number) => `${Math.round(v * 100)}%` },
    },
    series: [
      {
        type: 'scatter',
        // 气泡大小按客流开方后再缩，否则头部景区会把其余点压成一个像素
        symbolSize: (v: any) => Math.max(10, Math.min(38, Math.sqrt(Number(v?.[0] ?? 0)) / 9)),
        data: points.map((p) => ({
          name: p.name,
          id: p.id,
          district: p.district,
          value: [p.x, p.y],
          // 高负荷的点直接染成暖色 —— 不点也能一眼看出是哪几个
          itemStyle: {
            color: p.y >= high ? 'rgba(224,192,122,0.85)' : 'rgba(61,139,116,0.72)',
            borderColor: p.y >= high ? '#e0c07a' : 'rgba(113,169,150,0.8)',
            borderWidth: 1,
          },
        })),
        emphasis: {
          focus: 'self',
          itemStyle: { borderColor: '#fff', borderWidth: 2, shadowBlur: 12, shadowColor: 'rgba(224,192,122,0.6)' },
        },
        selectedMode: 'single',
        select: { itemStyle: { color: '#e0c07a', borderColor: '#fff', borderWidth: 2 } },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: 'rgba(224,192,122,0.45)', type: 'dashed', width: 1 },
          label: {
            formatter: `高负荷线 ${Math.round(high * 100)}%`,
            color: '#e0c07a',
            fontSize: 10,
            position: 'insideEndTop',
          },
          data: [{ yAxis: high }],
        },
      },
    ],
  }
}

/**
 * 贡献占比（100% 堆叠单条）。
 *
 * 与「销售额排行」的区别是刻意的，也是这两张图必须同时存在的原因：
 *   · 排行回答"谁卖得多"—— 看的是**绝对值**，头部会吃掉全部注意力；
 *   · 贡献占比回答"这块蛋糕怎么分的"—— 看的是**份额**，
 *     十个产地挤在一条上，谁占多少一眼就分得出。
 * 如果两张都用横向条，第二张就是第一张的复读。
 */
export function shareStackOption(rows: OpsBusinessRow[], opts: { money?: boolean } = {}) {
  const fmt = opts.money ? fmtMoney : fmtInt
  const total = rows.reduce((s, r) => s + r.value, 0) || 1
  return {
    ...NO_ANIM,
    grid: { left: 8, right: 8, top: 10, bottom: 44, containLabel: true },
    tooltip: {
      trigger: 'item',
      ...ADM_TOOLTIP,
      formatter: (p: any) => {
        const row = rows.find((r) => r.name === p.seriesName)
        return `${p.seriesName}<br/><b style="color:#e2ca91">${fmt(row?.value ?? 0)}</b><br/>占比 ${p.value}%`
      },
    },
    legend: { bottom: 0, ...ADM_LEGEND },
    xAxis: {
      type: 'value',
      max: 100,
      ...ADM_AXIS,
      axisLabel: { ...ADM_AXIS.axisLabel, formatter: '{value}%' },
    },
    yAxis: { type: 'category', data: ['销售贡献'], ...ADM_AXIS, axisLine: { show: false }, axisLabel: { show: false } },
    series: rows.map((r, i) => {
      const pct = (r.value / total) * 100
      return {
        name: r.name,
        type: 'bar',
        stack: 'share',
        barWidth: 38,
        data: [Number(pct.toFixed(2))],
        itemStyle: { color: ADM_PALETTE[i % ADM_PALETTE.length] },
        // 份额太小的段写不下标签，强行写会互相压住 —— 8% 是实测能放下两位数的下限
        label: {
          show: pct >= 8,
          position: 'inside',
          color: '#0b2119',
          fontSize: 11,
          fontWeight: 600,
          formatter: () => `${r.name} ${pct.toFixed(0)}%`,
        },
        emphasis: { focus: 'self' },
        selectedMode: 'single',
        select: { itemStyle: { borderColor: '#e0c07a', borderWidth: 2 } },
      }
    }),
  }
}

/** 分布条（承载档 / 分类构成）。横向 + 数值直显，适合 3~10 档 */
export function distributionBarOption(
  rows: OpsBusinessRow[],
  opts: { colors?: string[]; unit?: string } = {}
) {
  const colors = opts.colors ?? ADM_RAMP
  return {
    ...NO_ANIM,
    grid: { left: 8, right: 48, top: 8, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'item',
      ...ADM_TOOLTIP,
      formatter: (p: any) => `${p.name}<br/><b style="color:#e2ca91">${fmtInt(p.value)}</b>${opts.unit ?? ''}`,
    },
    xAxis: { type: 'value', ...ADM_AXIS, axisLabel: { show: false }, splitLine: { show: false }, axisLine: { show: false } },
    yAxis: {
      type: 'category',
      data: [...rows].reverse().map((r) => r.name),
      ...ADM_AXIS,
      axisLine: { show: false },
      splitLine: { show: false },
    },
    series: [
      {
        type: 'bar',
        data: [...rows].reverse().map((r, i) => ({
          value: r.value,
          name: r.name,
          itemStyle: { color: colors[i % colors.length] },
        })),
        barWidth: 14,
        selectedMode: 'single',
        select: { itemStyle: { color: '#e0c07a' } },
        label: {
          show: true,
          position: 'right',
          color: '#8aa398',
          fontSize: 11,
          formatter: (p: any) => fmtInt(p.value),
        },
      },
    ],
  }
}
