import type { Result } from '@/types'

/**
 * 后端基址。
 * 开发期由 Vite 代理转发到 8080（见 vite.config.ts 的 server.proxy），
 * 生产同源部署时 /api 指向同一域名下的后端，两边都不用改代码。
 */
const BASE = '/api'

/** 接口错误。code 沿用后端错误码，-1 / -2 是前端本地的网络与解析错误 */
export class ApiError extends Error {
  readonly code: number

  constructor(message: string, code: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/**
 * 统一请求。
 *
 * 后端约定 HTTP 恒为 200、业务结果看 body.code，所以这里只需要一条判定分支，
 * 页面也不必同时处理"HTTP 报错"和"业务报错"两套情况。
 * 三种失败分别给出可执行的提示：连不上后端 / 路径写错 / 业务失败。
 */
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${BASE}${path}`, { headers: { Accept: 'application/json' }, ...init })
  } catch {
    throw new ApiError('无法连接后端服务，请确认 server-java 已启动（默认 8080）', -1)
  }

  if (!res.ok) {
    throw new ApiError(`请求失败：HTTP ${res.status}`, res.status)
  }

  let body: Result<T>
  try {
    body = (await res.json()) as Result<T>
  } catch {
    throw new ApiError('响应不是合法 JSON，请检查接口路径是否正确', -2)
  }

  if (body.code !== 0) {
    throw new ApiError(body.message || '服务返回异常', body.code)
  }
  return body.data
}
