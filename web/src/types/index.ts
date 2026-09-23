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
