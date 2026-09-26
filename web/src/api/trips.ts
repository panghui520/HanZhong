import type { TripContext } from '@/types'
import { request } from './http'

/**
 * 当前行程与上下文接口（M4 阶段二）。
 *
 * 三个接口都在 `/api/trips/**` 下，**全部需要登录**：
 * 行程是"我的数据"，与购物车同理。未登录访问会拿到 4001，
 * 由 http.ts 统一处理（跳登录页）。
 *
 * 所以调用方**必须先判断登录态再调**，不能像公开接口那样"调了再看结果" ——
 * 在助手页上直接调，会把一个只是想问问路的游客弹到登录页去。
 * 见 Agent.vue 里 loadTrip() 的第一行。
 *
 * 路径里的 `current` 是刻意的：一个用户只有一次"当前行程"，前端不需要
 * 知道行程 id，也就不存在"把别人的行程 id 传进来"这类越权面。
 */

/** 取当前行程。**没有就创建一个**（服务端懒创建），所以这是一个有副作用的 GET */
export function getCurrentTrip() {
  return request<TripContext>('/trips/current')
}

/**
 * 把某家酒店记为本次行程的住处。
 *
 * 提交的字段就是前端刚收到的那张高德卡片。`longitude`/`latitude` 允许传
 * number（卡片上是 number）或字符串，后端两种都接。
 *
 * **这里不做真实预订**：没有库存、没有支付、没有订单。它只做一件事 ——
 * 让助手记住"用户住哪"。卡片上的"去第三方预订"是跳转链接，
 * 跳走之后的事情不在本系统内。
 */
export function selectTripHotel(hotel: {
  poi_id: string
  name: string
  address: string
  longitude: number | string
  latitude: number | string
  source?: string
}) {
  return request<TripContext>('/trips/current/hotel', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...hotel, source: hotel.source || 'amap' }),
  })
}

/**
 * 取消已选住处。
 *
 * 返回的是清空后的整个上下文，不是 `{deleted: true}` —— 前端拿到后要立刻
 * 重画上下文条与卡片高亮，返回新状态就省掉一次往返（也省掉一次
 * "清空成功但界面还显示着旧酒店"的中间态）。
 */
export function clearTripHotel() {
  return request<TripContext>('/trips/current/hotel', { method: 'DELETE' })
}
