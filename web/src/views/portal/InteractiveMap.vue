<script setup lang="ts">
/**
 * 互动地图（M4 的独立前端页面，路由 `/map`）。
 *
 * ============================================================
 * 它是什么 / 不是什么
 * ============================================================
 * 是：把网站**现有的全部景点**按真实经纬度铺在高德地图上，点标注看卡片、
 * 从卡片进现有的景点详情页。
 * 不是：新的一套景点数据、也不是「探索汉中」页的替代品 ——
 * 数据全部来自 `/api/pois`（同一份 `citypack/hanzhong/pois.json`），
 * 图片来自 `/api/media/poi-images/covers`，详情页复用 `/poi/:id`。
 * 所以这一页**没有自己的数据源**，后端也一行没改。
 *
 * ============================================================
 * 两个必须说明的实现选择
 * ============================================================
 * ① **标注是拼 HTML 字符串**（见 `utils/mapPin.ts`）。原因写在那个文件里：
 *    标注由高德接管渲染，套一层 Vue 组件要处理两套生命周期，不值得。
 *    代价是拼进去的文本必须自己转义 —— 那里已经处理。
 *    连带后果：`.mpin*` 的样式**不能写在 scoped 块里**（scoped 只给组件模板
 *    里的元素加属性，而 pin 是高德运行时插进去的），所以本文件末尾有一个
 *    **非 scoped** 的 `<style>` 块专放 pin 样式。详见那一节的注释。
 *
 * ② **key 通过 `VITE_` 前缀进前端**，这是 JS API 的固有形态：地图由浏览器
 *    直接加载高德脚本，key 藏不住，也不该藏（它只能调用地图/标注这类前端能力）。
 *    与 Python 侧那个「Web 服务」类型的 `AMAP_KEY` 是两个不同的 key，
 *    详见 `.env.example`。key 缺失时本页**显示一句说明**而不是白屏。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getMeta, getPois } from '@/api/citypack'
import PoiImage from '@/components/PoiImage.vue'
import { useAsync } from '@/composables/useAsync'
import { BUSINESS_ORDER, type BusinessType, type Poi } from '@/types'
import { amapConfigured, loadAmap, reverseGeocode } from '@/utils/amap'
import { pinMarkup, typeLabel } from '@/utils/mapPin'

// ------------------------------------------------------------------ 数据

/** 全部景点。与「探索汉中」页同一个接口、同一份数据 */
const { data: pois, loading, error } = useAsync(getPois)
/** 城市元数据。只用它的 `center` 做地图初始中心，不写死坐标 */
const { data: meta } = useAsync(getMeta)

const allPois = computed<Poi[]>(() => pois.value ?? [])

// ------------------------------------------------------------------ 筛选

const keyword = ref('')
const activeType = ref<BusinessType | 'ALL'>('ALL')
const district = ref('')

const countOfType = (t: BusinessType) => allPois.value.filter((p) => p.business_type === t).length

/**
 * 业态筛选项**只列出数据里真有的**（与「探索汉中」页同一套做法）。
 * 硬列出 6 个业态而其中 1 个是 0 个景点，点下去得到空白地图 —— 那是让用户
 * 去发现一个不存在的东西。`SHOPPING` 目前就是 0 个，所以它不会出现在这里。
 */
const cats = computed(() =>
  BUSINESS_ORDER.filter((t) => countOfType(t) > 0).map((t) => ({
    key: t,
    label: typeLabel(t),
    n: countOfType(t),
  }))
)

const districts = computed(() => Array.from(new Set(allPois.value.map((p) => p.district))).sort())

const filtered = computed<Poi[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  return allPois.value.filter((p) => {
    if (activeType.value !== 'ALL' && p.business_type !== activeType.value) return false
    if (district.value && p.district !== district.value) return false
    if (!kw) return true
    const hay = [p.name, p.district, p.summary, p.level ?? '', ...(p.tags ?? [])]
      .join(' ')
      .toLowerCase()
    return hay.includes(kw)
  })
})

const hasFilter = computed(
  () => activeType.value !== 'ALL' || !!district.value || !!keyword.value.trim()
)

function clearFilter() {
  activeType.value = 'ALL'
  district.value = ''
  keyword.value = ''
}

// ------------------------------------------------------------------ 地图

