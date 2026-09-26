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
 * llm（配了模型）→ cache（命中预生成答案）→ extractive（摘录原文）→ no_answer（这一问没答上）。
 * 页面要如实显示当前是哪一档，用户才知道这个回答可信到什么程度。
 *
 * `no_answer` 的**口径**（2026-09-26 起收窄）：它现在只表示
 * "离线演示模式下、这一问本来需要模型"——配了模型时不会出现。
 * 以前它还兼着"知识库没写过这个话题"的意思，那条判定已经挪去 `route`，
 * 因为"知识库没写过"不等于"系统答不了"（"你是 AI 吗"就属于这种）。
 */
export type QaMode = 'llm' | 'cache' | 'extractive' | 'no_answer'

export const QA_MODE_LABEL: Record<QaMode, string> = {
  llm: '大模型生成',
  cache: '预生成答案',
  extractive: '原文摘录',
  no_answer: '离线模式未答',
}

/**
 * 回答链路：这一问**用没用知识库**。与 `mode` 正交 ——
 * `mode` 说"这条回答由谁产出"，`route` 说"模型被允许用哪一部分知识"。
 *
 *   rag         —— 知识库覆盖这个问题。检索 → 把命中的切片作为【资料】交给模型
 *   constrained —— 涉及本地事实、但知识库**可能**没写过（`gaps` 非空）。
 *                  照常检索、照常给【资料】，只是提示词更严：
 *                  资料答不了的部分要明说"缺少可靠依据"，不许编造本地事实
 *   general     —— 与汉中无关（问系统自身 / 通用概念 / 寒暄）。
 *                  **不检索**，直接把问题交给模型
 *
 * 页面据此显示"知识库增强 / 通用问答"：`constrained` 与 `rag` 都用知识库，
 * 区别只在提示词的严格程度（属于实现细节，不占用徽标），所以两者共用同一个
 * 用户可见标签，细节放在 `QA_ROUTE_HINT` 里作为悬停说明。
 */
export type QaRoute = 'rag' | 'constrained' | 'general'

export const QA_ROUTE_LABEL: Record<QaRoute, string> = {
  rag: '知识库增强',
  constrained: '知识库增强',
  general: '通用问答',
}

export const QA_ROUTE_HINT: Record<QaRoute, string> = {
  rag: '检索了知识库，并把命中的资料交给模型综合作答',
  constrained: '涉及本地事实，但知识库可能没有依据：仍给了资料，提示词要求模型不得编造',
  general: '与汉中无关的问题，未检索知识库，由模型直接回答',
}

/**
 * 来源性质。决定来源卡片给什么标签、给不给外链：
 *   detail  —— 有可核对的具体页面（12 篇手写文档，front-matter 里带真实深链）
 *   site    —— 只有站点级参考：该站不提供这一条的独立页面
 *   dataset —— 项目数据包自有，外部没有对应出处
 *
 * 分三档是因为"看起来可核对、实际不可核对"比明说没有出处更糟：
 * 一条点进去找不到对应内容的链接，会让用户以为自己的核对已经做过了。
 */
export type QaSourceKind = 'detail' | 'site' | 'dataset'

export const QA_SOURCE_KIND_LABEL: Record<QaSourceKind, string> = {
  detail: '原文可查',
  site: '站点参考',
  dataset: '数据包自有',
}

/**
 * 文档类型（`doc_type`）→ 中文名。
 *
 * 放在这里而不是页面里：M3 的知识问答页与 M4 的助手页**都要显示来源**，
 * 各自抄一份的结果是新增一类文档时只改了一处，另一处直接显示英文标识
 * （`?? source.doc_type` 的兜底让这件事静默发生，不报错）。
 */
export const QA_DOC_TYPE_LABEL: Record<string, string> = {
  city_doc: '城市知识',
  poi: '资源点',
  experience: '乡村体验',
  product: '乡村产品',
}

