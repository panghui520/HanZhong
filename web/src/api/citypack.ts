import type { CityMeta, Experience, Poi, PoiDetail, Product, ProductCategory } from '@/types'
import { request } from './http'

/**
 * 城市数据读取层。页面只依赖这里的函数名，数据源换到后端时页面代码不用动。
 *
 * M1 曾有一半数据读本地 /data/*.json（由 scripts/sync-citypack.mjs 从 citypack/ 拷过去），
 * M2 全部切到 Java 后端后，本地 JSON 这条链路连同同步脚本一起删掉了——
 * 留着两套数据源，就会出现"接口改了、本地文件没改"的静默不一致。
 * 现在唯一的数据来源是后端，后端唯一的数据来源是 citypack/。
 */

// ---------------------------------------------------------------- 资源（M1）

export function getMeta() {
  return request<CityMeta>('/city')
}

export function getPois() {
  return request<Poi[]>('/pois')
}

/** 资源详情。含 nearby / support / same_village / diversion 四组关系，距离由后端算好 */
export function getPoiDetail(id: string) {
  return request<PoiDetail>(`/pois/${encodeURIComponent(id)}`)
}

// ------------------------------------------------- 乡村体验与农产品（M2）

/**
 * 拼查询串。值为 undefined / 空串的键直接丢掉，
 * 否则会拼出 ?poi_id= 这种空参数，后端拿到空串当"没有筛选条件"处理，
 * 看起来正常但多传了一个无意义的参数。
 */
function query(params: Record<string, string | undefined>) {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v) q.set(k, v)
  }
  const s = q.toString()
  return s ? `?${s}` : ''
}

/** 体验列表。传 poiId 只看某个乡村能做什么，传 type 按类型挑（M4 行程规划用） */
export function getExperiences(params: { poiId?: string; type?: string } = {}) {
  return request<Experience[]>(`/experiences${query({ poi_id: params.poiId, type: params.type })}`)
}

/** 体验详情 */
export function getExperience(id: string) {
  return request<Experience>(`/experiences/${encodeURIComponent(id)}`)
}

/**
 * 产品列表。四个筛选条件可任意组合，都为 undefined 时返回全部在售产品。
 * 详情页传 poiId，体验区块传 experienceId，M4 规划按 categoryCode 挑伴手礼。
 */
export function getProducts(
  params: {
    poiId?: string
    experienceId?: string
    categoryCode?: string
    keyword?: string
  } = {}
) {
  return request<Product[]>(
    `/products${query({
      poi_id: params.poiId,
      experience_id: params.experienceId,
      category_code: params.categoryCode,
      keyword: params.keyword,
    })}`
  )
}

/** 产品详情 */
export function getProduct(id: string) {
  return request<Product>(`/products/${encodeURIComponent(id)}`)
}

/** 分类列表，含各分类在售产品数。首页筛选条用 */
export function getProductCategories() {
  return request<ProductCategory[]>('/product-categories')
}

// ------------------------------------------------------------------ 组合

/** 一次拿全，页面里避免多次 fetch 竞态 */
export async function getCityPack() {
  const [meta, pois, experiences, products] = await Promise.all([
    getMeta(),
    getPois(),
    getExperiences(),
    getProducts(),
  ])
  return { meta, pois, experiences, products }
}
