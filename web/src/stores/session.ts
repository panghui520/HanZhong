import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as authApi from '@/api/auth'
import type { AuthToken } from '@/types'

/**
 * 会话状态（M8 真实认证）。
 *
 * 与旧版的区别：旧版是纯前端假登录，把账号直接当令牌存进 localStorage，
 * 任何账号密码都能进 —— 页面看起来"有登录"，但服务端根本不知道有这个人。
 * 现在令牌由 Spring Boot 签发，前端只负责保存和携带，无权判定自己是否合法。
 *
 * ---------------------------------------------------------------
 * 令牌存哪：localStorage
 * ---------------------------------------------------------------
 * 存 localStorage 而非 sessionStorage，是为了满足"刷新页面仍保持有效会话"
 * 与"关掉浏览器再打开还在"。代价是 XSS 能读到令牌——本项目没有用户生成内容
 * 注入点、也没有第三方脚本，这个代价可接受。生产环境若有富文本或外链脚本，
 * 应该改成 HttpOnly Cookie + CSRF Token。
 *
 * ---------------------------------------------------------------
 * "有令牌" ≠ "已登录"
 * ---------------------------------------------------------------
 * 令牌只是"曾经登录过"的证据。它可能已过期、可能因为该用户在其他设备
 * 退出登录而失效（后端 token_version 机制）、也可能账号已被禁用。
 * 所以 isLoggedIn 的判据是"有令牌且有用户信息"，而应用启动时还要用
 * restore() 向服务端确认一次——只有服务端认了，才算真的登录着。
 */

const KEY = 'hanyou.auth'

/** 旧假登录用的 key。读到就清理，见下面的 migrateLegacy() */
const LEGACY_KEY = 'hanyou.session'

interface Persisted {
  token: string
  user: AuthToken
}

export const useSessionStore = defineStore('session', () => {
  const token = ref<string>('')
  const user = ref<AuthToken | null>(null)
  /** 是否已经向服务端确认过会话（App 启动时做一次） */
  const ready = ref(false)

  const isLoggedIn = computed(() => token.value !== '' && user.value !== null)
  /** 后端角色 OPERATOR 才允许进管理端。前端这一层只用于"藏入口"，真正的拦截在后端 */
  const isAdmin = computed(() => user.value?.role === 'OPERATOR')
  const displayName = computed(() => user.value?.nickname || user.value?.email || '游客')
  const email = computed(() => user.value?.email ?? '')

  // ---------------------------------------------------------------- 持久化

  function persist() {
    try {
      if (token.value && user.value) {
        const p: Persisted = { token: token.value, user: user.value }
        localStorage.setItem(KEY, JSON.stringify(p))
      } else {
        localStorage.removeItem(KEY)
      }
    } catch {
      /* 隐私模式下 localStorage 可能不可用，静默降级为"仅当前标签页有效" */
    }
  }

  function load() {
    try {
      const raw = localStorage.getItem(KEY)
      if (!raw) return
      const p = JSON.parse(raw) as Persisted
      // 结构校验：宁可当作未登录，也不要带着半个会话继续跑
      if (p && typeof p.token === 'string' && p.user && typeof p.user.user_id === 'number') {
        token.value = p.token
        user.value = p.user
      } else {
        localStorage.removeItem(KEY)
      }
    } catch {
      /* 解析失败就当没登录过 */
    }
  }

  /**
   * 清理旧版假登录留下的数据。
   *
   * 旧 key 里存的是 `{name, role: 'guest'|'admin', token: 'demo-admin-xxx'}`，
   * 那种令牌后端完全不认，留着只会让用户遇到"界面显示已登录、接口全部 4001"
   * 的迷惑状态。
   *
   * 这里**不做数据迁移**：旧会话里没有任何真实信息（name 是用户打字输入的，
   * token 是本地时间戳），没有值得保留的东西。旧库里存的那些账号
   * （比如演示时随手输的 `13800000000`）在新体系里也不该存在——
   * 现在账号由邮箱唯一确定，服务端库里没有就是没有。
   * 所以直接删掉，让用户重新注册/登录一次即可，这比伪造一个"迁移成功"更诚实。
   */
  function migrateLegacy() {
    try {
      if (localStorage.getItem(LEGACY_KEY) !== null) {
        localStorage.removeItem(LEGACY_KEY)
        console.info(
          '[M8] 已清理旧版演示会话（%s）。旧账号在新认证体系里无效，请用邮箱重新注册或登录。',
          LEGACY_KEY,
        )
      }
    } catch {
      /* 同上 */
    }
  }

  // ---------------------------------------------------------------- 动作

  /** 把服务端返回的令牌写入会话 */
  function accept(t: AuthToken) {
    token.value = t.token
    user.value = t
    ready.value = true
    persist()
  }

  /**
   * 登录。失败时抛出 ApiError，错误码由后端的 ErrorCode 决定，
   * 调用方（Login.vue）负责翻译成人话。
   */
  async function login(e: string, password: string) {
    const t = await authApi.login(e, password)
    accept(t)
    return t
  }

  /** 注册。成功即拿到令牌，不需要再调一次登录 */
  async function register(e: string, code: string, password: string, nickname?: string) {
    const t = await authApi.register({ email: e, code, password, nickname })
    accept(t)
    return t
  }

  /**
   * 退出登录。
   *
   * **即使服务端调用失败也要清掉本地会话**：否则网络一断，用户就卡在
   * "点退出没反应"的状态里。服务端那边令牌本来就快过期了，
   * 而且用户既然点了退出，本地先断干净更符合预期。
   */
  async function logout() {
    if (token.value) {
      try {
        await authApi.logout()
      } catch {
        /* 见上：本地必须断 */
      }
    }
    clear()
  }

  /** 只清本地，不调接口。令牌失效（4001/4002）时由拦截器调用 */
  function clear() {
    token.value = ''
    user.value = null
    ready.value = true
    persist()
  }

  /**
   * 用服务端确认当前会话。应用启动时调用一次。
   *
   * 三种结果：
   *   - 没令牌           → 直接 ready，不打扰后端
   *   - 令牌有效         → 把用户信息刷新一遍（昵称/角色可能在别处被改过）
   *   - 令牌失效/被顶掉   → 清掉本地会话
   *
   * 返回是否仍处于登录态。
   */
  async function restore(): Promise<boolean> {
    if (!token.value) {
      ready.value = true
      return false
    }
    try {
      const me = await authApi.getMe()
      if (user.value) {
        // 只更新服务端权威字段，保留 token 与 expires_in_seconds
        user.value = {
          ...user.value,
          user_id: me.user_id,
          email: me.email,
          nickname: me.nickname,
          role: me.role,
        }
        persist()
      }
      return true
    } catch {
      // 令牌过期/被作废/账号禁用 —— 都以"未登录"结束，不区分原因给用户看
      clear()
      return false
    }
  }

  function init() {
    migrateLegacy()
    load()
  }

  return {
    // 状态
    token,
    user,
    ready,
    // 派生
    isLoggedIn,
    isAdmin,
    displayName,
    email,
    // 动作
    init,
    login,
    register,
    logout,
    clear,
    restore,
  }
})