/**
 * 回答引用的来源。
 *
 * **全部字段都来自知识库元数据，没有任何一处由模型生成** ——
 * 模型只组织答案正文，网址一旦交给它写就有编造的可能，而来源的全部意义是核对。
 */
export interface QaSource {
  title: string
  /** city_doc / poi / experience / product，用于分组显示 */
  doc_type: string
  /** 简短介绍。构建期从文档里抽好，可能为空 */
  snippet: string
  source_name: string
  /** 外部出处链接。dataset 档为空 —— 那时给的是站内链接 */
  source_url: string
  source_kind: QaSourceKind
  /** 站内跳转用：拼成 `/poi/{poi_id}`。city_doc 没有（那类只有外链） */
  poi_id: string
  /** 相关度 0—1；缓存模式下为 null（不经过检索） */
  score: number | null
}

export interface QaMetaEvent {
  type: 'meta'
  mode: QaMode
  /** 这一问用没用知识库（rag / constrained 用了，general 没用） */
  route: QaRoute
  /**
   * 这条回答**实际由谁产出**：模型名（真调了 DeepSeek）/ `cache` /
   * `extractive` / `none`。给"到底有没有调模型"一个可直接断言的字段，
   * 不用从回答文本里猜 —— 措辞和长度都会骗人。
   */
  generator: string
  sources: QaSource[]
  /** 检索到的候选条数。`general` 链路不检索，恒为 0 */
  retrieved: number
  /** 首条候选的归一化 BM25 相关度 */
  top_score: number
  /**
   * 问题里知识库完全没写过的实词。
   * **注意它现在只是信号，不是闸门**：非空表示"这一问涉及本地事实但知识库可能没依据"，
   * 会走 `constrained` 链路并把事实约束写进提示词；**不再**因此拒答。
   */
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
  /** 按**文档数**的类型分布。`by_type` 是切片数，界面上的"篇/处/项/款"要用这个 */
  by_type_docs: Record<string, number>
  embedder: string
  lexical: { chunks: number; terms: number; avg_tokens: number }
  llm_configured: boolean
  demo_mode: boolean
  model: string | null
  cache_entries: number
  stale: boolean
  stale_hint?: string
  error?: string
  /**
   * M4：高德密钥是否已配置。**AI 服务未启动时这两个字段整个缺省** ——
   * 所以页面判断"能不能用地图"要写成 `health?.agent?.amap_configured === true`，
   * 而不是 `!health.amap_configured`（后者在 health 为 null 时会炸）。
   */
  amap?: { configured: boolean }
  /** M4：旅游助手的可用工具与示例问题（示例按当前能力生成，见 agent.py） */
  agent?: {
    amap_configured: boolean
    tools: string[]
    samples: { title: string; items: string[] }[]
  }
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

/**
 * 订单状态。完整状态机：
 *
 * ```
 * PENDING_PAYMENT 待付款 ──30 分钟未付──▶ CANCELLED 已取消
 *      │ 付款
 *      ▼
 * PENDING_SHIPMENT 待发货 ──运营发货──▶ SHIPPED 已发货 ──确认收货──▶ COMPLETED 已完成
 *      │                                   │                              │
 *      └──────────申请退款─────────────────┘                      评价（星级+文字+图片）
 *                     │
 *                     ▼
 *          REFUND_REQUESTED 退款中 ──管理员同意──▶ REFUNDED 已退款
 *                                  └─管理员拒绝──▶ 退回申请前的状态
 * ```
 *
 * 流转规则以服务端为准，前端**不要**自己判断"这个状态能不能取消"——
 * 服务端已经把当前能做的操作算进 `available_actions` 了。
 */
export type OrderStatus =
  | 'PENDING_PAYMENT'
  | 'PENDING_SHIPMENT'
  | 'SHIPPED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REFUND_REQUESTED'
  | 'REFUNDED'

/**
 * 当前状态下可执行的操作，由服务端算好。
 *
 * 用户端会出现：PAY / CANCEL / CONFIRM_RECEIPT / REQUEST_REFUND / REVIEW
 * 运营端会出现：SHIP / HANDLE_REFUND
 *
 * 前端只做一件事：`actions.includes('PAY')` 决定按钮显不显示。
 * **不要再写一份"状态 → 按钮"的映射表** —— 那会和后端规则慢慢漂移，
 * 而漂移的后果是按钮该出现时没出现、该消失时还在（用户点了才报错）。
 */
export type OrderAction =
  | 'PAY'
  | 'CANCEL'
  | 'CONFIRM_RECEIPT'
  | 'REQUEST_REFUND'
  | 'REVIEW'
  | 'SHIP'
  | 'HANDLE_REFUND'

/** 订单评价。一单一评，靠库里的唯一键保证 */
export interface OrderReview {
  id: number
  order_id: number
  /** 星级 1..5 */
  rating: number
  content?: string
  /**
   * 图片地址，**服务端已拼成可直接用于 `<img src>` 的完整 URL**
   * （形如 `/api/media/review/3/xxx.jpg`）。
   *
   * 注意与提交时不对称：提交评价时传的是上传接口返回的**相对路径**，
   * 因为服务端要拿它做 `..` 穿越校验，不能让客户端自由拼前缀。
   */
  images: string[]
  created_at?: string
}

/**
 * 我的订单计数（顶栏「我的订单」角标）。
 *
 * `pending` 是**需要用户动手**的订单数（待付款 + 已发货），
 * 角标显示的是它，不是 `total` —— 已完成的订单用户无事可做，
 * 算进去角标就只增不减，变成一个永远消不掉的红点。
 */
export interface OrderCount {
  total: number
  pending: number
}

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
  /** 当前状态下能做的操作，服务端算好。见 OrderAction 的注释 */
  available_actions: OrderAction[]
  item_count: number
  total_amount: number
  /** 收货信息（下单时快照，不随地址变更而变） */
  receiver_name: string
  receiver_phone: string
  receiver_address: string
  remark?: string
  /** TRIP 到访当场带走 / REPURCHASE 离境复购 */
  channel: string

