import type { RouteLocationRaw } from 'vue-router'

/**
 * KPI 条里的一张卡。
 *
 * 放在单独的 `.ts` 里而不是 `KpiBar.vue` 的 `<script setup>` 中：
 * `<script setup>` **不允许出现 export 语句**（编译期就会报错），
 * 而四个业务页都要用这个类型来组装自己的 KPI 列表。
 */
export interface KpiItem {
  key: string
  label: string
  /** 已由后端格式化好的展示值（含千分位 / ¥ / %）。前端不再加工，否则两边会漂移 */
  value: string
  unit?: string
  /** 口径提示，鼠标悬停时出现 */
  hint?: string
  /** 下钻目标。给了才是可点的 */
  to?: RouteLocationRaw
}
