import type { CityMeta, Experience, Poi, Product } from '@/types'

/**
 * 城市数据读取层
 * 数据源为 /data/*.json（由 scripts/sync-citypack.mjs 从 citypack/ 同步而来）。
 * 后续接入 Java 后端时只需把 base 换成 /api，函数签名不变。
 */
const BASE = '/data'
const cache = new Map<string, unknown>()

async function load<T>(file: string): Promise<T> {
  if (cache.has(file)) return cache.get(file) as T
  const res = await fetch(`${BASE}/${file}`, { cache: 'no-cache' })
  if (!res.ok) throw new Error(`数据加载失败：${file}（HTTP ${res.status}）`)
  const data = (await res.json()) as T
  cache.set(file, data)
  return data
}

export function getMeta() {
  return load<CityMeta>('meta.json')
}
export function getPois() {
  return load<Poi[]>('pois.json')
}
export function getExperiences() {
  return load<Experience[]>('experiences.json')
}
export function getProducts() {
  return load<Product[]>('products.json')
}

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
