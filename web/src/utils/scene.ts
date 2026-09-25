/**
 * 手写插画的场景变体（M6 起抽成独立模块）。
 *
 * ============================================================
 * 为什么要单独一个文件
 * ============================================================
 * 这 7 个变体原本只写在 `SceneArt.vue` 的 props 类型里。M6 开始，
 * **数据驱动的页面**也要按数据包里的 `scene` 字段选变体
 * （产品卡、购物车行、结算摘要），而那些字段的类型是 `string`
 * —— 数据库/JSON 里存的是任意字符串，不是字面量联合类型。
 *
 * 于是出现两难：
 *   - 把 `SceneArt` 的 props 放宽成 `string` → 传进来一个拼错的变体时
 *     模板会静默落到最后一个 `v-else` 分支，页面看着正常、画错了场景；
 *   - 每个页面各写一遍白名单校验 → 抄三遍，迟早有一处漏掉。
 *
 * 所以把「合法的变体集合」与「归一化函数」放在这里，两边共用：
 * `SceneArt` 用它约束 props，页面用它把数据字段转成合法值。
 *
 * ============================================================
 * 为什么归一化很重要（这个项目的具体理由）
 * ============================================================
 * 本项目的图片一律是手写 SVG，**没有实拍图兜底**（`web/public/` 是空的，
 * 离线可演示）。所以"画错场景"不是个小瑕疵 —— 一个茶叶产品画出汉江水面，
 * 页面上没有任何别的东西能纠正这个印象。宁可回落到一个明确的默认变体。
 */

/** 合法变体。与 `SceneArt.vue` 里 `<g v-if>` 的分支一一对应 */
export type SceneVariant =
  | 'qinling'
  | 'terrace'
  | 'rapeseed'
  | 'ancient'
  | 'river'
  | 'hanjiang'
  | 'hantai'

export const SCENE_VARIANTS: readonly SceneVariant[] = [
  'qinling',
  'terrace',
  'rapeseed',
  'ancient',
  'river',
  'hanjiang',
  'hantai',
] as const

/** 变体中文名。管理端选"兜底画面"时用 */
export const SCENE_LABELS: Record<SceneVariant, string> = {
  qinling: '秦岭云海',
  terrace: '茶园梯田',
  rapeseed: '油菜花田',
  ancient: '古建街巷',
  river: '溪流山谷',
  hanjiang: '汉江水面',
  hantai: '汉台春色',
}

function isVariant(v: unknown): v is SceneVariant {
  return typeof v === 'string' && (SCENE_VARIANTS as readonly string[]).includes(v)
}

/**
 * 把数据字段（任意字符串）归一化成合法变体。
 *
 * @param v        数据包里的 `scene` 字段，可能是 undefined 或拼错的值
 * @param fallback 认不出来时用哪个。默认 `qinling`；
 *                 乡村/农产品场景建议传 `terrace`（茶园梯田），
 *                 比云海更贴近"从土地里长出来的东西"
 */
export function sceneVariant(v?: string | null, fallback: SceneVariant = 'qinling'): SceneVariant {
  return isVariant(v) ? v : fallback
}
