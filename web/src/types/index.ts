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
  /** 所属乡村点名称，由后端补全（详情页与产品溯源文案都要用） */
  poi_name?: string
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
  poi_id?: string
  /** 产地乡村点名称，由后端补全 */
  poi_name?: string
  /** 体验锚点。产品必须挂产地或体验，至少一项（数据库有 CHECK 约束） */
  experience_id?: string
  /** 体验锚点名称，由后端补全——"这一款来自哪次体验"是核心信息，不再由前端自己拼 */
  experience_name?: string
  /** 分类编码，用于筛选（分类中文名会被改，不能当筛选条件） */
  category_code?: string
  /** 分类显示名，用于展示 */
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

/** 农产品分类。product_count 由后端算好，与产品列表出自同一套过滤条件 */
export interface ProductCategory {
  code: string
  name: string
  sort: number
  product_count: number
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

// ------------------------------------------------- 文旅知识问答（M3）

/**
 * 回答模式。四种模式是**降级链**，不是可选项：
 * llm（配了模型）→ cache（命中预生成答案）→ extractive（摘录原文）→ no_answer（超出知识库范围）。
 * 页面要如实显示当前是哪一档，用户才知道这个回答可信到什么程度。
 */
export type QaMode = 'llm' | 'cache' | 'extractive' | 'no_answer'

export const QA_MODE_LABEL: Record<QaMode, string> = {
  llm: '大模型生成',
  cache: '预生成答案',
  extractive: '原文摘录',
  no_answer: '知识库未覆盖',
}

/** 回答引用的来源。source_url 指向公开出处，可点开核对 */
export interface QaSource {
  title: string
  /** city_doc / poi / experience / product，用于分组显示 */
  doc_type: string
  source_name: string
  source_url: string
  /** 相关度 0—1；缓存模式下为 null（不经过检索） */
  score: number | null
}

export interface QaMetaEvent {
  type: 'meta'
  mode: QaMode
  sources: QaSource[]
  /** 检索到的候选条数 */
  retrieved: number
  /** 首条候选的归一化 BM25 相关度 */
  top_score: number
  /** 问题里知识库完全没写过的实词。非空即判定超范围 */
  gaps: string[]
  gaps_hint: string
}

export interface QaDeltaEvent {
  type: 'delta'
  text: string
}

export interface QaDoneEvent {
  type: 'done'
  mode: QaMode
  elapsed_ms: number
}

export interface QaErrorEvent {
  type: 'error'
  message: string
}

export type QaEvent = QaMetaEvent | QaDeltaEvent | QaDoneEvent | QaErrorEvent

/** 流式回调。页面按这四个钩子渲染，不接触 SSE 细节 */
export interface QaHandlers {
  onMeta?: (event: QaMetaEvent) => void
  onDelta?: (text: string) => void
  onDone?: (event: QaDoneEvent) => void
  onError?: (message: string) => void
}

/** 知识库健康状态。字段与 server-ai 的 /ai/health 一一对应 */
export interface AiHealth {
  ok: boolean
  city: string
  chunks: number
  docs: number
  by_type: Record<string, number>
  embedder: string
  lexical: { chunks: number; terms: number; avg_tokens: number }
  llm_configured: boolean
  demo_mode: boolean
  model: string | null
  cache_entries: number
  stale: boolean
  stale_hint?: string
  error?: string
}

/** 一轮问答。页面里按时间顺序保存，用于多轮上下文展示 */
export interface QaTurn {
  id: number
  question: string
  answer: string
  meta: QaMetaEvent | null
  elapsedMs: number
  /** 流式进行中 */
  streaming: boolean
  error: string
}

/* ============================================================
 * M8 认证与权限
 *
 * 三个类型与后端 VO 一一对应：
 *   AuthToken ← AuthTokenVO   （注册/登录的返回）
 *   AuthUser  ← AuthUserVO    （GET /api/me 的返回）
 *   Role      ← app_user.role （后端是 GUEST/OPERATOR 大写）
 *
 * 注意 Role 用的是后端原样的字符串，不是前端自定义的小写。
 * 早先前端自己定义过 'guest' | 'admin' 两个小写值，那是纯前端演示的产物；
 * 现在角色是后端说了算，前端再翻译一层只会多一处可能对不上的地方。
 * ============================================================ */

export type Role = 'GUEST' | 'OPERATOR'

/** 注册/登录成功后返回的令牌与用户信息 */
export interface AuthToken {
  token: string
  expires_in_seconds: number
  user_id: number
  email: string
  nickname: string
  role: Role
}

/** GET /api/me 返回的当前用户 */
export interface AuthUser {
  user_id: number
  email: string
  nickname: string
  role: Role
  email_verified: boolean
}

/* ============================================================
 * M9 媒体与配图
 *
 * 两件事：景点配图（poi_image）与首页轮播（site_banner）。
 *
 * 共同的字段约定：image_path 是相对媒体根目录的路径（管理端替换/删除时带回来），
 * url 是可直接塞进 <img src> 的地址。
 *
 * 注意：后端全局配了 default-property-inclusion=non_null，**值为 null 的字段会被
 * 整个省略**。所以 url 的类型是 `string | undefined` 而不是 `string | null` ——
 * 判断"有没有图"要用 `if (x.url)`，不能写成 `x.url !== null`（那个条件永远成立）。
 * ============================================================ */

/** 景点配图 */
export interface PoiImage {
  id: number
  poi_id: string
  /** 相对 media-dir 的路径 */
  image_path: string
  /** 可直接访问的完整地址；不会为 null（库表里 image_path 是 NOT NULL） */
  url?: string
  alt_text?: string
  sort_order: number
  /** 1 = 该景点的封面，0 = 普通图。同一景点至多一张为 1 */
  is_cover: number
  source?: string
}

/**
 * 首页轮播帧。
 *
 * url 缺省表示运营还没给这一帧传图，前端应当用手写 SVG（scene 字段）渲染 ——
 * 这是"离线可演示"的兜底：一张真实图片都没有时，首页仍是一幅完整画面。
 */
export interface SiteBanner {
  id: number
  sort_order: number
  image_path?: string
  /** 缺省 = 未上传图片，回落到 scene */
  url?: string
  /** 兜底画面（手写 SVG 变体） */
  scene: string
  eyebrow?: string
  title: string
  subtitle?: string
  description?: string
  link_url?: string
  cta?: string
  /** 1 = 启用（游客端可见），0 = 停用 */
  enabled: number
}

/** 景点 id -> 封面图地址。没有配图的景点不在这个映射里，前端回落手写 SVG */
export type PoiCoverMap = Record<string, string>

/* ============================================================
 * 购物车与订单（M6）
 *
 * 同样受后端 `default-property-inclusion: non_null` 影响：
 * 值为 null 的字段会被整个省略，前端拿到的是 undefined 而不是 null。
 * 所以这里一律写成可选字段，判空用 `if (x)`。
 *
 * 金额字段在 Java 侧是 BigDecimal，Jackson 默认序列化成 JSON 数字，
 * 到前端就是 number。**不要拿它做累加后再提交** ——
 * 金额一律由服务端算，前端只负责显示。
 * ============================================================ */

/**
 * 购物车行。
 *
 * `available` 为 false 表示该商品已下架或已不属于当前城市的数据包，
 * 此时商品字段全部缺省。**不要静默跳过这种行** ——
 * 用户会以为"我明明加过"，然后反复加购反复失败。要显式提示并给删除按钮。
 */
export interface CartItem {
  /** 购物车行 id，不是商品 id。改数量、删除都用它 */
  id: number
  product_id: string
  available: boolean
  name?: string
  spec?: string
  scene?: string
  /** 当前单价（购物车看的是"现在多少钱"） */
  unit_price?: number
  quantity: number
  subtotal?: number
  /** 当前库存，用来禁用「+」按钮 */
  stock?: number
  /** 体验锚点 */
  experience_id?: string
  experience_name?: string
  /** 产地锚点，交给 PoiImage 取该乡村的封面图 */
  poi_id?: string
  poi_name?: string
}

/**
 * 订单明细。
 *
 * 这里的 `unit_price` / `product_name` 是**下单时的快照**，
 * 与 CartItem 的"当前价格"语义正好相反：产品后来调价，历史订单金额不能跟着变。
 */
export interface OrderItem {
  id: number
  product_id: string
  /** 体验锚点（下单时快照）。订单行必须能回答"这件东西来自哪次体验" */
  experience_id?: string
  experience_name?: string
  /** 产地锚点（下单时快照） */
  poi_id?: string
  poi_name?: string
  product_name: string
  spec?: string
  unit_price: number
  quantity: number
  subtotal: number
}

/** 订单状态。只有两个：待发货 → 已发货。不做支付与物流状态 */
export type OrderStatus = 'PENDING' | 'SHIPPED'

export interface Order {
  id: number
  /** 对外单号，形如 HY20260925004217 */
  order_no: string
  user_id: number
  /** 仅运营端有值 */
  buyer_nickname?: string
  buyer_email?: string
  status: OrderStatus
  /** 状态中文名，服务端算好，前端不维护映射表 */
  status_label: string
  item_count: number
  total_amount: number
  /** 收货信息（下单时快照，不随地址变更而变） */
  receiver_name: string
  receiver_phone: string
  receiver_address: string
  remark?: string
  /** TRIP 到访当场带走 / REPURCHASE 离境复购 */
  channel: string
  shipped_at?: string
  created_at?: string
  items: OrderItem[]
}