const mapEl = ref<HTMLElement | null>(null)
const mapState = ref<'idle' | 'loading' | 'ready' | 'failed'>('idle')
const mapError = ref('')

let map: AMap.Map | null = null
/** 当前铺在图上的标注。换筛选时要整批换掉，所以要留着引用才能 remove */
let markers: AMap.Marker[] = []
const markerById = new Map<string, AMap.Marker>()

const active = ref<Poi | null>(null)
/** 逆地理编码拿到的街道地址。拿不到就空着，卡片回落显示区县 */
const address = ref('')
/** 防竞态：连点两个点，先发的请求后回来会把地址安到错的点上 */
let addressFor = ''

/** 地图初始中心。元数据没到时用数据包里的同一个值兜底，随后 `setFitView` 会覆盖它 */
const FALLBACK_CENTER = { lng: 107.02, lat: 33.07 }

async function initMap() {
  if (map || mapState.value === 'loading') return
  if (!amapConfigured()) {
    mapState.value = 'failed'
    mapError.value = '未配置高德 JS key'
    return
  }
  mapState.value = 'loading'
  mapError.value = ''
  try {
    const AMap = await loadAmap()
    // 等一帧：容器必须已经在 DOM 里且量得到尺寸，否则地图渲染成 0 高
    await nextTick()
    const el = mapEl.value
    if (!el) throw new Error('地图容器还没准备好')

    const center = meta.value?.center ?? FALLBACK_CENTER
    map = new AMap.Map(el, {
      center: [center.lng, center.lat],
      zoom: 9,
      viewMode: '2D',
      // 窗口尺寸变化时自动重算（窄屏、侧边栏展开都靠它）
      resizeEnable: true,
      // 站点是浅米色纸感底，用高德的浅色样式最贴
      mapStyle: 'whitesmoke',
      // 下限 8：再往外拉整个汉中就变成一个小点；上限 17：到街道级别够了
      zooms: [8, 17],
    })
    // 点空白处收起卡片 —— 与"点标注打开卡片"配成一对，用户不用去找关闭按钮
    map.on('click', () => closeCard())
    mapState.value = 'ready'
    syncMarkers(true)
  } catch (e) {
    mapState.value = 'failed'
    mapError.value = e instanceof Error ? e.message : '地图加载失败'
  }
}

/** 画一个标注当前的 HTML（选中态要变样，所以要能重画） */
function paint(m: AMap.Marker, p: Poi) {
  const on = active.value?.id === p.id
  m.setContent(pinMarkup(p, on))
  m.setzIndex(on ? 300 : 100)
}

/**
 * 按当前筛选结果重建标注。
 *
 * 整批 remove 再 add 而不是逐个 diff：42 个点重建是毫秒级，
 * 而 diff 要维护"哪些点该在图上"的两套状态，出错的表现是
 * "筛选后地图上还留着已经不该显示的点"。
 */
function syncMarkers(fit: boolean) {
  if (!map || !window.AMap) return
  if (markers.length) {
    map.remove(markers)
    markers = []
  }
  markerById.clear()

  const AMap = window.AMap
  markers = filtered.value.map((p) => {
    const m = new AMap.Marker({
      position: [p.lng, p.lat],
      content: pinMarkup(p, active.value?.id === p.id),
      // 让坐标落在 pin 的**底边中点**（小三角的尖端），而不是元素左上角
      anchor: 'bottom-center',
      title: p.name,
      zIndex: active.value?.id === p.id ? 300 : 100,
      // 把 id 挂在标注上，点击时不用靠下标去猜是哪个点
      extData: p.id,
    })
    m.on('click', () => selectPoi(p.id))
    markerById.set(p.id, m)
    return m
  })

  if (markers.length) map.add(markers)
  if (fit) fitView()
}

/**
 * 缩放到刚好装下当前全部标注。
 *
 * 右侧留白**跟着卡片是否展开走**：卡片浮在地图右边（340px + 16px 边距），
 * 展开时不留白的话，东边的点会被卡片压住。但卡片没开时也留 380px 的代价是
 * "整张图白缩了一档"—— 实测会多框进陇南、巴中这些邻省区域，
 * 看起来不像一张"汉中地图"。
 *
 * 只在"换筛选"和"重置视野"时调用 —— 每次点标注都重新 fit 会让地图乱跳
 * （点标注改用 setZoomAndCenter，见 `selectPoi`）。
 */
