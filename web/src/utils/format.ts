/**
 * 展示层的格式化小工具（M6 订单流转起抽）。
 *
 * ============================================================
 * 为什么要单独抽出来
 * ============================================================
 * `when()` 原本在 `views/portal/Orders.vue` 与 `views/admin/Orders.vue`
 * 各写了一遍，M6 加上订单详情页就变三份。三份"只到分钟"的实现
 * 迟早会有一处被改成"带秒"或"带 T"，于是同一个订单号在列表页和
 * 详情页显示成两种样子 —— 用户会以为是两笔不同的时间。
 *
 * `mediaSrc()` 则是为了统一一个真实存在的**两种约定**：
 * 后端出库的图片地址有的是完整 URL（`/api/media/review/1/a.jpg`，
 * 见 `OrderReviewVO`），有的是相对路径（`review/1/a.jpg`，
 * 见上传接口 `POST /api/reviews/images` 的返回值）。让每个页面
 * 自己判断"要不要加前缀"，迟早有人忘了加，图片就 404。
 */

/** 媒体文件在服务端的访问前缀。与后端 `MediaStorageService.URL_PREFIX` 一致 */
const MEDIA_PREFIX = '/api/media/'

/**
 * 把图片路径转成可直接用于 `<img src>` 的地址。
 *
 * **两种入参都能吃**：
 *   - `review/1/a.jpg`        → `/api/media/review/1/a.jpg`
 *   - `/api/media/review/1/a.jpg` → 原样返回
 *   - `https://cdn.example.com/x.jpg` → 原样返回
 *
 * 之所以要兼容，是因为上传接口返回相对路径（服务端要用它做
 * `..` 穿越校验，不能让客户端自由拼前缀），而出库时后端已经拼好了。
 * 同一个页面上"刚上传还没提交的预览图"和"已保存的图"两种都会出现，
 * 让调用方去区分只会引入 bug。
 *
 * 空值返回空字符串，调用方可以用 `v-if` 判断要不要渲染 `<img>`。
 */
export function mediaSrc(path?: string | null): string {
  if (!path) return ''
  const p = String(path).trim()
  if (!p) return ''
  // 已经带了协议（http/https//data:）或已经是我们自己的前缀，就别再拼一层
  if (/^(https?:)?\/\//i.test(p) || p.startsWith(MEDIA_PREFIX) || p.startsWith('data:')) {
    return p
  }
  return MEDIA_PREFIX + p.replace(/^\/+/, '')
}

/**
 * 时间戳显示：只到分钟。
 *
 * 秒对用户没意义（"19:47:12" 和 "19:47:38" 是同一件事），
 * 反而让列表看起来更乱。后端返回的是 ISO 形如 `2026-09-25T19:47:12`，
 * 这里只做 `T` → 空格，**不做时区转换** —— 后端返回的就是本地时间
 * （`LocalDateTime`），用 `new Date()` 再格式化会被浏览器按 UTC 解释一次，
 * 演示时会看到时间差 8 小时。
 */
export function when(s?: string | null): string {
  if (!s) return ''
  const t = String(s).replace('T', ' ')
  return t.length >= 16 ? t.slice(0, 16) : t
}

/**
 * 相对时间：把"还有多久"说成人话。
 *
 * 只用于**倒计时**（待付款剩余时间）这类必须一眼看懂的场合，
 * 不用在订单列表的时间戳上 —— 那里用 `when()` 更准确。
 *
 * @param seconds 剩余秒数。<= 0 返回"已超时"
 */
export function countdown(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return '已超时'
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/**
 * 转义 HTML 实体。**要往 `v-html` / `innerHTML` 里拼数据包文本时，先过它。**
 *
 * 抽出来是因为这件事现在有两个调用方，且第二个调用方（互动地图的 Marker，
 * 见 `utils/mapPin.ts`）是**拼字符串**而不是模板 —— 那里没有任何框架兜底，
 * 漏一次转义就是一个注入点。转义规则只能有一份。
 *
 * 五个字符都转（含引号）：调用方可能把值拼进属性，只转尖括号不够。
 */
export function escapeHtml(text: string): string {
  return String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 模型回答的极简内联格式：先转义 HTML，再把 `**加粗**` 换成 `<strong>`。
 *
 * **不是 Markdown 渲染器** —— 只处理模型实际会用的这一种标记。刻意不引
 * 一个 markdown 库：那会连带处理链接、图片、HTML 内联，而回答里的链接
 * 必须来自知识库元数据（见 `_source_payload`），交给模型写就有编造的可能。
 *
 * 先转义再插标签，所以模型输出里的尖括号不会变成标签 —— 这个函数的结果
 * 是配 `v-html` 用的，顺序反了就是一个注入点。
 *
 * 原本在 `views/portal/Assistant.vue` 里写了一份，M4 的旅游助手页要用同一种
 * 呈现（不然同一个模型、同一套提示词，在知识问答页是粗体、在助手页是一串
 * 星号）。两个页面各写一份迟早会有一处被改（比如加上 `*斜体*`），
 * 于是同一句话在两页显示成两种样子 —— 用户会以为是两个不同的模型。
 */
export function inlineText(text: string): string {
  return escapeHtml(text).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
}
