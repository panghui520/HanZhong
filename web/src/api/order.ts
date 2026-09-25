import type { CartItem, Order, OrderCount, OrderReview } from '@/types'
import { request } from './http'

/**
 * 购物车与订单接口（M6）。
 *
 * 分三组：
 *   - `/api/cart/**`       我的购物车，需要登录
 *   - `/api/orders/**`     我的订单，需要登录
 *   - `/api/reviews/**`    评价图片上传，需要登录（但不需要运营角色）
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
 *
 * 下单后进入**待付款**，不是待发货。付款与超时取消见 payOrder。
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

/**
 * 我的订单计数。顶栏「我的订单」角标用。
 *
 * `pending` 是**需要用户动手**的订单数（待付款 + 已发货），
 * 角标显示的是它而不是 total —— 已完成的订单用户无事可做，
 * 算进角标会让角标只增不减，变成一个永远消不掉的红点。
 * 口径由服务端定，前端不自己数（见 OrderCountVO 的注释）。
 */
export function getOrderCount() {
  return request<OrderCount>('/orders/count')
}

/**
 * 付款（演示级：不接真实支付通道，调一次就是"已付款"）。
 *
 * **没有请求体**：付多少钱由服务端按订单总额算，前端传什么都不看。
 * 待付款超时（30 分钟）后调这个接口会拿到 6013，且订单已被服务端
 * 顺手改成已取消 —— 前端收到 6013 应当重新拉一次订单再提示，
 * 否则页面还停在"待付款 + 付款按钮"，用户会反复点。
 */
export function payOrder(id: number) {
  return request<Order>(`/orders/${id}/pay`, { method: 'POST' })
}

/** 取消订单。只有待付款能直接取消，已付款的要走申请退款 */
export function cancelOrder(id: number) {
  return request<Order>(`/orders/${id}/cancel`, { method: 'POST' })
}

/** 确认收货。只有已发货能确认；确认后才能评价 */
export function confirmReceipt(id: number) {
  return request<Order>(`/orders/${id}/receipt`, { method: 'POST' })
}

/** 申请退款。待发货与已发货两态可以申请；理由必填 */
export function requestRefund(id: number, reason: string) {
  return request<Order>(`/orders/${id}/refund`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refund_reason: reason }),
  })
}

/**
 * 评价。一单一评，星级必填（1..5），文字与图片可空。
 *
 * `images` 传的是**上传接口返回的相对路径**，不是完整 URL ——
 * 服务端要拿它做 `..` 穿越校验，不能让客户端自由拼前缀。
 * 读回来的时候服务端已经拼成完整 URL 了，方向不对称是刻意的。
 */
export function createReview(
  id: number,
  form: { rating: number; content?: string; images?: string[] }
) {
  return request<OrderReview>(`/orders/${id}/review`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(form),
  })
}

/**
 * 上传一张评价图片，返回它的相对路径。
 *
 * **一次一张，前端循环调用。** 三张图分三次请求，任何一张失败都只影响
 * 那一张，用户可以单独重试；一次传三张时失败一张就得整批重来。
 *
 * 用 FormData 且**不手动设 Content-Type** —— 浏览器要自己往里面写
 * multipart 的 boundary，手工设成 multipart/form-data 会丢掉 boundary，
 * 后端直接解析失败（M9 踩过）。
 */
export function uploadReviewImage(file: File) {
  const fd = new FormData()
  fd.append('file', file)
  return request<{ path: string }>('/reviews/images', { method: 'POST', body: fd })
}

// ------------------------------------------------------------ 运营端

/**
 * 全部订单。status 传具体状态（如 `PENDING_SHIPMENT`）可只看该状态。
 *
 * 注意传的必须是**真实状态值**。旧代码传的是 `'PENDING'`，那个值在
 * 七态状态机里已经不存在了，服务端会原样拿去比对、返回空列表 ——
 * 不报错，只是标签页永远空着，很难发现。
 */
export function adminListOrders(status?: string) {
  const q = status ? `?status=${encodeURIComponent(status)}` : ''
  return request<Order[]>(`/admin/orders${q}`)
}

/**
 * 标记已发货。**物流三要素必填**（快递公司 / 预计到达天数 / 快递单号）。
 *
 * 缺任何一项服务端返回 6009，不做"没填就存空字符串"的宽松处理 ——
 * 物流信息是给用户看的，存一条空记录比报错更糟：用户点开详情
 * 看到"快递公司：—"，却不知道该找谁。
 */
export function adminShipOrder(
  id: number,
  form: { carrier: string; eta_days: number; tracking_no: string }
) {
  return request<Order>(`/admin/orders/${id}/ship`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(form),
  })
}

/**
 * 处理退款。`approve` 必须是布尔。
 *
 * 同意 → 订单变已退款（终态）；拒绝 → 退回**申请前的状态**
 * （待发货或已发货），并记下拒绝理由。所以拒绝时理由也要填 ——
 * 用户看不到理由只会再申请一次，运营还要再拒一次。
 */
export function adminHandleRefund(id: number, form: { approve: boolean; reply?: string }) {
  return request<Order>(`/admin/orders/${id}/refund`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(form),
  })
}
