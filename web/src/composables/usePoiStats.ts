import { computed, ref } from 'vue'
import { getPoiStats } from '@/api/ops'
import type { PoiStat } from '@/types'

/**
 * 承载力的**全局单例**缓存（M5）。
 *
 * 为什么不做成每次调用各拉一份：探索页、行程页、详情页都要显示拥挤度，
 * 而探索页一屏就有几十张卡片。各自请求就是几十个并发打同一个接口、拿同一份数据。
 * 这里在模块级只保留一份状态，第一个用到它的组件负责发起请求，其余组件直接读。
 *
 * 为什么不用 Pinia store：这份数据没有任何写操作（改的是运营端，
 * 运营端读的是 `/admin/**` 那一套，与这里无关），也不参与会话与权限，
 * 用 store 是过度设计。与 `useCovers` 是同一个判断。
 *
 * 失败不抛错：客流服务出问题时页面应该继续显示内容，只是不显示拥挤度 ——
 * 而不是整个列表变成错误页。"这个数字暂时读不到"本来就是一种合法状态。
 */
const byId = ref<Record<string, PoiStat>>({})
/** 首次加载是否已结束（成功或失败都算）。区分"还没加载"与"确实没有数据" */
const loaded = ref(false)
let inflight: Promise<void> | null = null

function fetchOnce(): Promise<void> {
  if (!inflight) {
    inflight = getPoiStats()
      .then((list) => {
        const m: Record<string, PoiStat> = {}
        for (const s of list ?? []) m[s.poi_id] = s
        byId.value = m
      })
      .catch(() => {
        // 保持空映射：所有点位都不显示拥挤度，页面其余部分照常。
        byId.value = {}
      })
      .finally(() => {
        loaded.value = true
        inflight = null
      })
  }
  return inflight
}

/** 强制重新拉取 */
export function reloadPoiStats() {
  inflight = null
  loaded.value = false
  return fetchOnce()
}

export function usePoiStats() {
  if (!loaded.value) void fetchOnce()

  /**
   * 取某个资源点的承载情况。
   *
   * 返回 `undefined` 有三种含义，**调用方要分清楚**：
   *   1. 还没加载完（`loaded` 为 false）
   *   2. 接口失败
   *   3. 该点位确实没有数据（数据包里没编）
   * 所以**不要**把 undefined 当成 0 —— 0% 是"很空"，undefined 是"不知道"，
   * 显示成"舒适"会给出一个错误的结论。
   */
  function statOf(poiId: string): PoiStat | undefined {
    return byId.value[poiId]
  }

  /**
   * 承载占用率。取不到时返回 undefined。
   * 注意 `has_data` 为 false 时后端返回的 `capacity_usage` 是 0，
   * 那种情况要判 `has_data` 而不是看这个数是不是 0。
   */
  function usageOf(poiId: string): number | undefined {
    const s = byId.value[poiId]
    return s?.has_data ? s.capacity_usage : undefined
  }

  /** 承载率文案。取不到时给"—"，不要显示成 0% */
  function usageTextOf(poiId: string): string {
    const u = usageOf(poiId)
    return u == null ? '—' : `${Math.round(u * 100)}%`
  }

  /**
   * 拥挤档位。`unknown` 与 `ok` 必须分开：
   * 把"读不到"归到"舒适"会让用户以为已经确认过很空。
   */
  function levelOf(poiId: string): 'ok' | 'warn' | 'danger' | 'unknown' {
    const u = usageOf(poiId)
    if (u == null) return 'unknown'
    if (u >= 1) return 'danger'
    if (u >= 0.8) return 'warn'
    return 'ok'
  }

  return { byId, loaded, statOf, usageOf, usageTextOf, levelOf, reload: reloadPoiStats }
}

/** 拥挤档位的中文标签。`unknown` 单独一档 */
export const LOAD_LEVEL_LABEL: Record<string, string> = {
  ok: '舒适',
  warn: '偏忙',
  danger: '拥挤',
  unknown: '—',
}

/** 当前是否已加载完（给"两个数据源都就绪才渲染"的场景用） */
export function usePoiStatsReady() {
  if (!loaded.value) void fetchOnce()
  return computed(() => loaded.value)
}