function fitView() {
  if (!map || !markers.length) return
  const padRight = active.value ? 400 : 70
  map.setFitView(markers, false, [70, padRight, 70, 70], 13)
}

function selectPoi(id: string) {
  const p = allPois.value.find((x) => x.id === id)
  if (!p) return
  const prev = active.value
  active.value = p
  // 只重画变化的那两个：旧的取消高亮、新的高亮
  const pm = prev ? markerById.get(prev.id) : null
  if (prev && pm) paint(pm, prev)
  const nm = markerById.get(id)
  if (nm) paint(nm, p)

  if (map) {
    // 已经比 12 更近就别拉远了 —— 用户自己放大过，说明他想看细节
    map.setZoomAndCenter(Math.max(map.getZoom(), 12), [p.lng, p.lat])
  }
  void loadAddress(p)
}

function closeCard() {
  const prev = active.value
  if (!prev) return
  active.value = null
  address.value = ''
  addressFor = ''
  const pm = markerById.get(prev.id)
  if (pm) paint(pm, prev)
}

function resetView() {
  closeCard()
  fitView()
}

/**
 * 查一次街道地址（可选增强）。
 *
 * 数据包里的 POI 只有区县，没有门牌级地址。这里用高德的逆地理编码补一条，
 * **拿不到就不显示**（卡片回落成区县），不会转圈也不会留空 ——
 * 它不该成为"卡片能不能看"的前提。
 */
async function loadAddress(p: Poi) {
  address.value = ''
  addressFor = p.id
  const a = await reverseGeocode(p.lng, p.lat)
  if (addressFor !== p.id) return
  address.value = a
}

// ------------------------------------------------------------------ 卡片展示

const addressText = computed(() => {
  const p = active.value
  if (!p) return ''
  return address.value || `${p.district}（${p.lng.toFixed(4)}, ${p.lat.toFixed(4)}）`
})

/** 门票：0 元说"免费"，null 不说 —— 不能把"没登记"说成 0 元 */
const ticketText = computed(() => {
  const v = active.value?.ticket_price
  if (v === null || v === undefined) return ''
  return v === 0 ? '免费' : `¥${v}`
})

/** 建议游览时长：数据里是分钟，转成"约 N 小时"；不足一小时按分钟说 */
const durationText = computed(() => {
  const m = active.value?.duration_min
  if (!m) return ''
  if (m < 60) return `约 ${m} 分钟`
  const h = m / 60
  return `约 ${Number.isInteger(h) ? h : h.toFixed(1)} 小时`
})

// ------------------------------------------------------------------ 生命周期

/** 数据到了才建图。`getPois` 失败时保持 idle，由页面的错误态接管 */
watch(
  () => pois.value,
  (v) => {
    if (v) void initMap()
  },
  { immediate: true }
)

/** 筛选变了就重建标注。首次（数据刚到时）`map` 还没建好，交给 `initMap` 里那次 */
watch(filtered, () => {
  if (mapState.value === 'ready') syncMarkers(true)
})

onMounted(() => {
  // 数据可能比挂载先到（缓存/快），这里补一次
  if (pois.value) void initMap()
})

onBeforeUnmount(() => {
  // 必须销毁：地图持有 DOM 监听与动画帧，容器被移除后它们仍在跑
  map?.destroy()
  map = null
  markers = []
  markerById.clear()
})
</script>