  /** 支付截止时刻。待付款时前端拿它显示倒计时 */
  pay_deadline?: string
  paid_at?: string

  shipped_at?: string
  /** 物流三要素，发货时由运营填写 */
  carrier?: string
  eta_days?: number
  tracking_no?: string

  received_at?: string

  cancelled_at?: string
  /** TIMEOUT 超时未付自动取消 / USER 用户主动取消 */
  cancel_reason?: string

  refund_reason?: string
  /** 管理员对退款的处理说明 / 拒绝理由 */
  refund_reply?: string
  refund_at?: string
  refund_handled_at?: string

  created_at?: string
  items: OrderItem[]
  /** 已评价时带上；未评价则整个字段缺省（后端 non_null 序列化） */
  review?: OrderReview
}

/* ============================================================
 * M4 AI 旅游助手（Agent）
 *
 * 与 M3 问答（QaEvent）是**两套独立的事件协议**，刻意不复用：
 * M3 的 meta 描述"检索行为"（route / sources / gaps），
 * M4 的 meta 描述"用了哪个工具"（tools / amap），done 里还多一个 tool。
 * 合并成一套的话，前端每处都要判断"这次有没有 tool 字段"，
 * 而 M3 已验收的契约也会被改动。
 *
 * 服务端实现见 server-ai/app/agent.py，事件顺序：
 *   meta → tool(running) → tool(done|error) → cards(可选) → delta × N → done
 * 任一步出错则发 error 并结束。
 * ============================================================ */

/** 已知工具名。`none` 表示"不需要工具，直接由模型回答" */
export type AgentToolName = 'knowledge_search' | 'search_nearby' | 'search_poi' | 'none'

export const AGENT_TOOL_LABEL: Record<string, string> = {
  knowledge_search: '本地知识库',
  search_nearby: '高德 · 附近搜索',
  search_poi: '高德 · 关键词搜索',
  plan_itinerary: '本地数据包 · 行程规划',
  none: '直接回答',
}

