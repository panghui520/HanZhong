/**
 * 资源列表（景点 / 餐饮 / 住宿 / 农产品共用的列表 + 增删改逻辑）
 *
 * 抽出来的原因：拆分后的四个页面在"管理"这一侧是**同一件事** ——
 * 一个列表、一个关键词框、一个状态筛选、编辑 / 上下架 / 删除三个动作。
 * 差别只有打哪个端点。写四遍的话，`PACK` 行的那套权限口径（见下）
 * 要维护四份，而漏掉一处的表现是"这一页能删数据包资源"—— 后端会拒，
 * 但用户会先看到一个报错。
 *
 * ★ **数据包（`source='PACK'`）的三条口径，四个页面必须完全一致**：
 *   1. 「编辑」**不禁用**，但先弹一次确认 —— 保存即把 `source` 改成 `ADMIN`
 *      （接管）。这不是"确认要不要编辑"，是**告知一个不可逆的后果**：
 *      此后数据包更新这条资源的名字/简介不会再同步过来。
 *   2. 「删除」对 `PACK` 行**禁用**。删了下次启动也会被重新导入，后端会拒 ——
 *      与其让运营点一次读一遍报错，不如一开始就灰掉并说明原因。
 *   3. 「上架 / 下架」**从来不禁用**。`status` 不在数据包里，它是运营态，
 *      导入器会保留（`CityPackImporter.offlinePoiStatuses`）。
 *
 * ★ **农产品（product）当前不支持接管**，所以 PACK 行在农产品页是**禁用编辑**的。
 *   后端 `updateProduct` 走 `requireAdminOwned`，PACK 行必被 1108 拒。
 *   前端若照样弹"接管后即可编辑"，就是**承诺一个后端不认的能力**。
 *   两边口径在这里对齐，替代做法（改数据包 / 直接下架）写在下架按钮的 title 上。
 */

import { computed, ref, type Ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  adminCreatePoi,
  adminCreateProduct,
  adminDeletePoi,
  adminDeleteProduct,
  adminListPois,
  adminListProducts,
  adminSetPoiStatus,
  adminSetProductStatus,
  adminUpdatePoi,
  adminUpdateProduct,
  type PoiKind,
} from '@/api/resources'
import { useNotice } from '@/composables/useNotice'
import type { AdminPoi, AdminProduct } from '@/types'

export type ResourceRow = AdminPoi | AdminProduct

/** 页面自己决定"这一页只显示哪几行"（景点页用它把 18 个景区切成景区/乡村两段） */
export type RowFilter = (row: ResourceRow) => boolean

export function useResourceList(
  kind: PoiKind | 'product',
  options: { filter?: Ref<RowFilter | null>; initialKeyword?: string } = {}
) {
  const { notice, say } = useNotice()

  const isPoi = computed(() => kind !== 'product')
  const noun = computed(() => (kind === 'product' ? '农产品' : kind === 'food' ? '餐饮' : '景点'))

  const keyword = ref(options.initialKeyword ?? '')
  /** '' = 全部 / '1' = 已上架 / '0' = 已下架。用字符串是因为 select 的 v-model 只能给字符串 */
  const statusFilter = ref<'' | '0' | '1'>('')

  const poiRows = ref<AdminPoi[]>([])
  const productRows = ref<AdminProduct[]>([])

  const loading = ref(false)
  const busy = ref(false)

  /** 未过滤的原始结果 */
  const allRows = computed<ResourceRow[]>(() => (isPoi.value ? poiRows.value : productRows.value))
  /** 过滤后、真正渲染的行 */
  const rows = computed<ResourceRow[]>(() => {
    const f = options.filter?.value
    return f ? allRows.value.filter(f) : allRows.value
  })

  function errText(e: unknown, fallback: string) {
    return e instanceof ApiError ? e.message : fallback
  }

  /** 统一的写操作包装：串行化 + 错误提示。与 Media.vue 同一做法 */
  async function write(okText: string, fn: () => Promise<unknown>) {
    if (busy.value) return
    busy.value = true
    try {
      await fn()
      say('ok', okText)
    } catch (e) {
      say('err', errText(e, '操作失败，请重试'))
    } finally {
      busy.value = false
    }
  }

  async function reload() {
    loading.value = true
    try {
      const status = statusFilter.value === '' ? undefined : Number(statusFilter.value)
      const kw = keyword.value.trim()
      if (isPoi.value) {
        poiRows.value = await adminListPois(kind as PoiKind, { keyword: kw, status })
      } else {
        productRows.value = await adminListProducts({ keyword: kw, status })
      }
    } catch (e) {
      say('err', errText(e, '列表加载失败'))
    } finally {
      loading.value = false
    }
  }

  function toggleStatus(row: ResourceRow) {
    const next = row.status === 1 ? 0 : 1
    const label = next === 1 ? '上架' : '下架'
    void write(`已${label}「${row.name}」`, async () => {
      const saved = isPoi.value
        ? await adminSetPoiStatus(kind as PoiKind, row.id, next)
        : await adminSetProductStatus(row.id, next)
      // 用服务端返回的对象覆盖本地，避免"本地看着改了、服务端没接受"的偏差
      Object.assign(row, saved)
    })
  }

  function remove(row: ResourceRow) {
    const hint =
      row.source === 'PACK'
        ? '\n\n该资源来自城市数据包，后端会拒绝删除 —— 请改用下架。'
        : '\n\n若它已被订单、足迹或体验引用，后端也会拒绝并说明原因。'
    if (!window.confirm(`删除「${row.name}」？该操作不可撤销。${hint}`)) return
    void write(`已删除「${row.name}」`, async () => {
      if (isPoi.value) {
        await adminDeletePoi(kind as PoiKind, row.id)
      } else {
        await adminDeleteProduct(row.id)
      }
      await reload()
    })
  }

  /**
   * 能否编辑。农产品的 PACK 行返回 false（见文件头第 4 条）。
   * 其余一律 true —— 包括景点的 PACK 行，它走"接管"。
   */
  function canEdit(row: ResourceRow): boolean {
    return isPoi.value || row.source !== 'PACK'
  }

  /** 编辑按钮的 title。把"为什么不能点"和"不能点该怎么办"写清楚 */
  function editHint(row: ResourceRow): string {
    if (!canEdit(row)) {
      return '来自城市数据包，当前版本不支持编辑农产品 —— 请改数据包，或直接下架'
    }
    return row.source === 'PACK' ? '编辑（会把这条资源从城市数据包接管为运营维护）' : '编辑'
  }

  function removeHint(row: ResourceRow): string {
    if (row.source === 'ADMIN') return '删除'
    return isPoi.value
      ? '数据包资源删除后下次启动会重新导入。想改内容请点「编辑」接管，想藏起来请「下架」'
      : '数据包资源删除后下次启动会重新导入。农产品当前不支持接管编辑，想藏起来请「下架」'
  }

  return {
    notice,
    say,
    errText,
    write,
    isPoi,
    noun,
    keyword,
    statusFilter,
    rows,
    allRows,
    loading,
    busy,
    reload,
    toggleStatus,
    remove,
    canEdit,
    editHint,
    removeHint,
    /** 让页面在保存后能自己重拉（表单抽屉的保存回调里用） */
    createPoi: (body: Record<string, unknown>) => adminCreatePoi(kind as PoiKind, body),
    updatePoi: (id: string, body: Record<string, unknown>) =>
      adminUpdatePoi(kind as PoiKind, id, body),
    createProduct: (body: Record<string, unknown>) => adminCreateProduct(body),
    updateProduct: (id: string, body: Record<string, unknown>) => adminUpdateProduct(id, body),
  }
}