<template>
  <div class="imap">
    <div class="container container-wide">
      <!-- 页头：标题与规模统计同行，把纵向空间让给地图 -->
      <header class="imap__head">
        <div class="imap__head-main">
          <p class="eyebrow">互动地图 · 汉中</p>
          <h1 class="imap__title">在图上，把汉中的景点一次看完</h1>
          <p class="imap__lead">
            全部景点按真实经纬度落图。点标注看简介与地址，从卡片直接进详情页。
          </p>
        </div>
        <dl class="imap__stats">
          <div>
            <dt>落图景点</dt>
            <dd class="num">{{ allPois.length }}</dd>
          </div>
          <div>
            <dt>覆盖区县</dt>
            <dd class="num">{{ districts.length }}</dd>
          </div>
          <div>
            <dt>当前显示</dt>
            <dd class="num">{{ filtered.length }}</dd>
          </div>
        </dl>
      </header>

      <!-- 工具条：搜索 + 业态 + 区县 -->
      <div class="imap__bar">
        <label class="imap__search">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true">
            <circle cx="11" cy="11" r="6.4" />
            <path d="m16 16 4.4 4.4" stroke-linecap="round" />
          </svg>
          <input v-model="keyword" type="search" placeholder="搜景点名、区县或关键词，如「朱鹮」「留坝」" />
        </label>

        <div class="imap__cats">
          <button
            type="button"
            class="icat"
            :class="{ 'icat--on': activeType === 'ALL' }"
            @click="activeType = 'ALL'"
          >
            全部<i class="num">{{ allPois.length }}</i>
          </button>
          <button
            v-for="c in cats"
            :key="c.key"
            type="button"
            class="icat"
            :class="{ 'icat--on': activeType === c.key }"
            :data-kind="c.key"
            @click="activeType = c.key"
          >
            <span class="icat__dot" />{{ c.label }}<i class="num">{{ c.n }}</i>
          </button>
        </div>

        <select v-model="district" class="imap__select" aria-label="按区县筛选">
          <option value="">全部区县</option>
          <option v-for="d in districts" :key="d" :value="d">{{ d }}</option>
        </select>

        <button type="button" class="btn btn-ghost btn-sm" @click="resetView">重置视野</button>
        <button v-if="hasFilter" type="button" class="btn btn-text btn-sm" @click="clearFilter">
          清除筛选
        </button>
      </div>
    </div>

    <!-- 地图舞台：地图占满，卡片浮在右上 -->
    <div class="container container-wide">
      <div class="imap__stage">
        <!-- 容器始终在 DOM 里（不能被 v-if 拿掉），否则 ref 会晚一步才有值 -->
        <div ref="mapEl" class="imap__canvas" />

        <!-- 加载中 / 失败 / 未配 key：三种都是"盖一层说明"，不把地图容器换掉 -->
        <div v-if="mapState !== 'ready'" class="imap__mask">
          <template v-if="mapState === 'failed' && mapError === '未配置高德 JS key'">
            <h3>互动地图需要高德 JS API 的 key</h3>
            <p>
              在仓库根的 <code>.env</code> 里填 <code>VITE_AMAP_JS_KEY</code> 与
              <code>VITE_AMAP_JS_SECURITY_CODE</code>（键名必须以 <code>VITE_</code> 开头，
              否则 Vite 不会把它传给前端），改完重启开发服务器。
            </p>
            <p class="imap__mask-hint">
              注意这是「Web 端(JS API)」类型的 key，与 AI 助手用的「Web 服务」key 不是同一个。
            </p>
          </template>
          <template v-else-if="mapState === 'failed'">
            <h3>地图没能加载</h3>
            <p>{{ mapError }}</p>
            <button type="button" class="btn btn-primary btn-sm" @click="initMap">重试</button>
          </template>
          <template v-else>
            <p class="imap__mask-loading">正在加载地图…</p>
          </template>
        </div>

        <!-- 右侧悬浮信息卡 -->
        <Transition name="imap-card">
          <aside v-if="active" class="imap__card">
            <button type="button" class="imap__card-x" aria-label="关闭" @click="closeCard">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="m6 6 12 12M18 6 6 18" stroke-linecap="round" />
              </svg>
            </button>

            <div class="imap__card-fig">
              <PoiImage :poi-id="active.id" :scene="active.scene" ratio="16 / 10" :alt="active.name" />
            </div>

            <div class="imap__card-body">
              <div class="imap__card-tags">
                <span class="tag tag-brand" :data-kind="active.business_type">
                  {{ typeLabel(active.business_type) }}
                </span>
                <span v-if="active.level" class="tag tag-gold">{{ active.level }}</span>
                <span class="tag">{{ active.district }}</span>
              </div>

              <h2 class="imap__card-name">{{ active.name }}</h2>
              <p class="imap__card-sum">{{ active.summary }}</p>

              <dl class="imap__meta">
                <div>
                  <dt>地址</dt>
                  <dd>{{ addressText }}</dd>
                </div>
                <div v-if="active.open_hours">
                  <dt>开放时间</dt>
                  <dd>{{ active.open_hours }}</dd>
                </div>
                <div v-if="ticketText">
                  <dt>门票</dt>
                  <dd>{{ ticketText }}</dd>
                </div>
                <div v-if="durationText">
                  <dt>建议游览</dt>
                  <dd>{{ durationText }}</dd>
                </div>
              </dl>

              <RouterLink
                class="btn btn-primary imap__card-go"
                :to="{ name: 'poi', params: { id: active.id } }"
              >
                查看详情
              </RouterLink>
            </div>
          </aside>
        </Transition>

        <!-- 筛选后一个点都不剩 -->
        <div v-if="mapState === 'ready' && !filtered.length" class="imap__empty">
          <p>没有符合条件的景点。</p>
          <button type="button" class="btn btn-ghost btn-sm" @click="clearFilter">清除筛选</button>
        </div>
      </div>

      <p v-if="error" class="imap__err">景点数据加载失败：{{ error }}</p>
      <p v-else-if="loading" class="imap__err">正在读取景点数据…</p>
      <p v-else class="imap__foot">
        标注位置为整理后的近似坐标，仅供大致参考；出行请以景区公告与地图导航为准。
      </p>
    </div>
  </div>
