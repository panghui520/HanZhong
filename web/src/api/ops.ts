import type {
  OpsBizType,
  OpsBusiness,
  OpsRange,
  OpsSnapshot,
  PoiStat,
  RiskEvent,
  WorkOrder,
  WorkOrderStatus,
} from '@/types'
import { request } from './http'

/**
 * 承载力与风险（M5）的接口层。
 *
 * 两类路径要分清，混用会踩权限：
 *   · `/stats/**`      —— 公开。游客端读拥挤度，未登录也能拿
 *   · `/admin/**`      —— 需要 OPERATOR 角色。运营端读原始指标、建单、处置
 *
 * 路径与模块文档里写的 `/api/ops/snapshot` 不同：方案第 16 节那是设计阶段的
 * 草案，实施时以 M8 定下的 `/api/admin/**` 前缀约定为准 —— 挂在统一前缀下，
 * 漏配权限的后果是"访问不了"而不是"游客也能看到运营数据"。
 */

// ------------------------------------------------- 游客端（公开）

/**
 * 全部资源点的当日承载。**一次拿全**，前端按 poi_id 查。
 *
 * 为什么不做成 `/stats/pois/{id}` 按需取：探索页一屏要显示几十张卡片的拥挤度，
 * 按需取就是几十个并发请求打同一个接口。42 个资源点一次返回不到 4KB，
 * 直接全量更省事也更快。数据量再大一个量级才需要考虑按需。
 */
export function getPoiStats() {
  return request<PoiStat[]>('/stats/pois')
}

// ------------------------------------------------- 运营端（需要 OPERATOR）

/**
 * 运营大屏快照。字段见 OpsSnapshot 的注释，全部为仿真数据。
 *
 * `range` 是驾驶舱顶部日期控件的档位：`TODAY`（默认）/ `LAST7` / `ALL`。
 * 不传 = 后端按 `TODAY` 算 —— 这个参数加上之前的行为，不是新接口：
 * 窗口化的取数与原 `snapshot()` 是同一份逻辑（都走 `sumWindow`）。
 */
export function getOpsSnapshot(range?: OpsRange) {
  const q = range ? `?range=${range}` : ''
  return request<OpsSnapshot>(`/admin/ops/snapshot${q}`)
}

/**
 * 单业态运营分析（管理端四大业务页面）。
 *
 * 与 `getOpsSnapshot` **是同一套算法**：后端复用同一批窗口求和与承载均值函数，
 * 所以驾驶舱的销售额与农产品页的销售额必然相等。
 *
 * `range` 从驾驶舱下钻时原样带过来（见 `Dashboard.vue` 的 `to`），
 * 保证"切到近 30 日后点进去，业务页也是近 30 日"。
 */
export function getOpsBusiness(type: OpsBizType, range?: OpsRange) {
  const q = new URLSearchParams({ type })
  if (range) q.set('range', range)
  return request<OpsBusiness>(`/admin/ops/business?${q.toString()}`)
}

/**
 * 手动跑一次规则扫描，返回本次命中的事件数。
 *
 * 服务启动时已经自动扫过一次（见 OpsScanRunner），这个接口是给
 * "改了 `risk_rule` 的阈值、现在就想看结果"用的 —— 不需要重启服务。
 * 幂等：同一条 (规则, 资源点, 日期) 只留一条事件。
 */
export function rescanRisks() {
  return request<number>('/admin/ops/scan', { method: 'POST' })
}

/**
 * 风险事件列表。
 *
 * 三个筛选条件都可不传。**不传 status 时返回全部**（含已闭环的）——
 * 列表页要能回看处置过的历史，只看未处置的话，处理完一条它就消失了，
 * 运营会怀疑自己是不是误删了。
 */
export function getRisks(params: { status?: string; level?: string; district?: string } = {}) {
  const q = new URLSearchParams()
  if (params.status) q.set('status', params.status)
  if (params.level) q.set('level', params.level)
  if (params.district) q.set('district', params.district)
  const s = q.toString()
  return request<RiskEvent[]>(`/admin/risks${s ? `?${s}` : ''}`)
}

/** 风险事件详情 */
export function getRisk(id: number) {
  return request<RiskEvent>(`/admin/risks/${id}`)
}

/**
 * 一键建单。同一条事件只能建一张单，重复建单后端返回 8003。
 *
 * `assignee` 与 `suggestion` 都可以不传：前者为空表示"待认领"，
 * 后者为空则沿用规则自带的建议文案（比前端拼的模板更贴近该条规则的触发原因）。
 */
export function createWorkOrder(
  riskId: number,
  body: { assignee?: string; suggestion?: string } = {}
) {
  return request<WorkOrder>(`/admin/risks/${riskId}/work-order`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

/** 工单列表。不传 status 时返回全部 */
export function getWorkOrders(status?: WorkOrderStatus) {
  const q = status ? `?status=${status}` : ''
  return request<WorkOrder[]>(`/admin/work-orders${q}`)
}

/**
 * 处置反馈：改派处置人、置处置中、或填反馈后完结。
 *
 * 置 `DONE` 时 `result` 必填（后端返回 8005），因为"完结了但说不出做了什么"
 * 等于没有处置记录。改派与置处置中则不要求。
 */
export function updateWorkOrder(
  id: number,
  body: { status?: WorkOrderStatus; assignee?: string; result?: string }
) {
  return request<WorkOrder>(`/admin/work-orders/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}
