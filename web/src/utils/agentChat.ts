import type { AgentTurn } from '@/types'

/**
 * AI 旅游助手的对话历史持久化。
 *
 * ============================================================
 * 解决的问题
 * ============================================================
 * `turns` 原本是 `Agent.vue` 里的一个组件级 `ref`，组件一卸载就没了。
 * 于是"切到探索页看看景点、再切回来"和"刷新一下页面"都会**清空整段对话** ——
 * 用户刚问出来的酒店列表、刚排好的行程，一切走就没了，只能重问一遍。
 *
 * ============================================================
 * 为什么是 sessionStorage，不是 localStorage
 * ============================================================
 * 三个理由，按重要性排序：
 *
 * 1. **它是"这一次访问的对话"，不是长期资产。** 用户的需求原话是"页面切换、
 *    组件重新挂载或页面刷新后还在" —— 这三件事 sessionStorage 全都满足。
 *    它没有要求"关掉浏览器明天打开还在"。
 * 2. **localStorage 会把上一个人的对话留给下一个人。** 这台机器明天换个人用
 *    （演示现场很常见），打开助手页看到的是别人查过的酒店、别人选过的住处。
 *    而 sessionStorage 是**按标签页**隔离的，关掉标签页就结束 ——
 *    与"清空对话"按钮的语义一致。
 * 3. **会话令牌才需要跨浏览器会话。** 登录状态存 localStorage 是因为
 *    "关掉浏览器再打开还登录着"是明确需求（见 `stores/session.ts`）；
 *    对话历史没有这条需求，跟着令牌一起长期留存只是顺手，不是理由。
 *
 * 代价：**关掉标签页/浏览器，对话就没了**。这是有意的，不是缺陷。
 *
 * ============================================================
 * 三条必须做对的边界
 * ============================================================
 * ① **绝不把 `streaming: true` 存下来。** 用户在流式回答生成到一半时切走，
 *    如果原样存下 `streaming: true`，回来时那一轮会永远转圈（没有流在推了），
 *    而 `streaming` 守卫还会让新提问发不出去 —— 页面看着活着，实际卡死。
 *    所以写入时统一归一成 `false`，并给未生成完的那一轮补一句说明。
 * ② **带版本号。** `AgentTurn` 的形状会随功能演进（比如阶段三加了 `itinerary`）。
 *    旧版本存下来的数据在新代码里渲染，表现是"某一块空白"或整页报错，
 *    而用户只会说"这个页面坏了"。版本对不上就直接丢弃，重新开始。
 * ③ **有容量上限。** 卡片里带图片地址、来源里带摘要，一轮对话几百字节到十几 KB。
 *    sessionStorage 通常只有 5MB，写超了会抛 `QuotaExceededError` ——
 *    如果不管，用户会看到"发一条消息就报错"。这里按**轮数**和**总字符数**
 *    两个上限裁掉最旧的（保留最新的），并在写入失败时再降一级重试。
 *
 * ============================================================
 * 已知边界（不打算在这一版解决）
 * ============================================================
 * 同一个标签页里退出登录、换个账号登录，仍然会看到上一个人的对话 ——
 * 对话是"按标签页"存的，不区分账号。用「清空对话」可以清掉。
 * 要做成按账号隔离，得在登录/退出时同步清理（注意退出登录会先跳走再卸载组件，
 * 组件卸载时的保存会把刚清掉的数据又写回去，不能只在 store 里清）。
 */

/** 存储键。与 `hanyou.auth` / `hanyou.cart` 同一套命名 */
const KEY = 'hanyou.agent.chat'

/**
 * 结构版本。**改了 `AgentTurn` 的形状就要 +1**，旧数据会被丢弃。
 * 丢弃的代价是"用户丢了一次对话"，比"渲染出半张空白卡"轻得多。
 */
const VERSION = 1

/** 最多保留多少轮。再多就裁掉最旧的 */
const MAX_TURNS = 30

/** 最多保留多少字符（约 400KB，远低于 sessionStorage 的常见 5MB 配额） */
const MAX_CHARS = 400_000

interface Persisted {
  v: number
  turns: AgentTurn[]
}

/**
 * 把存下来的原始数据复原成一个**可以安全渲染**的 `AgentTurn`。
 *
 * 逐字段兜底而不是直接 `as AgentTurn`：存储里的数据可能来自上一个版本的代码、
 * 也可能被用户手动改过（开发者工具里改一下很常见）。缺一个字段就让整页崩掉，
 * 代价远大于"这一格是空的"。
 *
 * 认不出来的一轮直接返回 `null`（由调用方过滤掉），而不是补一堆默认值 ——
 * 一轮连问题都没有的对话，补出来也只是个空壳。
 */