</template>

<style scoped>
.imap {
  padding: var(--sp-6) 0 var(--sp-8);
}

/* ------------------------------------------------------------------ 页头 */

.imap__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-6);
  flex-wrap: wrap;
}

.imap__head-main {
  max-width: 46em;
}

.imap__title {
  font-family: var(--font-display);
  font-size: var(--fs-h1);
  color: var(--ink-900);
  margin: var(--sp-2) 0 var(--sp-2);
  letter-spacing: 0.01em;
}

.imap__lead {
  font-size: var(--fs-body);
  color: var(--ink-500);
  margin: 0;
}

.imap__stats {
  display: flex;
  gap: var(--sp-6);
  margin: 0;
}

.imap__stats div {
  text-align: right;
}

.imap__stats dt {
  font-size: var(--fs-cap);
  color: var(--warm-500);
  letter-spacing: 0.06em;
}

.imap__stats dd {
  margin: 2px 0 0;
  font-size: var(--fs-h2);
  color: var(--brand-700);
  line-height: 1.1;
}

/* ---------------------------------------------------------------- 工具条 */

.imap__bar {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin: var(--sp-5) 0 var(--sp-3);
}

.imap__search {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 0 var(--sp-3);
  height: 38px;
  min-width: 300px;
  flex: 1 1 300px;
  max-width: 420px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  color: var(--warm-500);
}

.imap__search:focus-within {
  border-color: var(--brand-300);
  box-shadow: 0 0 0 3px var(--brand-50);
}

.imap__search svg {
  width: 16px;
  height: 16px;
  flex: none;
}

.imap__search input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  font-size: var(--fs-sm);
  color: var(--ink-900);
  font-family: inherit;
}

/* 去掉 type=search 自带的叉，样式与站点其余控件不一致 */
.imap__search input::-webkit-search-cancel-button {
  display: none;
}

.imap__cats {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  flex-wrap: wrap;
}

.icat {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 var(--sp-3);
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  background: #fff;
  color: var(--ink-600);
  font-size: var(--fs-xs);
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}

.icat:hover {
  border-color: var(--brand-300);
  color: var(--brand-700);
}

.icat i {
  font-style: normal;
  color: var(--warm-400);
  font-size: var(--fs-cap);
}

.icat--on {
  background: var(--brand-700);
  border-color: var(--brand-700);
  color: #fff;
}

.icat--on i {
  color: var(--brand-100);
}

/* 圆点颜色与地图上的标注一致，所以这排按钮同时是图例 */
.icat__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
  background: var(--pin-color, var(--warm-400));
}

.icat[data-kind='SCENIC'] {
  --pin-color: var(--brand-500);
}
.icat[data-kind='RURAL_SPOT'] {
  --pin-color: var(--gold-500);
}
.icat[data-kind='FOOD'] {
  --pin-color: var(--danger);
}
.icat[data-kind='LODGING'] {
  --pin-color: var(--tech-500);
}
.icat[data-kind='TRANSPORT'] {
  --pin-color: var(--ink-600);
}
.icat[data-kind='SHOPPING'] {
  --pin-color: var(--warm-500);
}

