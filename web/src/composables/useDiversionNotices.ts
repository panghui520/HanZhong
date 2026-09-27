import { computed, ref } from 'vue'
import { getDiversionNotices } from '@/api/diversion'
import type { DiversionNotice } from '@/types'

/**
 * 分流公告的**全局单例**缓存（M5 续）。
 *
 * 为什么不做成每次调用各拉一份：首页要显示告警条、详情页要显示 banner，
 * 而详情页是从首页点进来的 —— 各自请求就是两次打同一个接口拿同一份数据。
 * 与 `usePoiStats` 是同一个判断：模块级只保留一份状态，第一个用到它的组件发起请求。
 *
 * 为什么不用 Pinia store：这份数据没有任何写操作（写的是运营端 `/admin/**` 那一套），
 * 也不参与会话与权限。用 store 是过度设计。
 *
 * **失败不抛错**：公告服务出问题时首页应该继续显示内容，只是没有告警条 ——
 * 而不是整页变成错误页。"现在没有公告"和"公告读不到"对游客是同一个体验，
 * 但对开发是两回事，所以 `loaded` 与 `list.length` 分开暴露。
 */
const list = ref<DiversionNotice[]>([])
/** 首次加载是否已结束（成功或失败都算）。区分"还没加载"与"确实没有公告" */
const loaded = ref(false)
let inflight: Promise<void> | null = null

function fetchOnce(): Promise<void> {
  if (!inflight) {
    inflight = getDiversionNotices()
      .then((rows) => {
        list.value = rows ?? []
      })
      .catch(() => {
        // 保持空数组：首页照常渲染，只是不显示告警条
        list.value = []
      })
      .finally(() => {
        loaded.value = true
        inflight = null
      })
  }
  return inflight
}

/**
 * 强制重新拉取。
 *
 * **运营端发布/撤下之后必须调它。** 这个缓存是模块级的，会在路由切换间活下来：
 * 运营在 `/admin/notices` 发布了公告，再切到首页，首页读到的还是发布前那份 ——
 * 看起来像"发布没生效"。让写的那一方显式失效，比让每个读的页面各自猜要可靠。
 */
export function reloadDiversionNotices() {
  inflight = null
  loaded.value = false
  return fetchOnce()
}

export function useDiversionNotices() {
  if (!loaded.value) void fetchOnce()

  /**
   * 与某个资源点相关的公告。
   *
   * 只看 `from_poi_id`：公告说的是"**这个点**挤了，改往别处"，
   * 所以它只属于那个溢出的点。候选点自己不该看到这条公告 ——
   * 游客打开候选点的详情页时，需要的是"我要不要改去这里"，
   * 而不是"别人被建议来这里"。这个区分决定了详情页 banner 显示在哪一侧。
   */
  function forPoi(poiId: string) {
    return list.value.filter((n) => n.from_poi_id === poiId)
  }

  /** 有公告且**还有候选能去**时才值得提醒。全部候选都满了的公告只是噪音 */
  const actionable = computed(() => list.value.filter((n) => n.available_count > 0))

  return {
    list,
    loaded,
    actionable,
    forPoi,
    reload: reloadDiversionNotices,
  }
}
