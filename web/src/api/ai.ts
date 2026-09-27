import type {
  AgentEvent,
  AgentHandlers,
  AiHealth,
  OpsAnalysis,
  QaEvent,
  QaHandlers,
} from '@/types'
import { ApiError, authHeaders, request } from './http'

/**
 * AI 能力（M3 知识问答 + M4 旅游助手 + M7 运营解读）的接口层。
 *
 * 与 citypack.ts 的区别：这里除了普通 JSON 请求，还有**流式**接口。
 * 流式不能用 fetch + res.json()，必须自己读 ReadableStream 并按 SSE 分帧。
 * 分帧逻辑封在这里，页面只拿到回调，不需要知道 SSE 长什么样。
 */

/**
 * 路径前缀要分两个常量，不能合成一个：
 * http.ts 的 request() 会自己拼上 `/api`，而流式问答走的是原生 fetch，不经过它。
 * 早先这里只写了一个 BASE = '/api/ai' 并同时用于两者，结果普通请求被拼成
 * `/api/api/ai/health`——后端返回 404，页面却只是"状态读不出来"，不报错，
 * 很容易看漏。分开写之后，两条路径各自完整、可对照。
 */
const API_ROOT = '/api'
const AI_PATH = '/ai'

/** 知识库健康状态。AI 服务没起来时后端返回 3001，这里转成异常由页面兜住 */
export function getAiHealth() {
  return request<AiHealth>(`${AI_PATH}/health`)
}

/** 推荐问题。知识库不可用时后端返回空数组，页面隐藏推荐区即可 */
export function getSuggestions() {
  return request<string[]>(`${AI_PATH}/suggestions`)
}

/**
 * M7 运营解读：把快照里的一组指标讲成三段式。
 *
 * **非流式**，与上面两个问答接口不同 —— 那是"边生成边看"的对话，
 * 这是"点一下出一个结论"的报表动作。非流式才能一次拿到完整结构
 * （三段正文 + 依据 + 模式），页面拿到就渲染，不必自己把 SSE 碎片拼成对象。
 *
 * **路径为什么是 `/admin/ops/analyze` 而不是 `/api/ai/analyze/ops`：**
 * `/api/ai/**` 在后端是整段公开的（问答对游客开放），而这里的返回正文与
 * 「依据」里带着销售额、复购率、待处置风险数 —— 是**运营数据**的解读。
 * 放在 `/api/admin/**` 下才会被统一收进 OPERATOR 角色。所以这个函数
 * **必须带令牌**：未登录会拿到 4001，`request()` 会自动带上 Authorization。
 *
 * 失败不在这里兜成"没有解读"：4001/3001/3002 都是**真的出错了**，
 * 该让页面显示错误；而"模型没解读出来"不是错误 —— 后端会正常返回 200，
 * 只是 `mode` 为 `unavailable`、`sections` 为空。两者别混。
 *
 * @param focus 解读维度。取值见 `OPS_FOCUSES`，后端与 Python 各有白名单校验
 */
export function analyzeOps(focus: string) {
  return request<OpsAnalysis>('/admin/ops/analyze', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ focus }),
  })
}

/**
 * 发起一次 M3 流式问答。
 *
 * 返回一个 abort 函数，调用它可中断生成（用户切走页面或点"停止"时用）。
 */
export function ask(question: string, handlers: QaHandlers): () => void {
  return openStream('/qa', question, handlers.onError, (frame) => {
    const event = parseFrame<QaEvent>(frame)
    if (!event) return
    switch (event.type) {
      case 'meta':
        handlers.onMeta?.(event)
        break
      case 'delta':
        handlers.onDelta?.(event.text)
        break
      case 'done':
        handlers.onDone?.(event)
        break
      case 'error':
        handlers.onError?.(event.message)
        break
    }
  })
}

/**
 * 发起一次 M4 旅游助手对话。
 *
 * 与 {@link ask} 共用同一套分帧与错误处理，差别只在路径与事件类型 ——
 * 两套并行的流式解析代码迟早在"跨 chunk 被切断的帧"这类边界上分叉，
 * 而那种 bug 表现为"偶尔丢一句话"，最难查。
 *
 * 事件顺序：meta → tool(running) → tool(done|error) → cards(可选)
 *          → delta × N → done
 */
