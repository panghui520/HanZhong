import { request } from './http'
import type { AuthToken, AuthUser } from '@/types'

/**
 * M8 认证接口层。
 *
 * 这里**不含任何验证码判定逻辑** —— 验证码是否正确、是否过期、还剩几次，
 * 全部由后端回答。前端只做两件事：把用户输入送上去、把后端的话翻译成人话。
 *
 * 早先版本里前端会自己存一个 demo 验证码并本地比对，那等于把安全判定放在
 * 用户可以随意改的地方。现在 `/api/auth/code` 的响应体里根本没有验证码字段，
 * 前端想校验也无从校验 —— 这是有意的设计，不是遗漏。
 */

/** 发送注册验证码。响应体只有 sent/message，**不含验证码本身** */
export function sendRegisterCode(email: string) {
  return request<{ sent: boolean; message: string }>('/auth/code', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  })
}

/** 注册。成功后直接拿到令牌，不用再调一次登录 */
export function register(payload: {
  email: string
  code: string
  password: string
  nickname?: string
}) {
  return request<AuthToken>('/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

/** 登录 */
export function login(email: string, password: string) {
  return request<AuthToken>('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
}

/**
 * 取当前用户。
 *
 * 这个接口是"刷新页面仍保持会话"的关键：令牌存在 localStorage 里只能说明
 * "曾经登录过"，不能说明"还是有效的"（可能已过期、可能已被退出登录顶掉、
 * 可能账号被禁用）。所以应用启动时要用它向服务端确认一次。
 */
export function getMe() {
  return request<AuthUser>('/me')
}

/** 退出登录。服务端会把 token_version +1，让该用户所有历史令牌立即失效 */
export function logout() {
  return request<{ loggedOut: boolean }>('/me/logout', { method: 'POST' })
}