export interface AgentMetaEvent {
  type: 'meta'
  mode: QaMode
  /** 与 M3 同义：这一答实际由谁产出（模型名 / extractive） */
  generator: string
  city: string
  /** 高德密钥是否已配置。false 时 search_nearby / search_poi 不可用 */
  amap: boolean
  /** 本次真正可用的工具名。与 meta.amap 一致，不单独维护一份 */
  tools: string[]
}

/**
 * 工具调用状态。
 *
 * 单独成一条事件而不是塞进 meta：工具是**异步且可能失败**的
 * （高德超时、地理编码没命中），前端要在"正在查"和"查完了"之间切换显示，
 * 而 meta 只在开头发一次。
 */
export interface AgentToolEvent {
  type: 'tool'
  name: string
  status: 'running' | 'done' | 'error'
  /** 服务端生成的中文说明，例："正在搜索「汉中高铁站」附近…" */
  label: string
  /** 查到的条数（running 时没有） */
  count?: number
  elapsed_ms?: number
  error?: string
}

/**
 * 一张推荐卡片。字段**逐字来自高德**，不经过模型转述。
 *
 * 为什么不由模型把结构化数据说出来再解析回来：用户要照着 `tel` 打电话、
 * 照着 `address` 导航，中间任何一步转述出错都是"酒店名少个字"这种
 * 现场很难解释的问题。所以模型只管组织语言，卡片走独立通道。
 *
 * `source` 恒为 `amap`，留这个字段是为了以后接自采数据时能分清来源。
 */
export interface AgentCard {
  poi_id: string
  name: string
  address: string
  /** 区县，例"洋县" */
  district: string
  business_area: string
  typecode: string
  /** 中文类别，例"宾馆酒店"。由 typecode 前两位映射，不是高德原文 */
  category: string
  /**
   * 这张卡片自己的类别（M4 阶段二新增）。取值是 {@link AgentPoiCardKind}，
   * 由 typecode 前两位决定。
   *
   * **不要用 `cards` 事件上的 kind 代替它**：事件上的 kind 是"这批结果整体
   * 是什么"（取第一条的大类），而"这张卡片能不能被选为住处"必须逐张判断 ——
   * 否则混合结果里只要第一条不是酒店，那张酒店卡片就不会有「选择」按钮。
   */
  kind: AgentPoiCardKind
  lng: number | null
  lat: number | null
  distance_m: number | null
  tel: string
  /** 高德返回的是字符串，可能是 "" —— 不要当成 number 用 */
  rating: string
  cost: string
  tag: string
  photo: string
  source: string
}

/**
 * 一张**来源**卡片（走知识库那条路时下发）。
 *
 * 与 {@link AgentCard} 是两种东西，所以 `cards` 事件是联合类型而不是
 * 一个大而全的结构：来源是"这条依据从哪来"（可核对），
 * 推荐卡是"这个地点在哪"（可导航）。字段几乎不重叠，
 * 硬合成一个类型的结果是两边都要写一堆可选字段。
 *
 * 字段与 M3 的 `QaSource` 一致（后端两处共用 `_source_payload`），
 * 但**刻意不复用 QaSource 类型**：M3 的 `/ai/qa` 与 M4 的 `/ai/agent`
 * 是两套协议，共用类型会让"改一处影响两处"，而这两个端点本可以各自演进。
 */
export interface AgentSourceCard {
  title: string
  doc_type: string
  source_name: string
  source_url: string
  source_kind: QaSourceKind
  snippet: string
  /** 站内链接用。数据包自有那几档靠它跳到 /poi/{poi_id} */
  poi_id: string
  score: number | null
}

