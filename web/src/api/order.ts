import type { CartItem, Order } from '@/types'
import { request } from './http'

/**
 * 购物车与订单接口（M6）。
 *
 * 分三组：
 *   - `/api/cart/**`       我的购物车，需要登录
 *   - `/api/orders/**`     我的订单，需要登录
 *   - `/api/admin/orders`  运营端订单处理，需要 OPERATOR
 *
 * **没有匿名购物车。** 所有接口都要登录，未登录会拿到 4001，
 * 由 http.ts 统一触发跳登录页。页面不用自己判断登录态再决定调不调。
 */

// ---------------------------------------------------------------- 购物车

/** 我的购物车。空车返回空数组，不是 404 */
export function getCart() {
  return request<CartItem[]>('/cart')
}

/** 购物车件数（数量之和）。顶栏角标用，不用为此拉整张购物车 */
export function getCartCount() {
  return request<number>('/cart/count')
}

/** 加购。同一商品重复加购是数量累加，不是新增一行 */
export function addToCart(productId: string, quantity = 1) {
  return request<CartItem>('/cart', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ product_id: productId, quantity }),
  })
}

/** 改数量。必须 >= 1；要移除请调 removeCartItem，不要传 0 */
export function updateCartQuantity(cartItemId: number, quantity: number) {
  return request<CartItem>(`/cart/${cartItemId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ quantity }),
  })
}

export function removeCartItem(cartItemId: number) {
  return request<{ deleted: boolean }>(`/cart/${cartItemId}`, { method: 'DELETE' })
}

export function clearCart() {
  return request<{ cleared: boolean }>('/cart', { method: 'DELETE' })
}

// ---------------------------------------------------------------- 订单

export interface CheckoutForm {
  receiver_name: string
  receiver_phone: string
  receiver_address: string
  remark?: string
}

/**
 * 提交订单：把购物车整车结算成一单。
 *
 * 只传收货信息，**不传商品也不传金额** —— 买什么从服务端购物车读，
 * 多少钱由服务端按库里价格算。前端传的价格后端一律不看。
 */
export function createOrder(form: CheckoutForm) {
  return request<Order>('/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(form),
  })
}

/** 我的订单（含明细），按时间倒序 */
export function getMyOrders() {
  return request<Order[]>('/orders')
}

export function getMyOrder(id: number) {
  return request<Order>(`/orders/${id}`)
}

// ------------------------------------------------------------ 运营端

/** 全部订单。status 传 'PENDING' 可只看待发货 */
export function adminListOrders(status?: string) {
  const q = status ? `?status=${encodeURIComponent(status)}` : ''
  return request<Order[]>(`/admin/orders${q}`)
}

/** 标记已发货。服务端会校验当前必须是待发货，重复点不会刷新发货时间 */
export function adminShipOrder(id: number) {
  return request<Order>(`/admin/orders/${id}/ship`, { method: 'PATCH' })
}
