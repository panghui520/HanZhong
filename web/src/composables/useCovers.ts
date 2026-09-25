import { ref } from 'vue'
import { getPoiCovers } from '@/api/media'
import type { PoiCoverMap } from '@/types'

/**
 * 景点封面映射的**全局单例**缓存。
 *
 * 为什么不做成普通 composable（每次调用各拉一份）：
 * 一个列表页上有几十张卡片，每张卡片都调一次就是几十个并发请求，
 * 打的是同一个接口、拿的是同一份数据。这里在模块级只保留一份状态，
 * 第一个用到它的组件负责发起请求，其余组件直接读。
 *
 * 为什么不用 Pinia store：这份数据没有任何写操作（改图只发生在管理端，
 * 管理端自己会 reload），也不参与会话/权限，用 store 是过度设计。
 *
 * 失败不抛错：媒体服务出问题时页面应该继续显示手写 SVG 插画，
 * 而不是整个列表变成错误页 —— 这也正是"没有图片"时的正常表现。
 */
const covers = ref<PoiCoverMap>({})
/** 首次加载是否已结束（成功或失败都算）。用于区分"还没加载"与"确实没有图" */
const loaded = ref(false)
let inflight: Promise<void> | null = null

function fetchOnce(): Promise<void> {
  if (!inflight) {
    inflight = getPoiCovers()
      .then((m) => {
        covers.value = m ?? {}
      })
      .catch(() => {
        // 保持空映射：所有景点回落到手写 SVG。不把错误抛给页面，
        // 因为"没有配图"本来就是一种合法的展示状态。
        covers.value = {}
      })
      .finally(() => {
        loaded.value = true
        // 置空以便下次 reload 能重新发起（也避免把已完成的 promise 一直挂着）
        inflight = null
      })
  }
  return inflight
}

/** 强制重新拉取。管理端传完图后调用，让预览与游客端立刻看到新图 */
export function reloadCovers() {
  inflight = null
  loaded.value = false
  return fetchOnce()
}

export function useCovers() {
  // 已经加载过就直接用缓存；否则发起（或复用进行中的那次）请求
  if (!loaded.value) void fetchOnce()
  return { covers, loaded, reload: reloadCovers }
}
