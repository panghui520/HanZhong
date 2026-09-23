import type { CityMeta, Experience, Poi, PoiDetail, Product } from '@/types'
import { request } from './http'

/**
 * 城市数据读取层。页面只依赖这里的函数名，数据源换到后端时页面代码不用动。
 *
 * 当前各函数的数据源状态（逐模块切换，切完即删对应的 mock 文件）：
 *   getMeta / getPois / getPoiDetail  → Java 后端 /api（数据由 CityPackImporter 从 citypack/ 导入）
 *   getExperiences / getProducts      → 本地 /data/*.json，等 M2 建表后再切
 *
 * 之所以两套并存，是因为按纵切推进：M1 只负责"资源"这一层，
 * 体验与产品属于 M2，提前切过来会让 M1 的验收范围说不清。
 */
const DATA_BASE = '/data'
const cache = new Map<string, unknown>()

/** 读取尚未迁移到后端的本地 JSON（由 scripts/sync-citypack.mjs 同步而来） */
async function loadLocal<T>(file: string): Promise<T> {
  if (cache.has(file)) return cache.get(file) as T
  const res = await fetch(`${DATA_BASE}/${file}`, { cache: 'no-cache' })
  if (!res.ok) throw new Error(`数据加载失败：${file}（HTTP ${res.status}）`)
  const data = (await res.json()) as T
  cache.set(file, data)
  return data
}

// ---------------------------------------------------------------- M1：后端

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

// ------------------------------------------------- M2 待切：乡村体验与农产品

export function getExperiences() {
  return loadLocal<Experience[]>('experiences.json')
}

export function getProducts() {
  return loadLocal<Product[]>('products.json')
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