.imap__select {
  height: 32px;
  padding: 0 var(--sp-3);
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  background: #fff;
  color: var(--ink-700);
  font-size: var(--fs-xs);
  font-family: inherit;
  cursor: pointer;
}

.imap__select:focus {
  outline: 0;
  border-color: var(--brand-300);
  box-shadow: 0 0 0 3px var(--brand-50);
}

/* ------------------------------------------------------------ 地图舞台 */

.imap__stage {
  position: relative;
  /*
   * 地图要占主要区域：撑满"视口高度减去导航与页头工具条"。
   * 实测（1680×1200）页头 + 工具条占 298px，所以减 300 后底边正好齐平视口 ——
   * 一屏能看全地图，不用滚。
   * 最小值 480 而不是 560：视口矮到 800px 时 100vh-300 = 500，
   * 若最小值是 560 就会顶出视口 58px（实测过），矮屏上白多一条滚动条。
   */
  height: clamp(480px, calc(100vh - 300px), 920px);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  overflow: hidden;
  background: var(--paper-2);
  box-shadow: 0 12px 32px -24px rgb(16 48 38 / 45%);
}

.imap__canvas {
  position: absolute;
  inset: 0;
}

.imap__mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sp-3);
  padding: var(--sp-6);
  text-align: center;
  background: var(--paper-2);
}

.imap__mask h3 {
  margin: 0;
  font-size: var(--fs-h3);
  color: var(--ink-900);
}

.imap__mask p {
  max-width: 44em;
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--ink-500);
  line-height: 1.7;
}

.imap__mask code {
  padding: 1px 5px;
  border-radius: var(--r-sm);
  background: var(--paper-3);
  font-size: 0.92em;
  color: var(--brand-700);
}

.imap__mask-hint {
  color: var(--warm-500);
  font-size: var(--fs-xs);
}

.imap__mask-loading {
  color: var(--warm-500);
}

.imap__empty {
  position: absolute;
  left: 50%;
  bottom: var(--sp-6);
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-3) var(--sp-2) var(--sp-5);
  border-radius: var(--r-pill);
  background: rgb(255 255 255 / 94%);
  border: 1px solid var(--line);
  box-shadow: 0 10px 24px -18px rgb(16 48 38 / 60%);
}

.imap__empty p {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--ink-600);
}

/* ---------------------------------------------------------- 悬浮信息卡 */

.imap__card {
  position: absolute;
  top: var(--sp-4);
  right: var(--sp-4);
  width: 340px;
  max-width: calc(100% - var(--sp-6));
  /* 卡片自身可滚：景点简介长短不一，不设上限会顶出地图外 */
  max-height: calc(100% - var(--sp-6));
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: 0 20px 44px -26px rgb(16 48 38 / 55%);
  overflow: hidden;
  z-index: 20;
}

.imap__card-x {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  /* 压在图片上，所以用半透明白底而不是站点描边样式 */
  background: rgb(255 255 255 / 88%);
  color: var(--ink-700);
  cursor: pointer;
  z-index: 2;
  transition: background 0.16s ease;
}

.imap__card-x:hover {
  background: #fff;
}

.imap__card-x svg {
  width: 14px;
  height: 14px;
}

.imap__card-fig {
  flex: none;
}

.imap__card-body {
  padding: var(--sp-4) var(--sp-5) var(--sp-5);
  overflow-y: auto;
}

.imap__card-tags {
  display: flex;
  gap: var(--sp-1);
  flex-wrap: wrap;
  margin-bottom: var(--sp-2);
}

.imap__card-name {
  margin: 0 0 var(--sp-2);
  font-family: var(--font-display);
  font-size: var(--fs-h3);
  color: var(--ink-900);
  line-height: 1.35;
}

.imap__card-sum {
  margin: 0 0 var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--ink-600);
  line-height: 1.75;
}

.imap__meta {
  margin: 0 0 var(--sp-5);
  display: grid;
  gap: var(--sp-2);
}

.imap__meta div {
  display: grid;
  grid-template-columns: 4.6em 1fr;
  gap: var(--sp-2);
  align-items: start;
}