export function askAgent(question: string, handlers: AgentHandlers): () => void {
  return openStream('/agent', question, handlers.onError, (frame) => {
    const event = parseFrame<AgentEvent>(frame)
    if (!event) return
    switch (event.type) {
      case 'meta':
        handlers.onMeta?.(event)
        break
      case 'tool':
        handlers.onTool?.(event)
        break
      case 'cards':
        handlers.onCards?.(event)
        break
      case 'delta':
        handlers.onDelta?.(event.text)
        break
      case 'done':
        handlers.onDone?.(event)
        break
      case 'error':
        handlers.onError?.(event.message)
        break
    }
  })
}

/**
 * 两个流式端点的共同实现：POST 一个问题，按 SSE 分帧后逐帧回调。
 *
 * 为什么不用 EventSource：EventSource 只支持 GET，问题得放在查询串里，
 * 中文要编码、长问题会撞 URL 长度上限，而且会被浏览器日志与代理记下来。
 * 用 fetch 发 POST、自己解 SSE，语义更对。
 *
 * @param path      端点后缀，如 `/qa` / `/agent`
 * @param question  用户问题
 * @param onError   传输层错误（连不上、非 200、流中断）的回调
 * @param onFrame   每解析出一帧就回调一次，帧内容由调用方按各自协议分派
 * @returns         中断函数
 */
function openStream(
  path: string,
  question: string,
  onError: ((message: string) => void) | undefined,
  onFrame: (frame: string) => void,
): () => void {
  const controller = new AbortController()

  void (async () => {
    let res: Response
    try {
      res = await fetch(`${API_ROOT}${AI_PATH}${path}`, {
        method: 'POST',
        // 必须带令牌：`/api/ai/**` 是公开接口，但**登录与否会改变它做什么** ——
        // 带了令牌，Java 侧才知道"这是谁"，才能把当前行程的上下文（目的地、
        // 已选酒店的坐标）交给 Python。不带令牌它就以游客身份执行，
        // 助手在界面上永远记不住行程，而**接口本身仍然返回 200**，
        // 所以这个漏法不会报任何错，只能靠"选完酒店再问附近"这条链路才测得出来。
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...authHeaders(),
        },
        body: JSON.stringify({ question }),
        signal: controller.signal,
      })
    } catch {
      if (controller.signal.aborted) return
      onError?.('无法连接后端服务，请确认 server-java 已启动（默认 8080）')
      return
    }

    if (!res.ok) {
      onError?.(`请求失败：HTTP ${res.status}`)
      return
    }
    if (!res.body) {
      onError?.('浏览器不支持流式响应')
      return
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        // SSE 以空行分帧。注意要留下最后一个不完整的帧等下一批数据，
        // 否则跨 chunk 被切断的 JSON 会被当成坏数据丢掉。
        let boundary = buffer.indexOf('\n\n')
        while (boundary !== -1) {
          const frame = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          onFrame(frame)
          boundary = buffer.indexOf('\n\n')
        }
      }
      // 收尾：服务端若没有以空行结束，最后残留的一帧也要处理
      if (buffer.trim()) onFrame(buffer)
    } catch {
      if (!controller.signal.aborted) {
        onError?.('连接中断，回答可能不完整')
      }
    } finally {
      reader.releaseLock()
    }
  })()

  return () => controller.abort()
}

/**
 * 解析一帧 SSE，取出 data: 行并反序列化。
 *
 * 单帧坏掉返回 null 而不是抛错：一轮回答里丢一句不该让整段失败，
 * 后面的帧还是好的。
 */
function parseFrame<T>(frame: string): T | null {
  const payload = frame
    .split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
    .join('')

  if (!payload) return null

  try {
    return JSON.parse(payload) as T
  } catch {
    return null
  }
}

/** 复用的错误类型导出，页面判断错误码时不必再从 http.ts 引入 */
export { ApiError }
