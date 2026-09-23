/** 业务类型（多业态统一在 POI 一张表上，靠 business_type 区分） */
export type BusinessType =
  | 'SCENIC'
  | 'RURAL_SPOT'
  | 'FOOD'
  | 'LODGING'
  | 'TRANSPORT'
  | 'SHOPPING'

export interface Poi {
  id: string
  name: string
  business_type: BusinessType
  district: string
  level?: string
  lng: number
  lat: number
  ticket_price: number
  open_hours: string
  duration_min: number
  capacity: number
  tags: string[]
  summary: string
  scene?: string
  data_origin?: string
  source_url?: string
}

export interface Experience {
  id: string
  poi_id: string
  name: string
  type: string
  duration_min: number
  price: number
  season: string
  capacity: number
  tags: string[]
  desc: string
}

export interface Product {
  id: string
  poi_id: string
  experience_id?: string
  category: string
  name: string
  spec: string
  price: number
  origin_village: string
  stock: number
  tags: string[]
  story: string
  scene?: string
}

export interface CityMeta {
  city_code: string
  name: string
  province: string
  center: { lng: number; lat: number }
  tagline: string
  summary: string
  data_origin: string
  disclaimer: string
  version: string
}

/** 后端统一响应体：HTTP 恒为 200，业务结果看 code（0 表示成功） */
export interface Result<T> {
  code: number
  message: string
  data: T
}

/** 资源关系项。对应后端 poi_relation，距离与通行时间由后端算好 */
export interface RelationItem {
  id: string
  name: string
  business_type: BusinessType
  district: string
  scene?: string
  distance_km: number
  travel_min: number
  weight: number
}

/**
 * 资源详情。四组关系由后端按球面距离与业态规则生成，前端不再自己算——
 * 换一份城市数据包，关系网络会跟着重建。
 */
export interface PoiDetail {
  poi: Poi
  /** 邻近资源（同城，距离近） */
  nearby: RelationItem[]
  /** 配套业态（吃住行购） */
  support: RelationItem[]
  /** 同片区乡村点 */
  same_village: RelationItem[]
  /** 可分流承接的乡村点（仅景区有） */
  diversion: RelationItem[]
}

export const BUSINESS_LABEL: Record<BusinessType, string> = {
  SCENIC: '景区',
  RURAL_SPOT: '乡村旅游',
  FOOD: '餐饮',
  LODGING: '住宿',
  TRANSPORT: '交通',
  SHOPPING: '购物',
}

export const BUSINESS_ORDER: BusinessType[] = [
  'SCENIC',
  'RURAL_SPOT',
  'FOOD',
  'LODGING',
  'TRANSPORT',
  'SHOPPING',
]
