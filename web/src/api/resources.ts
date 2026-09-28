import type { AdminPoi, AdminProduct } from '@/types'
import { request } from './http'

/**
 * 资源管理接口（M10，运营端）。
 *
 * <p>全部挂在 `/api/admin/resources/**` 下，权限由后端 `SecurityConfig` 里
 * 那条 `/api/admin/**` → `hasRole("OPERATOR")` 统一兜住，前端不必额外处理 ——
 * 与 `api/media.ts` 的写接口同一约定。
 *
 * <p>景点与美食共用一组 `/pois` 端点，用 `type` 查询参数区分：它们在后端
 * 是同一张 `poi` 表的不同 `business_type`，字段完全一样，拆成两组端点
 * 只会多一份要同步维护的代码。农产品走 `/products`。
 */

/** `/pois` 端点只接受这两个值；农产品走 `/products` */
export type PoiKind = 'scenic' | 'food'

type ListParams = { keyword?: string; status?: number }

/**
 * 拼查询串。
 *
 * `status` 只在真的是 0 或 1 时才带上：`status=0` 是有意义的筛选条件
 * （只看下架），而"不筛选"的正确表达是**不带这个参数**。
 * 写成 `status=` 空串会让后端的类型转换失败（后端已容错，但没必要依赖它）。
 */
function queryOf(type: PoiKind | null, params: ListParams): string {
  const q = new URLSearchParams()
  if (type) q.set('type', type)
  if (params.keyword) q.set('keyword', params.keyword)
  if (params.status === 0 || params.status === 1) q.set('status', String(params.status))
  const s = q.toString()
  return s ? `?${s}` : ''
}

// ============================================================
// 景点 / 美食
// ============================================================

export function adminListPois(type: PoiKind, params: ListParams = {}) {
  return request<AdminPoi[]>(`/admin/resources/pois${queryOf(type, params)}`)
}

/** 新增。id 由服务端生成（P-ADM-xxx），body 里带 id 不会被采纳 */
export function adminCreatePoi(type: PoiKind, body: Record<string, unknown>) {
  return request<AdminPoi>(`/admin/resources/pois?type=${type}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

/** 编辑。只改 body 里出现的字段，没传的保持原值 */
export function adminUpdatePoi(type: PoiKind, id: string, body: Record<string, unknown>) {
  return request<AdminPoi>(`/admin/resources/pois/${encodeURIComponent(id)}?type=${type}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

/** 上架 / 下架。1 上架、0 下架 */
export function adminSetPoiStatus(type: PoiKind, id: string, status: number) {
  return request<AdminPoi>(`/admin/resources/pois/${encodeURIComponent(id)}/status?type=${type}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  })
}

/**
 * 删除。
 *
 * 会失败——这是设计如此：来自城市数据包的资源、以及被订单/足迹/体验/配图/
 * 资源关系引用的资源都会被后端拒绝，并在错误文案里说明改用下架。
 * 调用方应当把错误原文展示给运营，不要替换成笼统的"删除失败"。
 */
export function adminDeletePoi(type: PoiKind, id: string) {
  return request<{ deleted: boolean }>(
    `/admin/resources/pois/${encodeURIComponent(id)}?type=${type}`,
    { method: 'DELETE' }
  )
}

// ============================================================
// 农产品
// ============================================================

export function adminListProducts(params: ListParams = {}) {
  return request<AdminProduct[]>(`/admin/resources/products${queryOf(null, params)}`)
}

export function adminCreateProduct(body: Record<string, unknown>) {
  return request<AdminProduct>('/admin/resources/products', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function adminUpdateProduct(id: string, body: Record<string, unknown>) {
  return request<AdminProduct>(`/admin/resources/products/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function adminSetProductStatus(id: string, status: number) {
  return request<AdminProduct>(`/admin/resources/products/${encodeURIComponent(id)}/status`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  })
}

export function adminDeleteProduct(id: string) {
  return request<{ deleted: boolean }>(
    `/admin/resources/products/${encodeURIComponent(id)}`,
    { method: 'DELETE' }
  )
}
