/**
 * 单业态运营分析（四个业务页共用的取数逻辑）
 *
 * 四个页面（农产品 / 景点 / 餐饮 / 住宿）在数据层的差别**只有 type 一个参数**，
 * 其余完全一样：一个区间档位、一个 loading、一次"丢弃过期响应"。
 * 复制四遍的代价不是行数，而是"某一页忘了丢弃过期响应"这类**不会报错**的偏差 ——
 * 快速切档位时那一页会停在旧数据上，而屏幕上没有任何东西提示它是旧的。
 *
 * 所以这里把它做成一个 composable，四个页面各自 `useOpsBusiness('product')` 即可。
 *
 * ★ **range 的初值从 URL 读**（`?range=LAST7`），因为驾驶舱的 KPI 卡下钻会带过来。
 *   读一次就够，之后用户在页面内切档位**不回写 URL** —— 回写会让"切档位"变成
 *   一次路由跳转，返回键的行为也跟着变（与 `Resources.vue` 处理 `?kind=` 同一取舍）。
 */

import { computed, ref, watch, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { getOpsBusiness } from '@/api/ops'
import { ApiError } from '@/api/http'
import type { OpsBizType, OpsBusiness, OpsRange } from '@/types'

/** 四档统计区间。文案与驾驶舱、`Risks.vue` 的日期分段**必须逐字一致** —— 同一个窗口两种说法会被读成两个口径 */
export const RANGE_TABS: { key: OpsRange; label: string }[] = [
  { key: 'TODAY', label: '今日' },
  { key: 'LAST7', label: '近 7 日' },
  { key: 'LAST30', label: '近 30 日' },
  { key: 'ALL', label: '全部' },
]

const RANGE_KEYS = RANGE_TABS.map((r) => r.key)

/** 从 `?range=` 读初值。不认识的值一律回落 `LAST7` —— 深链参数是外部输入，不能直接当状态用 */
export function initRange(raw: unknown, fallback: OpsRange = 'LAST7'): OpsRange {
  const v = String(raw ?? '').toUpperCase()
  return (RANGE_KEYS as string[]).includes(v) ? (v as OpsRange) : fallback
}

/**
 * @param type 业态。可以传 ref —— 景点页要在「核心景区」「乡村景点」两段之间切，
 *             两段是**两个 type**（scenic / rural），不是一个 type 加筛选参数。
 */
export function useOpsBusiness(type: OpsBizType | Ref<OpsBizType>, initialRange?: OpsRange) {
  const route = useRoute()
  const range = ref<OpsRange>(initialRange ?? initRange(route.query.range))

  const data = ref<OpsBusiness | null>(null)
  const loading = ref(true)
  /** 切档位时的"静默加载"：保留旧数据，只在顶部标"更新中"，不整屏闪骨架 */
  const switching = ref(false)
  const error = ref<string | null>(null)
  /** 请求序号，用来丢弃过期响应 —— 连点两档会有两个并发请求，先发的可能后到 */
  let reqId = 0

  const typeOf = () => (typeof type === 'string' ? type : type.value)

  async function load(quiet = false) {
    const id = ++reqId
    const want = range.value
    const wantType = typeOf()
    if (quiet) switching.value = true
    else loading.value = true
    error.value = null
    try {
      const d = await getOpsBusiness(wantType, want)
      // 判据用的是**后端回显的 type/range**，不是请求顺序：
      // 请求顺序只能证明"谁后发"，证明不了"谁的数据是现在想要的"
      if (id !== reqId || d.range !== range.value || d.type !== typeOf()) return
      data.value = d
    } catch (e) {
      if (id !== reqId) return
      const msg = e instanceof ApiError ? e.message : '加载失败'
      if (quiet) {
        // 静默加载失败保留旧数据：把整屏换成错误页会让运营以为刚才看到的数据没了
        error.value = msg
      } else {
        error.value = msg
        data.value = null
      }
    } finally {
      if (id === reqId) {
        loading.value = false
        switching.value = false
      }
    }
  }

  function pickRange(r: OpsRange) {
    if (range.value === r) return
    range.value = r
  }

  watch(range, () => void load(true))
  // 业态切换（景点页的两段）也走静默重拉，理由同上
  if (typeof type !== 'string') watch(type, () => void load(true))

  /** KPI 的周期前缀（"近 7 日"），由后端 `period_label` 给 —— 前端不自己拼 */
  const periodLabel = computed(() => data.value?.period_label ?? '')

  return { range, RANGE_TABS, data, loading, switching, error, load, pickRange, periodLabel }
}