function reviveTurn(raw: unknown): AgentTurn | null {
  if (!raw || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>

  if (typeof r.question !== 'string' || !r.question) return null

  const asArray = <T>(v: unknown): T[] => (Array.isArray(v) ? (v as T[]) : [])

  return {
    // id 只用来做 `:key`，真正的值由 `loadAgentTurns` 统一重排（见那里的说明）
    id: 0,
    question: r.question,
    answer: typeof r.answer === 'string' ? r.answer : '',
    meta: (r.meta ?? null) as AgentTurn['meta'],
    tool: (r.tool ?? null) as AgentTurn['tool'],
    cards: asArray<AgentTurn['cards'][number]>(r.cards),
    sources: asArray<AgentTurn['sources'][number]>(r.sources),
    itinerary: (r.itinerary ?? null) as AgentTurn['itinerary'],
    cardKind: typeof r.cardKind === 'string' ? r.cardKind : '',
    elapsedMs: typeof r.elapsedMs === 'number' && Number.isFinite(r.elapsedMs) ? r.elapsedMs : 0,
    // ★ 无论存的是什么，**一律当已结束**（见模块说明 ①）
    streaming: false,
    error: typeof r.error === 'string' ? r.error : '',
  }
}

/**
 * 读回对话历史。**任何异常都返回空数组** ——
 * 读不到只是"没有历史"，不该让助手页打不开。
 *
 * 读回来的 `id` 会**重新按顺序排 1..N**，而不是用存下来的值。
 * 理由：`id` 唯一的用途是模板里的 `:key`，重复的 key 会让 Vue 复用错节点
 * （表现为"回答串到了别的问题下面"）。存下来的 id 可能是旧版本生成的、
 * 可能被手动改过，重排一遍是唯一能保证唯一性的做法 —— 而组件里
 * `seq` 只需取 `turns.length` 就能接着往下发号，不会与历史撞号。
 */
export function loadAgentTurns(): AgentTurn[] {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as Persisted
    if (!parsed || parsed.v !== VERSION || !Array.isArray(parsed.turns)) {
      // 版本对不上 / 结构不对：丢弃，不要让新代码去渲染旧数据
      sessionStorage.removeItem(KEY)
      return []
    }
    return parsed.turns
      .map(reviveTurn)
      .filter((t): t is AgentTurn => t !== null)
      .map((t, i) => ({ ...t, id: i + 1 }))
  } catch {
    return []
  }
}

/**
 * 写入对话历史。**永不抛异常** —— 存不下最多是"刷新后看不到历史"，
 * 不该影响这一次对话本身。
 *
 * 写入时把 `streaming` 归一成 `false`：用户可能在回答生成到一半时刷新页面，
 * 那一轮没有流在推了，存成 `true` 回来就是一个永远转的圈。
 * 同时给它补一句说明 —— 用户看到半截回答时得知道"这是没生成完"，
 * 而不是以为助手就答了这么点。
 */
export function saveAgentTurns(turns: readonly AgentTurn[]): void {
  if (!turns.length) {
    clearAgentTurns()
    return
  }

  const snapshot = turns.slice(-MAX_TURNS).map((t) => {
    const copy: AgentTurn = {
      ...t,
      // 卡片与来源是嵌套对象，浅拷贝够用：我们只改自己这一层的 `streaming`
      cards: [...t.cards],
      sources: [...t.sources],
      streaming: false,
    }
    if (t.streaming && !copy.error) {
      copy.error = '这次回答还没生成完就离开了页面'
    }
    return copy
  })

  // 按总字符数裁掉最旧的。**每次砍掉最旧的 1/4**，而不是一轮一轮砍：
  // 一次 stringify 就能少一大截，省掉几十次无谓的序列化。
  let payload: Persisted = { v: VERSION, turns: snapshot }
  let text = JSON.stringify(payload)
  while (text.length > MAX_CHARS && payload.turns.length > 1) {
    payload = { v: VERSION, turns: payload.turns.slice(Math.ceil(payload.turns.length / 4)) }
    text = JSON.stringify(payload)
  }

  try {
    sessionStorage.setItem(KEY, text)
  } catch {
    // 配额满 / 隐私模式禁用存储。再降一级试一次，还不行就放弃 ——
    // 这一次对话照常，只是刷新后看不到历史。
    try {
      const half = payload.turns.slice(Math.ceil(payload.turns.length / 2))
      sessionStorage.setItem(KEY, JSON.stringify({ v: VERSION, turns: half }))
    } catch {
      /* 静默放弃：对话本身不该因为存不下而受影响 */
    }
  }
}

/** 清空历史。「清空对话」按钮与"存了空数组"两条路都走这里 */
export function clearAgentTurns(): void {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    /* 同上 */
  }
}
