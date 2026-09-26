/**
 * 互动地图的自定义 Marker（pin）。
 *
 * ============================================================
 * 为什么是"拼 HTML 字符串"而不是一个 Vue 组件
 * ============================================================
 * 标注是 `AMap.Marker` 接管的：把字符串或 DOM 交给 `content`，之后由高德的
 * 渲染循环负责定位（它会给外层容器写 `transform`）。在这上面再套一层 Vue
 * 组件，就要处理"Vue 的渲染时机"与"高德的重新挂载时机"两套生命周期，
 * 而这里需要的能力只有"换一段 HTML"，不值得。
 *
 * 代价是**这里没有框架兜底**：拼进去的文本必须自己转义（见 `escapeHtml`）。
 * 数据包里的景点名目前都是正常中文，但"目前没有"不是"以后也没有"。
 *
 * ============================================================
 * 样式为什么必须是全局的（不能写在 `<style scoped>` 里）
 * ============================================================
 * scoped 样式的做法是给**组件模板里的元素**加一个 `data-v-xxx` 属性，
 * 再靠 `[data-v-xxx]` 选择器命中。而 pin 的 DOM 是高德在运行时插进地图容器的，
 * 不在组件模板里，拿不到那个属性 —— 写在 scoped 块里等于**一条都不生效**，
 * 表现是"标注裸着显示、没有样式"，而且不报任何错。
 * 所以 `.mpin*` 的样式放在 `InteractiveMap.vue` 的**非 scoped** `<style>` 块里，
 * 并用 `mpin` 前缀保证不会碰到别处。
 */

import { BUSINESS_LABEL, type BusinessType, type Poi } from '@/types'
import { escapeHtml } from './format'

/**
 * 各业态的图标（24×24 线性图标，`stroke="currentColor"` 由外层颜色决定）。
 *
 * 用图标而不是纯色圆点：42 个点铺在一张图上，只靠颜色区分业态，
 * 用户要先记住"哪个颜色是住宿"才能读图。图标不用记。
 */
const GLYPHS: Record<BusinessType, string> = {
  // 山（景区）
  SCENIC: '<path d="M2 19h20"/><path d="M4.5 19l5-9.5 3.6 6.6L16 11.4 20.5 19"/>',
  // 屋（乡村旅游）
  RURAL_SPOT:
    '<path d="M2.8 10.6 12 3.6l9.2 7"/><path d="M5.4 9.4V20h13.2V9.4"/><path d="M10 20v-6h4v6"/>',
  // 叉与刀（餐饮）
  FOOD: '<path d="M7.2 3v7.2a2.2 2.2 0 0 0 4.4 0V3"/><path d="M9.4 12.4V21"/><path d="M16.8 3v18"/><path d="M16.8 3c2.6 0 4 2.2 4 4.6s-1.4 3.9-4 3.9"/>',
  // 床（住宿）
  LODGING:
    '<path d="M2.6 7.4V20"/><path d="M2.6 13.6h18.8V20"/><circle cx="6.4" cy="10.8" r="1.7"/><path d="M9.4 12.6h12V9.4a2 2 0 0 0-2-2h-6.4v5.2"/>',
  // 车（交通）
  TRANSPORT:
    '<rect x="5" y="3.8" width="14" height="13.4" rx="3"/><path d="M5 11.4h14"/><path d="M8.4 21l1.6-3.8"/><path d="M15.6 21 14 17.2"/>',
  // 购物袋（购物）
  SHOPPING: '<path d="M4.6 8h14.8l1 12.4H3.6z"/><path d="M9 8V5.9a3 3 0 0 1 6 0V8"/>',
}

/** 取业态图标。数据里出现没登记的业态时回落景区图标，而不是画一个空圈 */
export function pinGlyph(type: BusinessType): string {
  return GLYPHS[type] ?? GLYPHS.SCENIC
}

/** 业态中文名。取全局那一份 `BUSINESS_LABEL`，页面里不另起一套文案 */
export function typeLabel(type: BusinessType): string {
  return BUSINESS_LABEL[type] ?? type
}

/**
 * 生成一个 pin 的 HTML。`AMap.Marker` 的 `content` 直接吃这个字符串。
 *
 * 结构：
 *   `.mpin`          —— 定位基点（`anchor: 'bottom-center'`，即它的**底边中点**
 *                       落在坐标上），所以内部是"徽标在上、小三角在下"
 *   `.mpin__dot`     —— 圆形徽标 + 业态图标
 *   `.mpin__stem`    —— 指向坐标点的小三角
 *   `.mpin__name`    —— 名称气泡。绝对定位（不影响 `.mpin` 的尺寸，
 *                       否则"底边中点"会被它带偏），默认透明，hover 或选中时出现
 *
 * `data-active` 是给样式用的：选中的 pin 放大、浮到最上层、名称常显。
 * 用 `setContent` 重画而不是直接改 DOM —— 高德自己也会重画，
 * 两边同时改同一个节点会互相覆盖。
 */
export function pinMarkup(poi: Poi, active = false): string {
  const type = poi.business_type
  return (
    `<div class="mpin" data-kind="${type}" data-active="${active ? '1' : '0'}">` +
    `<span class="mpin__dot">` +
    `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" ` +
    `stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${pinGlyph(type)}</svg>` +
    `</span>` +
    `<span class="mpin__stem"></span>` +
    `<span class="mpin__name">${escapeHtml(poi.name)}</span>` +
    `</div>`
  )
}
