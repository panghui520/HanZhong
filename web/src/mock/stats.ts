/**
 * 演示用仿真指标（SIMULATED）
 * ------------------------------------------------------------------
 * 这些数字不是真实统计，而是由 POI id 派生的确定性伪随机值。
 *
 * 为什么按业态分档：本项目要演示的核心叙事是「热点景区超载 + 周边乡村闲置」，
 * 如果各业态承载在同一个区间里均匀取值，这个失衡就看不出来，规则引擎也不会触发。
 * 因此按业态设定不同区间——景区整体偏高（含超载样本），乡村整体偏低（有承接余量），
 * 餐饮住宿交通居中。区间是设计选择，不是真实观测值。
 *
 * 后端规则引擎上线后，本文件整体删除，改为读取 /api/stats。
 */
import { seed } from './hash'
import type { BusinessType } from '@/types'

/** 各业态的承载占用率区间 [下限, 上限]，上限可 >1 表示超载 */
const USAGE_RANGE: Record<BusinessType, [number, number]> = {
  // 热点景区：区间 [0.25,1.15] 使 18 个景区里约 7 个进入高位（≥80%）、约 3 个超载，
  // 比"半数以上高位"更接近真实——真正挤的永远只有少数几个头部点位。
  SCENIC: [0.25, 1.15],
  RURAL_SPOT: [0.22, 0.67], // 乡村：整体宽裕，才有承接空间
  FOOD: [0.35, 0.88],
  LODGING: [0.3, 0.8],
  TRANSPORT: [0.4, 0.9],
  SHOPPING: [0.25, 0.7],
}

/** 承载力占用率（>1 表示超载） */
export function capacityUsage(poiId: string, type: BusinessType = 'SCENIC'): number {
  const [lo, hi] = USAGE_RANGE[type] ?? USAGE_RANGE.SCENIC
  return Number((lo + seed(poiId, 'usage') * (hi - lo)).toFixed(4))
}

/** 当日到访人数 */
export function todayVisitors(poiId: string, capacity: number, type?: BusinessType): number {
  const ratio = 0.55 + seed(poiId, 'day') * 0.5
  return Math.round(capacity * capacityUsage(poiId, type) * ratio)
}

/** 近 7 日客流（用于趋势图与迷你柱图） */
export function weekVisitors(
  poiId: string,
  capacity: number,
  type?: BusinessType
): number[] {
  const u = capacityUsage(poiId, type)
  return Array.from({ length: 7 }, (_, i) => {
    const weekend = i >= 5 ? 1.35 : 1
    const jitter = 0.8 + seed(poiId, 'wk', i) * 0.4
    return Math.round(capacity * u * 0.6 * weekend * jitter)
  })
}

/** 评分（4.2–4.9） */
export function ratingOf(poiId: string): number {
  return Number((4.2 + seed(poiId, 'rating') * 0.7).toFixed(1))
}
