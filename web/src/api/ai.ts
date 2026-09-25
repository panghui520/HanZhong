import type { AiHealth, QaEvent, QaHandlers } from '@/types'
import { ApiError, request } from './http'

/**
 * M3 文旅知识问答的接口层。
 *
 * 与 citypack.ts 的区别：这里除了普通 JSON 请求，还有一个**流式**接口。
 * 流式不能用 fetch + res.json()，必须自己读 ReadableStream 并按 SSE 分帧。
 * 分帧逻辑封在这里，页面只拿到 onMeta / onDelta / onDone / onError 回调，
 * 不需要知道 SSE 长什么样。
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
 * 发起一次流式问答。
 *
 * 返回一个 abort 函数，调用它可中断生成（用户切走页面或点"停止"时用）。
 *
 * 为什么不用 EventSource：EventSource 只支持 GET，问题得放在查询串里，
 * 中文要编码、长问题会撞 URL 长度上限，而且会被浏览器日志与代理记下来。
 * 用 fetch 发 POST、自己解 SSE，语义更对。
 */
export function ask(question: string, handlers: QaHandlers): () => void {
  const controller = new AbortController()

  void (async () => {
    let res: Response
    try {
      res = await fetch(`${API_ROOT}${AI_PATH}/qa`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({ question }),
        signal: controller.signal,
      })
    } catch (e) {
      if (controller.signal.aborted) return
      handlers.onError?.('无法连接后端服务，请确认 server-java 已启动（默认 8080）')
      return
    }

    if (!res.ok) {
      handlers.onError?.(`请求失败：HTTP ${res.status}`)
      return
    }
    if (!res.body) {
      handlers.onError?.('浏览器不支持流式响应')
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
          dispatch(frame, handlers)
          boundary = buffer.indexOf('\n\n')
        }
      }
      // 收尾：服务端若没有以空行结束，最后残留的一帧也要处理
      if (buffer.trim()) dispatch(buffer, handlers)
    } catch (e) {
      if (!controller.signal.aborted) {
        handlers.onError?.('连接中断，回答可能不完整')
      }
    } finally {
      reader.releaseLock()
    }
  })()

  return () => controller.abort()
}

/** 解析一帧 SSE，取出 data: 行并派发 */
function dispatch(frame: string, handlers: QaHandlers) {
  const payload = frame
    .split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
    .join('')

  if (!payload) return

  let event: QaEvent
  try {
    event = JSON.parse(payload) as QaEvent
  } catch {
    // 单帧坏掉不该让整轮回答失败，丢掉它继续读后面的
    return
  }

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
}

/** 复用的错误类型导出，页面判断错误码时不必再从 http.ts 引入 */
export { ApiError }