/**
 * 高德 POI 卡片的类别。**与 {@link AgentCardKind} 分开**：
 * 这个集合里的每一种都是"一个地点"，所以它才能被当成 AgentCard 的 kind；
 * 而 `knowledge`（一条来源）和 `itinerary`（一份行程）都不是地点，
 * 它们的字段与 AgentCard 几乎不重叠，混进同一个类型只会到处写可选字段。
 *
 * 取值由后端 POI 类型码前两位映射，见 server-ai/app/tools.py 的 `_KIND_BY_MAJOR`。
 */
export type AgentPoiCardKind = 'hotel' | 'restaurant' | 'attraction' | 'transport' | 'poi'

/**
 * 卡片类别。**这是一个封闭集合**，由后端定义：
 *   - `knowledge` —— 知识库来源（`AgentSourceCard`）
 *   - `itinerary` —— 一份按天排的行程（`AgentItineraryCard`）
 *   - 其余五种 —— 高德 POI（`AgentCard`），由 POI 类型码前两位映射
 * 见 server-ai/app/tools.py 的 `_KIND_BY_MAJOR` 与 `_knowledge_search` / `_plan_itinerary`。
 */
export type AgentCardKind = 'knowledge' | 'itinerary' | AgentPoiCardKind

/**
 * 行程里的一站。字段**逐字来自数据包**（`citypack/<city>/pois.json`），
 * 不经过模型 —— 与 POI 卡片同一个理由：用户会照着 `name` 去导航。
 */
export interface AgentItineraryStop {
  poi_id: string
  name: string
  district: string
  /** 景区级别，如 "4A"。乡村业态是"省级乡村旅游示范村"这类，不是低等级 */
  level: string
  /** 建议游览时长（分钟） */
  duration_min: number
  open_hours: string
  /** 0 = 免费；null = 未知。**两者必须分开显示** */
  ticket_price: number | string | null
  summary: string
  tags: string[]
}

export interface AgentItineraryDay {
  day: number
  /** 这一天的区县。**同一天只会有一个** —— 这是行程算法唯一的硬保证 */
  district: string
  minutes: number
  over_budget: boolean
  stops: AgentItineraryStop[]
}

/**
 * 一份行程。**它是一张卡、不是一批卡** —— 拆成"每个景点一张卡"就丢掉了
 * 天数与区县这两个最关键的字段，而用户要看的恰恰是"第一天在汉台区、
 * 第二天在南郑区"。
 *
 * `requested_days`（用户要几天）与 `days.length`（真的排出来几天）分开：
 * 数据包里的点不够时算法会少排，界面要能如实显示"要 3 天、排出 2 天"，
 * 而不是把 2 天冒充成 3 天。
 */
export interface AgentItineraryCard {
  kind: 'itinerary'
  title: string
  requested_days: number
  days: AgentItineraryDay[]
  minutes_per_day: number
  preference: string
  notes: string[]
}

/**
 * 卡片按类分组下发。`kind` 是判别式，**前端必须按它分支渲染**：
 * `knowledge` 时 `items` 是来源卡片、`itinerary` 时是**一张**行程卡
 * （它本身就是数组里唯一那项）、其余是高德 POI 卡片。
 * 三种卡片字段几乎不重叠，混着渲染的结果是整片空白。
 */
export type AgentCardsEvent =
  | { type: 'cards'; kind: 'knowledge'; items: AgentSourceCard[] }
  | { type: 'cards'; kind: 'itinerary'; items: AgentItineraryCard[] }
  | {
      type: 'cards'
      kind: AgentPoiCardKind
      items: AgentCard[]
    }

export interface AgentDeltaEvent {
  type: 'delta'
  text: string
}

export interface AgentDoneEvent {
  type: 'done'
  mode: QaMode
  /** 本次实际用的工具，便于前端显示"由高德附近搜索回答" */
  tool: string
  elapsed_ms: number
}

export interface AgentErrorEvent {
  type: 'error'
  message: string
}

export type AgentEvent =
  | AgentMetaEvent
  | AgentToolEvent
  | AgentCardsEvent
  | AgentDeltaEvent
  | AgentDoneEvent
  | AgentErrorEvent

