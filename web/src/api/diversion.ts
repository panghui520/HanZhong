import type { DiversionNotice } from '@/types'
import { request } from './http'

/**
 * 分流公告（M5 续）的接口层。
 *
 * 两类路径要分清，混用会踩权限（与 `ops.ts` 同一条约定）：
 *   · `/diversion-notices`        —— 公开。**只有 GET**。游客端首页/详情页读它
 *   · `/admin/diversion-notices`  —— 需要 OPERATOR。草稿、发布、撤下
 *
 * 游客端那条只放 GET，不是随手写的：发布/编辑/撤下全在 `/admin/**` 下，
 * 由 `SecurityConfig.ADMIN_PREFIX` 那条规则兜住。这里若不限定方法，
 * 等于把"发布公告"的通道开给游客。
 */

// ------------------------------------------------- 游客端（公开）

/**
 * 当前生效的分流公告。
 *
 * 返回数组而不是单条：同一时刻可能有两个景区都在高位，首页要把两条都列出来。
 * 前端决定显示几条（当前是全部，通常 0–2 条）。
 *
 * **服务端已过滤**（只给 `PUBLISHED` 且未过期的），前端不要自己再判一次状态 ——
 * 两处判断迟早不一致，而不一致的那次一定表现为"首页出现过期公告"。
 */
export function getDiversionNotices() {
  return request<DiversionNotice[]>('/diversion-notices')
}

// ------------------------------------------------- 运营端（需要 OPERATOR）

/** 公告列表。不传 status 时返回**四态全部**（草稿也要看得到，否则草稿一存就找不到了） */
export function getAdminDiversionNotices(status?: string) {
  const q = status ? `?status=${status}` : ''
  return request<DiversionNotice[]>(`/admin/diversion-notices${q}`)
}

/**
 * 从风险事件生成公告草稿。
 *
 * `title` / `message` 都可以不传：不传则由候选自动生成
 * （正文里会带"演示用仿真数据"这句）。**候选在这一步就算好写进快照**，
 * 所以"当时为什么推荐它"永远可回看。
 *
 * 同一条事件重复生成返回 8011；该事件没有候选返回 8013；
 * 已有公告但**已过期**时会顶替掉那一条（不是 8011）—— 见后端 `DiversionServiceImpl`。
 */
export function createNoticeDraft(riskId: number, body: { title?: string; message?: string } = {}) {
  return request<DiversionNotice>(`/admin/risks/${riskId}/notice`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

/**
 * 改文案 / 发布 / 撤下。
 *
 * **状态只允许这两个值**，故意收窄：后端把 `DRAFT` 与 `EXPIRED` 都判为
 * 8012（非法状态）—— 前者是"不能把已发布的退回草稿"，后者是"过期是系统按时间置的，
 * 手工置会让人以为过期了、实际是被人关掉了，追不了责"。
 * 类型上直接不让传，比让用户点了再报错好。
 *
 * 重新发布会**刷新失效时间**（+24h），所以撤回后过一会儿再发不会只剩几分钟寿命。
 */
export function updateDiversionNotice(
  id: number,
  body: {
    title?: string
    message?: string
    status?: 'PUBLISHED' | 'WITHDRAWN'
    published_by?: string
  }
) {
  return request<DiversionNotice>(`/admin/diversion-notices/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}
