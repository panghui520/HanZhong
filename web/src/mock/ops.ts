/**
 * 运营快照（SIMULATED）
 * ------------------------------------------------------------------
 * 前端版“规则引擎”：按与后端一致的规则从资源数据推导运营指标与风险事件。
 * 后端规则引擎上线后，本文件删除，改为读取 /api/ops/snapshot。
 */
import { capacityUsage, todayVisitors } from './stats'
import { seed } from './hash'
import type { Poi, Product } from '@/types'

export interface RiskEvent {
  id: string
  type: 'OVERLOAD' | 'RURAL_IDLE' | 'REVIEW' | 'CONVERSION'
  level: 'HIGH' | 'MID'
  title: string
  poi: string
  detail: string
  suggestion: string
}

export interface OpsSnapshot {
  totalVisitors: number
  ruralVisitors: number
  ruralRatio: number
  productSales: number
  repurchaseRate: number
  openRisks: number
  trend: { date: string; visitors: number; usage: number }[]
  mix: { name: string; value: number }[]
  imbalance: { name: string; scenic: number; rural: number }[]
  productTop: { name: string; sales: number }[]
  risks: RiskEvent[]
  /** 承载 ≥80% 的景区数（AI 建议文案用，避免硬编码与实际数据打架） */
  hotScenicCount: number
  /** 景区高位但乡村闲置的区县名 */
  idleDistricts: string[]
  /** 乡村点平均承载占用率（0–1） */
  ruralAvgUsage: number
  /**
   * 环比变化。由仿真"上期值"反推，不是写死的字符串——
   * 否则界面上会出现"数据变了、涨跌幅不变"的自相矛盾。
   */
  deltas: {
    visitorsPct: number
    ruralRatioPt: number
    salesPct: number
    repurchasePt: number
  }
}

const WEEK = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** 单个产品的仿真销量（件） */
function salesQty(p: Product): number {
  return Math.round(30 + seed(p.id, 'sales') * 220)
}

