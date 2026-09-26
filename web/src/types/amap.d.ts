/**
 * 高德地图 JS API 2.0 的**最小**类型声明（互动地图页用）。
 *
 * 为什么自己写，而不是装 `@types/amap-js-api`：
 *   ① 那个包是按 JS API **1.4** 写的。2.0 里几处对不上，最典型的是
 *      `Marker.content` 现在接受 `HTMLElement`、`setFitView` 多了 `avoid` 参数。
 *      装了之后要在代码里来回 `as` 才能过编译 —— 比不装更难看懂。
 *   ② 这个页面只用到很小的一个子集（Map / Marker / Pixel / Geocoder）。
 *      声明 6 个类型比引一个几百 KB 的类型包更符合"能理解、能调试"。
 *
 * **只声明用到的成员。** 要加新能力时在这里补，不要用 `any` 绕过去 ——
 * 绕过去的表现是"方法名拼错了编译期看不出来，只在运行时白屏"，
 * 而那正是这个文件存在的意义。
 *
 * 本文件没有 import / export，所以是全局脚本，下面声明的 `AMap` 命名空间与
 * `Window` 成员直接是全局的（`Window` 与 lib.dom 里那个自动合并）。
 */

declare namespace AMap {
  /** 高德统一用 [经度, 纬度] 数组表示一个点，与 GeoJSON 一致 */
  type LngLat = [number, number]

  interface MapOptions {
    zoom?: number
    center?: LngLat
    /** '2D' 或 '3D'。3D 才能倾斜/旋转视角，本页用 2D */
    viewMode?: '2D' | '3D'
    /** 容器尺寸变化时自动适配（侧边卡片展开收起时会改容器宽度） */
    resizeEnable?: boolean
    zooms?: [number, number]
    /** 内置样式名。默认是 'normal'，本页用 'whitesmoke' 配站点浅色底 */
    mapStyle?: string
    features?: string[]
    showLabel?: boolean
    dragEnable?: boolean
    scrollWheel?: boolean
    doubleClickZoom?: boolean
    keyboardEnable?: boolean
    animateEnable?: boolean
  }

  class Pixel {
    constructor(x: number, y: number)
  }

  /** 事件对象的公共部分。`lnglat` 只在鼠标类事件上有 */
  interface MapsEvent<T> {
    target: T
    lnglat?: { lng: number; lat: number }
  }

  interface MarkerOptions {
    position?: LngLat
    /** HTML 字符串或 DOM 元素。本页传字符串（自定义样式的 pin） */
    content?: string | HTMLElement
    /** 定位基点。'bottom-center' = position 落在 pin 的底部尖端 */
    anchor?: string
    offset?: Pixel
    title?: string
    zIndex?: number
    /** 挂在标注上的业务数据。本页放 poi.id，点击时反查 */
    extData?: unknown
    visible?: boolean
    clickable?: boolean
  }

  class Marker {
    constructor(opts: MarkerOptions)
    on(event: string, handler: (e: MapsEvent<Marker>) => void): void
    off(event: string, handler?: (e: MapsEvent<Marker>) => void): void
    setContent(content: string | HTMLElement): void
    setPosition(position: LngLat): void
    setzIndex(zIndex: number): void
    setExtData(data: unknown): void
    getExtData(): unknown
    show(): void
    hide(): void
  }

  class Map {
    constructor(container: string | HTMLElement, opts?: MapOptions)
    add(overlay: unknown | unknown[]): void
    remove(overlay: unknown | unknown[]): void
    clearMap(): void
    on(event: string, handler: (e: MapsEvent<Map>) => void): void
    off(event: string, handler?: (e: MapsEvent<Map>) => void): void
    /**
     * 缩放到刚好装下这批覆盖物。
     * @param avoid 四边留白 [上, 右, 下, 左]，单位像素
     */
    setFitView(overlays?: unknown[], immediately?: boolean, avoid?: number[], maxZoom?: number): void
    setZoomAndCenter(zoom: number, center: LngLat, immediately?: boolean, duration?: number): void
    setZoom(zoom: number): void
    setCenter(center: LngLat): void
    getZoom(): number
    getCenter(): { lng: number; lat: number }
    /** 卸载时必须调用，否则容器被移除后监听与定时器仍在跑 */
    destroy(): void
  }

  interface GeocodeResult {
    info?: string
    regeocode?: {
      /** 结构化地址，如"陕西省汉中市汉台区…"。逆地理编码的主要产物 */
      formattedAddress?: string
      addressComponent?: Record<string, unknown>
    }
  }

  class Geocoder {
    constructor(opts?: { radius?: number; extensions?: string })
    /** 逆地理编码：坐标 → 地址。回调的 status 是 'complete' / 'error' / 'no_data' */
    getAddress(lnglat: LngLat, callback: (status: string, result: GeocodeResult) => void): void
  }

  /** 按需加载插件。Geocoder 这类非核心类要先 plugin 才能用 */
  function plugin(names: string | string[], callback: () => void): void
}

interface Window {
  /** JS API 脚本加载完成后由脚本自己挂上。加载前是 undefined */
  AMap?: typeof AMap
  /**
   * JS API 2.0 的安全密钥。**必须在加载脚本之前**赋值，
   * 否则地图鉴权失败（控制台 INVALID_USER_SCODE）、容器一片空白。
   */
  _AMapSecurityConfig?: {
    securityJsCode?: string
    serviceHost?: string
  }
}
