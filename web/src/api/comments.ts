import type { AdminComment, CommentStatus, PoiComment, PoiCommentList } from '@/types'
import { request } from './http'

/**
 * 景点评论接口（M10 续）。
 *
 * 两组路径，权限完全不同：
 *   `/api/pois/{id}/comments`   游客端。GET 公开（未登录也能看），POST 需登录
 *   `/api/admin/comments/**`    运营端。整段要 OPERATOR 角色
 *
 * 与 `api/resources.ts` 拆成两个文件而不是合成一个：它们在 URL 上本来就是
 * 两组（一个挂在 `/pois` 下、一个挂在 `/admin` 下）。合并之后，
 * "这个函数要不要登录"就得逐个去看实现才知道。
 */

// ============================================================
// 游客端
// ============================================================

/**
 * 某景点的评论列表 + 评分汇总。
 *
 * 资源不存在或已下架时后端抛 1001 —— 与景点详情同一判据，
 * 调用方按"没有这个资源"处理即可。
 */
export function getPoiComments(poiId: string) {
  return request<PoiCommentList>(`/pois/${encodeURIComponent(poiId)}/comments`)
}

/**
 * 发表评论。**需要登录**（后端 `/api/pois/**` 只放行了 GET）。
 *
 * 提交后默认就是可见状态（APPROVED），所以调用方可以直接把它插到列表头部，
 * 不必等一次重新查询 —— 但更稳的做法仍是重新拉一次列表：
 * 服务端返回的那条才带着数据库填的 `created_at`。
 */
export function createPoiComment(poiId: string, body: { rating: number; content: string }) {
  return request<PoiComment>(`/pois/${encodeURIComponent(poiId)}/comments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

// ============================================================
// 运营端
// ============================================================

/**
 * 评论列表。
 *
 * @param poiId  按景点筛，不传 = 全部
 * @param status 按状态筛，`''` 或不传 = 全部
 */
export function adminListComments(
  params: { poiId?: string; status?: CommentStatus | '' } = {}
) {
  const q = new URLSearchParams()
  if (params.poiId) q.set('poi_id', params.poiId)
  // 空串不带上：`status=` 会让后端的 String 判断失效（它把空串当"没传"，
  // 但没必要依赖这一点，明确不传更清楚）
  if (params.status) q.set('status', params.status)
  const s = q.toString()
  return request<AdminComment[]>(`/admin/comments${s ? `?${s}` : ''}`)
}

/** 改状态：通过 / 隐藏 / 打回待审 */
export function adminSetCommentStatus(id: number, status: CommentStatus) {
  return request<AdminComment>(`/admin/comments/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  })
}

/** 物理删除。与「隐藏」是两回事：隐藏可恢复，删除不可 */
export function adminDeleteComment(id: number) {
  return request<{ deleted: boolean }>(`/admin/comments/${id}`, { method: 'DELETE' })
}