export function buildOpsSnapshot(pois: Poi[], products: Product[]): OpsSnapshot {
  const scenics = pois.filter((p) => p.business_type === 'SCENIC')
  const rurals = pois.filter((p) => p.business_type === 'RURAL_SPOT')
  const foods = pois.filter((p) => p.business_type === 'FOOD')
  const lodgings = pois.filter((p) => p.business_type === 'LODGING')

  const visit = (p: Poi) => todayVisitors(p.id, p.capacity, p.business_type)
  const totalVisitors = scenics.reduce((s, p) => s + visit(p), 0)
  const ruralVisitors = rurals.reduce((s, p) => s + visit(p), 0)
  const foodVisitors = foods.reduce((s, p) => s + visit(p), 0)
  const lodgeVisitors = lodgings.reduce((s, p) => s + visit(p), 0)

  const productSales = products.reduce((s, p) => s + salesQty(p) * p.price, 0)

  const repurchaseRate = 0.28 + seed('repurchase-rate') * 0.16
  const ruralRatio = ruralVisitors / Math.max(1, ruralVisitors + totalVisitors)

  // 趋势：周末抬升
  const trend = WEEK.map((d, i) => {
    const weekend = i >= 5 ? 1.4 : 1
    const visitors = Math.round((totalVisitors / 6.2) * weekend * (0.9 + seed('trend', i) * 0.22))
    const cap = scenics.reduce((s, p) => s + p.capacity, 0)
    return { date: d, visitors, usage: Number(((visitors / cap) * 100).toFixed(1)) }
  })

  const mix = [
    { name: '核心景区', value: totalVisitors },
    { name: '乡村旅游', value: ruralVisitors },
    { name: '餐饮', value: foodVisitors },
    { name: '住宿', value: lodgeVisitors },
  ]

  // 冷热失衡：按区县的景区承载 vs 乡村承载
  const districts = Array.from(new Set(pois.map((p) => p.district)))
  const imbalance = districts
    .map((d) => {
      const sList = scenics.filter((p) => p.district === d)
      const rList = rurals.filter((p) => p.district === d)
      if (!sList.length || !rList.length) return null
      const su = sList.reduce((a, p) => a + capacityUsage(p.id, p.business_type), 0) / sList.length
      const ru = rList.reduce((a, p) => a + capacityUsage(p.id, p.business_type), 0) / rList.length
      return { name: d, scenic: Math.round(su * 100), rural: Math.round(ru * 100) }
    })
    .filter(Boolean) as { name: string; scenic: number; rural: number }[]

  const productTop = [...products]
    .map((p) => ({ name: p.name, sales: salesQty(p) * p.price }))
    .sort((a, b) => b.sales - a.sales)
    .slice(0, 6)

  // ---- 风险：规则判定（确定性） ----
  const risks: RiskEvent[] = []

  for (const p of scenics) {
    const u = capacityUsage(p.id, p.business_type)
    if (u >= 1) {
      risks.push({
        id: `R-${p.id}`,
        type: 'OVERLOAD',
        level: 'HIGH',
        title: '景区客流超载',
        poi: p.name,
        detail: `当前承载 ${Math.round(u * 100)}%，已超过设计上限 ${p.capacity.toLocaleString()} 人`,
        suggestion: '启动分时预约与现场分流，同步推送周边乡村替代方案',
      })
    } else if (u >= 0.8) {
      risks.push({
        id: `R-${p.id}`,
        type: 'OVERLOAD',
        level: 'MID',
        title: '景区承载接近上限',
        poi: p.name,
        detail: `当前承载 ${Math.round(u * 100)}%，预计 2 小时内达到上限`,
        suggestion: '提前向未入园游客推送周边乡村点，降低峰值压力',
      })
    }
  }

  // 乡村闲置：同区县景区高位、乡村低位
  for (const row of imbalance) {
    if (row.scenic >= 75 && row.rural <= 40) {
      risks.push({
        id: `R-IDLE-${row.name}`,
        type: 'RURAL_IDLE',
        level: 'MID',
        title: '景区高位运行但周边乡村闲置',
        poi: `${row.name}（景区 ${row.scenic}% / 乡村 ${row.rural}%）`,
        detail: '存在可承接的溢出需求，但乡村点未获得有效导流',
        suggestion: '生成乡村分流工单：上调该区域乡村点在行程规划中的推荐权重',
      })
    }
  }

  risks.sort((a, b) => (a.level === b.level ? 0 : a.level === 'HIGH' ? -1 : 1))

  // 列表要能看见"不同类型"的风险：若只按等级排序，前 5 条会被景区超载占满，
  // 而「乡村闲置」——本项目的核心机制——反而看不见。
  // 做法：先取 2 条最高优先级，再按类型轮转补齐，保证类型多样。
  const shown: RiskEvent[] = risks.slice(0, 2)
  const seen = new Set(shown.map((r) => r.type))
  for (const r of risks.slice(2)) {
    if (shown.length >= 5) break
    if (seen.has(r.type)) continue
    shown.push(r)
    seen.add(r.type)
  }
  for (const r of risks.slice(2)) {
    if (shown.length >= 5) break
    if (shown.includes(r)) continue
    shown.push(r)
  }

  // 环比：以种子生成"上期"基数，再反推变化率，保证界面数字自洽
  const prevVisitors = totalVisitors / (1 + (seed('growth-visitors') * 0.28 - 0.06))
  const prevRuralRatio = ruralRatio - (seed('growth-rural') * 4.2 - 0.8) / 100
  const prevSales = productSales / (1 + (seed('growth-sales') * 0.36 - 0.08))
  const prevRepurchase = repurchaseRate - (seed('growth-repurchase') * 6 - 1.2) / 100

  const deltas = {
    visitorsPct: ((totalVisitors - prevVisitors) / prevVisitors) * 100,
    ruralRatioPt: (ruralRatio - prevRuralRatio) * 100,
    salesPct: ((productSales - prevSales) / prevSales) * 100,
    repurchasePt: (repurchaseRate - prevRepurchase) * 100,
  }

  const hotScenicCount = scenics.filter((p) => capacityUsage(p.id, p.business_type) >= 0.8).length
  const idleDistricts = imbalance.filter((r) => r.scenic >= 75 && r.rural <= 40).map((r) => r.name)
  const ruralAvgUsage = rurals.length
    ? rurals.reduce((s, p) => s + capacityUsage(p.id, p.business_type), 0) / rurals.length
    : 0

  return {
    totalVisitors,
    ruralVisitors,
    ruralRatio,
    productSales,
    repurchaseRate,
    openRisks: risks.length,
    trend,
    mix,
    imbalance,
    productTop,
    risks: shown,
    hotScenicCount,
    idleDistricts,
    ruralAvgUsage,
    deltas,
  }
}