/** 流式回调。比 M3 多 onTool / onCards 两个钩子 */
export interface AgentHandlers {
  onMeta?: (event: AgentMetaEvent) => void
  onTool?: (event: AgentToolEvent) => void
  onCards?: (event: AgentCardsEvent) => void
  onDelta?: (text: string) => void
  onDone?: (event: AgentDoneEvent) => void
  onError?: (message: string) => void
}

/** 一轮助手对话。与 QaTurn 分开：它多带工具状态与卡片 */
export interface AgentTurn {
  id: number
  question: string
  answer: string
  meta: AgentMetaEvent | null
  /** 最近一次工具事件。running → done 就地更新，不追加新行 */
  tool: AgentToolEvent | null
  /** 高德 POI 卡片。走知识库 / 行程那条路时为空 */
  cards: AgentCard[]
  /** 知识库来源卡片。走高德 / 行程那条路时为空 */
  sources: AgentSourceCard[]
  /**
   * 行程卡片。**单独一格，不塞进 cards** —— 它是一份整体方案（哪天去哪几个点），
   * 而 cards 是"一批同类的点"。混在一起会让"逐张渲染 + 每张一个选择按钮"
   * 那套逻辑作用在一份行程上。
   */
  itinerary: AgentItineraryCard | null
  /** 卡片类别，模板据此决定渲染哪种卡片 */
  cardKind: string
  elapsedMs: number
  streaming: boolean
  error: string
}

/* ============================================================
 * M4 阶段二：当前行程与上下文
 *
 * 后端：`/api/trips/**`（需要登录），见 TripController / TripService。
 *
 * 这一组类型存在的理由只有一条：**让助手记得住**。用户上一轮在卡片上
 * 点了「选择酒店」，下一轮问「这附近有什么好吃的」，服务端就能用
 * 已选酒店的坐标去搜附近，而不是反问他住哪。
 *
 * 注意这是**用户自己的数据**，所以整组接口都要登录；未登录时页面照常
 * 可用，只是没有记忆（见 Agent.vue 的说明）。
 * ============================================================ */

/**
 * 本次行程已选的住处。
 *
 * **坐标是字符串**，不是 number。这两个值的唯一用途是被回读并原样拼进
 * 高德的 `location` 参数（`"经度,纬度"`），走 number 就要在
 * "序列化 → 前端 → 反序列化 → 拼字符串"这条链上反复经过二进制浮点，
 * 每一跳都可能出现 107.01999999999999 这种非法坐标。后端同理，
 * 见 SelectedHotelVO 的说明。
 */
export interface SelectedHotel {
  poi_id: string
  name: string
  address: string
  /** 经度。高德坐标是"经度,纬度"，经度在前 */
  longitude: string
  latitude: string
  /** 拼好的 "经度,纬度"，与高德 location 参数格式一致 */
  location: string
  /** 数据来源，当前恒为 amap */
  source: string
}

/** 当前行程与它的上下文 */
export interface TripContext {
  trip_id: number
  /** 业务编码，形如 2026-hanzhong-001 */
  trip_code: string
  destination: string
  destination_code: string
  /** ACTIVE / ARCHIVED */
  status: string
  /** 已选住处。没选时这个字段整个不出现（后端配了 non_null） */
  hotel?: SelectedHotel
  /** not_booked / external_pending / booked / cancelled */
  hotel_booking_status: string
}

/**
 * 预订状态的中文。
 *
 * 当前只会出现 `not_booked` —— 本系统**不代办预订**，也不接美团/携程的
 * 内部接口。这一列现在建出来、并且要参与提示词，是因为助手得知道
 * "这家还没订"才敢说"建议先预订"；等阶段五真的加了"去第三方预订"的跳转，
 * 其余三档才会被写出来。
 */
export const HOTEL_BOOKING_LABEL: Record<string, string> = {
  not_booked: '未预订',
  external_pending: '已跳转第三方，待确认',
  booked: '已预订',
  cancelled: '已取消',
}

