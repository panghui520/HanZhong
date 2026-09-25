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
 * 令牌失效的通知钩子。
 *
 * 这里不直接 import session store，是为了**避免循环依赖**：
 * session.ts → api/auth.ts → api/http.ts，http.ts 再回头 import session 就成环了
 * （Pinia store 在模块顶层被引用时还会撞上"store 未安装"的报错）。
 * 改成由 App 启动时注册一个回调，方向就是单向的：
 * http.ts 只管"发现令牌失效了"这件事，谁处理由外部决定。
 */
type UnauthorizedHandler = () => void
let onUnauthorized: UnauthorizedHandler | null = null

export function setUnauthorizedHandler(fn: UnauthorizedHandler) {
  onUnauthorized = fn
}

/**
 * 需要登录的接口路径前缀：命中这些路径的 4001/4002 才触发全局登出。
 *
 * `/cart` 与 `/orders` 是 M6 的"我的数据"，未登录时后端返回 4001，
 * 应当把本地会话清掉并跳登录页 —— 否则用户会看到一个一直转圈的空页面。
 */
const PROTECTED_PREFIXES = ['/me', '/cart', '/orders']

function isProtected(path: string) {
  return PROTECTED_PREFIXES.some((p) => path === p || path.startsWith(`${p}/`))
}

/**
 * 取当前令牌。
 *
 * 同样为了避开循环依赖，不 import session store，直接读 localStorage ——
 * 那是会话的**唯一落盘位置**，读它不会读到过期副本。
 * 令牌很小时这样做的代价可以忽略；换来的是 http 层零依赖、可单测。
 */
function readToken(): string {
  try {
    const raw = localStorage.getItem('hanyou.auth')
    if (!raw) return ''
    const p = JSON.parse(raw) as { token?: unknown }
    return typeof p?.token === 'string' ? p.token : ''
  } catch {
    return ''
  }
}

/**
 * 统一请求。
 *
 * 后端约定 HTTP 恒为 200、业务结果看 body.code，所以这里只需要一条判定分支，
 * 页面也不必同时处理"HTTP 报错"和"业务报错"两套情况。
 * 四种失败分别给出可执行的提示：连不上后端 / 路径写错 / 业务失败 / 未登录。
 */
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  if (!headers.has('Accept')) headers.set('Accept', 'application/json')

  // 带上令牌。公开接口带了也无妨（后端不会因为多一个 Authorization 就拒绝），
  // 这样调用方不必区分"这个接口要不要登录"。
  const token = readToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let res: Response
  try {
    res = await fetch(`${BASE}${path}`, { ...init, headers })
  } catch {
    throw new ApiError('无法连接后端服务，请确认 server-java 已启动', -1)
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
    // 4001 未登录 / 4002 令牌无效（含过期、被退出登录作废、账号被禁用）。
    //
    // 只对**需要登录的接口**触发全局登出：公开接口也可能返回 4001 吗？不会。
    // 但假如将来后端把某个公开接口误配成需要认证，全局登出会让用户莫名其妙
    // 掉线——限定在受保护路径上，误伤面更小，问题也更容易被看见。
    if ((body.code === 4001 || body.code === 4002) && isProtected(path)) {
      onUnauthorized?.()
    }
    throw new ApiError(body.message || '服务返回异常', body.code)
  }
  return body.data
}