.imap__meta dt {
  font-size: var(--fs-xs);
  color: var(--warm-500);
  padding-top: 1px;
}

.imap__meta dd {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--ink-700);
  line-height: 1.6;
  word-break: break-word;
}

.imap__card-go {
  width: 100%;
}

/* 卡片进出：从右侧滑入。短、只用 transform/opacity，避免地图跟着重排 */
.imap-card-enter-active,
.imap-card-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.24s cubic-bezier(0.22, 1, 0.36, 1);
}

.imap-card-enter-from,
.imap-card-leave-to {
  opacity: 0;
  transform: translateX(16px);
}

.imap__foot,
.imap__err {
  margin: var(--sp-3) 0 0;
  font-size: var(--fs-xs);
  color: var(--warm-500);
}

.imap__err {
  color: var(--danger);
}

@media (max-width: 900px) {
  .imap__card {
    left: var(--sp-4);
    right: var(--sp-4);
    width: auto;
    top: auto;
    bottom: var(--sp-4);
    max-height: 58%;
  }

  .imap__stats {
    gap: var(--sp-5);
  }
}
</style>

<!--
  ★ 非 scoped：这些类名挂在**高德运行时插入的 DOM** 上，不在本组件模板里。
  scoped 样式靠 `[data-v-xxx]` 命中元素，而 pin 拿不到那个属性 ——
  写在上面那个 scoped 块里会**一条都不生效**（标注裸着显示，且不报错）。
  全部以 `mpin` 前缀命名，避免影响其它页面。
-->
<style>
.mpin {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  cursor: pointer;
  /* 颜色按业态分。默认给个中性色，遇到没登记的业态也不会变成透明 */
  --pin: var(--warm-400);
}

.mpin[data-kind='SCENIC'] {
  --pin: #2a6f5b;
}
.mpin[data-kind='RURAL_SPOT'] {
  --pin: #c09a4e;
}
.mpin[data-kind='FOOD'] {
  --pin: #a8402b;
}
.mpin[data-kind='LODGING'] {
  --pin: #2e7bc4;
}
.mpin[data-kind='TRANSPORT'] {
  --pin: #455049;
}
.mpin[data-kind='SHOPPING'] {
  --pin: #8b8477;
}

.mpin__dot {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--pin);
  color: #fff;
  /* 白描边把标注从底图上"抠"出来，深色底图上也能看清 */
  border: 2px solid #fff;
  box-shadow: 0 4px 10px -4px rgb(16 48 38 / 70%);
  transition:
    transform 0.18s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.18s ease;
}

.mpin__dot svg {
  width: 16px;
  height: 16px;
}

/* 指向坐标点的小三角。与圆点同色，中间那道白线由圆点的白描边接上 */
.mpin__stem {
  width: 0;
  height: 0;
  margin-top: -1px;
  border-left: 5px solid transparent;
  border-right: 5px solid transparent;
  border-top: 8px solid var(--pin);
  filter: drop-shadow(0 2px 2px rgb(16 48 38 / 30%));
}

/*
  名称气泡。**绝对定位**（不参与 .mpin 的高度），
  否则 .mpin 会被它撑高，"底边中点"这个定位基点就被带偏了 ——
  表现是标注整体浮在坐标点上方一段距离。
*/
.mpin__name {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 50%;
  transform: translateX(-50%);
  padding: 3px 9px;
  border-radius: var(--r-pill);
  background: rgb(11 33 25 / 92%);
  color: #fff;
  font-size: 12px;
  font-family: var(--font-sans);
  line-height: 1.5;
  white-space: nowrap;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.16s ease;
}

.mpin:hover .mpin__name,
.mpin[data-active='1'] .mpin__name {
  opacity: 1;
}

.mpin:hover .mpin__dot {
  transform: scale(1.1);
}

/* 选中态：放大 + 金色光环。光环用 box-shadow 而不是 outline，
   免得被地图容器的 overflow 裁掉 */
.mpin[data-active='1'] .mpin__dot {
  transform: scale(1.22);
  box-shadow:
    0 0 0 4px rgb(192 154 78 / 45%),
    0 6px 14px -4px rgb(16 48 38 / 70%);
}

.mpin[data-active='1'] .mpin__name {
  background: #9c7330;
}
</style>
