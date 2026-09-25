import type { PoiCoverMap, PoiImage, SiteBanner } from '@/types'
import { request } from './http'

/**
 * 媒体与配图接口（M9）。
 *
 * 分两组：
 *   - 读：`/api/media/**`，游客端用，公开
 *   - 写：`/api/admin/media/**`，运营端用，需要 OPERATOR 角色
 *
 * 上传用 FormData，不手动设 Content-Type —— 浏览器要自己往里面写
 * multipart 的 boundary，手工设成 multipart/form-data 会丢掉 boundary，
 * 后端直接解析失败。http.ts 的 request 只在没有 Accept 时才补头，不会覆盖它。
 */

// ------------------------------------------------------------------ 读

/** 首页轮播。后端只返回启用中的帧 */
export function getBanners() {
  return request<SiteBanner[]>('/media/banners')
}

/** 某个景点的全部配图，按 sort_order 排好 */
export function getPoiImages(poiId: string) {
  return request<PoiImage[]>(`/media/poi-images?poi_id=${encodeURIComponent(poiId)}`)
}

/**
 * 全部景点的封面映射（poi_id -> url）。
 *
 * 列表页几十张卡片逐张发请求不现实，所以这里一次取回、前端按 id 查表。
 * 查不到就回落手写 SVG —— "还没传图的景点"与"传了图的景点"能在同一页共存。
 */
export function getPoiCovers() {
  return request<PoiCoverMap>('/media/poi-images/covers')
}

// ------------------------------------------------------- 写：景点配图

/** 管理端配图列表。不传 poiId 返回全部 */
export function adminListPoiImages(poiId?: string) {
  const q = poiId ? `?poi_id=${encodeURIComponent(poiId)}` : ''
  return request<PoiImage[]>(`/admin/media/poi-images${q}`)
}

/** 给某个景点新增一张配图。该景点原本没图时，新图自动成为封面 */
export function adminUploadPoiImage(poiId: string, file: File, altText?: string) {
  const fd = new FormData()
  fd.append('poi_id', poiId)
  fd.append('file', file)
  if (altText) fd.append('alt_text', altText)
  return request<PoiImage>('/admin/media/poi-images', { method: 'POST', body: fd })
}

/** 替换某张配图的文件。保留它的排序位置与封面身份 */
export function adminReplacePoiImage(imageId: number, file: File) {
  const fd = new FormData()
  fd.append('file', file)
  return request<PoiImage>(`/admin/media/poi-images/${imageId}/image`, {
    method: 'PUT',
    body: fd,
  })
}

/** 改备注 / 设封面。两个字段都可选，只传要改的那个 */
export function adminUpdatePoiImage(imageId: number, patch: { alt_text?: string; is_cover?: number }) {
  return request<PoiImage>(`/admin/media/poi-images/${imageId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  })
}

export function adminDeletePoiImage(imageId: number) {
  return request<{ deleted: boolean }>(`/admin/media/poi-images/${imageId}`, { method: 'DELETE' })
}

/**
 * 重排某个景点的配图。
 *
 * 传的是**期望的完整顺序**（imageId 数组），不是两两交换 ——
 * 前端把当前列表顺序整理好发过来即可。后端会校验这批 id
 * 恰好等于该景点的全部图片，多一个少一个都拒绝。
 */
export function adminReorderPoiImages(poiId: string, imageIds: number[]) {
  return request<PoiImage[]>(
    `/admin/media/poi-images/order?poi_id=${encodeURIComponent(poiId)}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(imageIds),
    }
  )
}

// --------------------------------------------------------- 写：轮播图

export function adminListBanners() {
  return request<SiteBanner[]>('/admin/media/banners')
}

/** 新建一帧。图片可选 —— 先配文案、回头再传图是常见的操作顺序 */
export function adminCreateBanner(
  file: File | null,
  fields: {
    scene?: string
    eyebrow?: string
    title?: string
    subtitle?: string
    description?: string
    link_url?: string
    cta?: string
    enabled?: number
  }
) {
  const fd = new FormData()
  if (file) fd.append('file', file)
  for (const [k, v] of Object.entries(fields)) {
    if (v !== undefined && v !== null) fd.append(k, String(v))
  }
  return request<SiteBanner>('/admin/media/banners', { method: 'POST', body: fd })
}

/** 改文案 / 启停。刻意不动图片 —— 换图有单独的接口 */
export function adminUpdateBanner(
  id: number,
  patch: Partial<{
    scene: string
    eyebrow: string
    title: string
    subtitle: string
    description: string
    link_url: string
    cta: string
    enabled: number
  }>
) {
  return request<SiteBanner>(`/admin/media/banners/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  })
}

export function adminReplaceBannerImage(id: number, file: File) {
  const fd = new FormData()
  fd.append('file', file)
  return request<SiteBanner>(`/admin/media/banners/${id}/image`, { method: 'PUT', body: fd })
}

/** 清除图片，回到手写 SVG 兜底画面。文案与链接都留着 */
export function adminClearBannerImage(id: number) {
  return request<SiteBanner>(`/admin/media/banners/${id}/image`, { method: 'DELETE' })
}

export function adminDeleteBanner(id: number) {
  return request<{ deleted: boolean }>(`/admin/media/banners/${id}`, { method: 'DELETE' })
}

/** 重排轮播顺序。语义与 adminReorderPoiImages 一致：传完整顺序 */
export function adminReorderBanners(ids: number[]) {
  return request<SiteBanner[]>('/admin/media/banners/order', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(ids),
  })
}
