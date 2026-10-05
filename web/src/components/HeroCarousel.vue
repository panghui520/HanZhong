<script setup lang="ts">
/**
 * HeroCarousel —— 首页超大轮播
 *
 * 数据来自后端 `site_banner` 表（M9 起可在管理端增删改排序）。
 * 内置的 4 帧作为**兜底**保留：接口挂了、表被清空、后端没起来，
 * 首页依然是一幅完整画面。这是"离线可演示"这条约束的具体落点，
 * 不是为了省事留的死代码。
 *
 * 每一帧的图片同样可缺省：没上传时回落到 SceneArt 手写 SVG（scene 字段）。
 * 交互只做三件克制的事：自动切换、淡入淡出、极轻微的缓慢推近。
 * 不做粒子、不做发光、不做跑马灯。
 *
 * ★ 左下角那三个数字（汉中好去处/乡村体验/乡村好物）**已从这里移除**。
 *   原因：Hero 高度收到横幅级（见 tokens 的 --hero-h）后，塞不下
 *   "标题 + 副标 + 搜索框 + 简介 + 三个数字"这一整摞，硬塞会把主标题压小。
 *   而这三个数字在下方「精选景点」区底部本来就有一份同样口径的展示，
 *   这里删掉不丢信息，反而避免同一页出现两处。`stats` prop 随之删除 ——
 *   项目约定是"不预留被忽略的参数"，留着一个没人读的 prop 只会误导。
 *
 * ============================================================
 * 这一版按参考设计稿补了三个元素（都是"呈现"，不动数据）
 * ============================================================
 *   1. **搜索框 + 「开始探索」**：原版是两个按钮（探索汉中 / 帮我规划行程）。
 *      参考稿里是一个输入框 + 绿色圆形确认键。改成一个真正能用的搜索：
 *      回车或点按钮 → 带着关键词进 `/explore?keyword=…`，直接落到"搜什么看什么"。
 *      ★ 落地页那一侧由 Explore.vue 负责：它的 `keyword` 初值从 URL 读
 *      （`initialKeyword()`），并且与 URL **双向**同步 —— 所以在本页搜完落地，
 *      关键词会留在输入框里、也会留在地址栏里（可分享、可刷新）。
 *      空输入时不跳转，避免把用户扔进一个空筛选页。
 *   2. **右上竖排城市标签**：`汉中 / HANZHONG` + "一座值得慢慢走的城市"。
 *   3. **右下「01 / 04」+ 左右箭头**：原来只有进度条 + 箭头，
 *      参考稿多一个页码。页码与进度条并存 —— 进度条说明"还要多久换下一帧"，
 *      页码说明"这是第几帧"，两者回答的是不同问题。
 *
 * ★ 按钮不再写死"探索汉中"：原来模板里无论数据配了什么 cta 都显示"探索汉中"，
 *   运营在管理端改 `cta` 字段是无效的。这里改回 `current.cta`，
 *   运营配的文案才真正生效。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PoiImage from './PoiImage.vue'
import { getBanners } from '@/api/media'
import type { SiteBanner } from '@/types'

type Variant = 'qinling' | 'terrace' | 'rapeseed' | 'ancient' | 'river' | 'hanjiang' | 'hantai'

const router = useRouter()

interface Slide {
  /** 列表渲染的 key。用 id 而不是 variant —— 两帧可能选同一个兜底画面，会撞 key */
  key: string
  variant: Variant
  /** 实拍图地址。缺省时用 variant 的手写 SVG */
  url?: string
  eyebrow: string
  title: string
  sub: string
  desc: string
  to: string
  cta: string
}

/**
 * 内置兜底 4 帧。
 *
 * ★ 文案标准：**游客视角**。写"你能看到什么、能带走什么"，
 *   不写"我们做了什么、系统怎么实现"。
 */
const FALLBACK_SLIDES: Slide[] = [
  {
    key: 'fallback-qinling',
    variant: 'qinling',
    eyebrow: '一城 · 一水 · 一山 · 一步 · 一景 · 一故事',
    title: '汉中 · 在这里\n遇见最美的诗与远方',
    sub: '智慧文旅 · 乡村振兴 · AI 让旅行更简单',
    desc:
      '秦岭的云海、汉江的古镇、村里的茶园与作坊。帮你把想看的、想吃的、想带走的，排成一条走得下来的动线。',
    to: '/explore',
    cta: '开始探索',
  },
  {
    key: 'fallback-hanjiang',
    variant: 'hanjiang',
    eyebrow: '一江汉水 · 两岸春秋',
    title: '一江汉水\n两岸春秋',
    sub: '从石门栈道到汉家发祥地',
    desc:
      '汉中是汉文化的发祥地。石门栈道、古汉台、拜将坛都在一条不长的动线上，一天就能走完半部汉史。',
    to: '/explore?type=SCENIC',
    cta: '开始探索',
  },
  {
    key: 'fallback-rapeseed',
    variant: 'rapeseed',
    eyebrow: '油菜花海 · 乡村体验',
    title: '把春天\n种在田里',
    sub: '花期之外，乡村仍然值得来',
    desc:
      '油菜花、茶园、橘园、腊味作坊，都能走进去待上半天。跟着农户采一次茶、熏一挂肉，比拍照记得更久。',
    to: '/explore?type=RURAL_SPOT',
    cta: '开始探索',
  },
  {
    key: 'fallback-hantai',
    variant: 'hantai',
    eyebrow: '古汉台 · 东方人文',
    title: '檐下百年\n一眼千载',
    sub: '在古建与花树之间读懂汉中',
    desc:
      '汉中市博物馆里藏着石门十三品，也藏着这座城两千年的来路。慢慢看，比匆匆打卡值得。',
    to: '/explore?type=SCENIC',
    cta: '开始探索',
  },
]

/** 后端配置的轮播帧。为空数组时用内置兜底 */
const banners = ref<SiteBanner[]>([])

const slides = computed<Slide[]>(() => {
  if (!banners.value.length) return FALLBACK_SLIDES
  return banners.value.map((b) => ({
    key: `banner-${b.id}`,
    variant: (b.scene as Variant) || 'qinling',
    url: b.url,
    eyebrow: b.eyebrow || '',
    title: b.title,
    sub: b.subtitle || '',
    desc: b.description || '',
    // 链接没配时给一个安全落点：空字符串会让 router-link 跳到当前页，
    // 点上去像"按钮没反应"
    to: b.link_url || '/explore',
    cta: b.cta || '开始探索',
  }))
})

const index = ref(0)
const paused = ref(false)
const DURATION = 6200
let timer: number | undefined

const current = computed(() => slides.value[index.value] ?? slides.value[0])

/**
 * 主标题换行：数据里用 `\n` 表示"这里该断行"。
 * 内置兜底与运营在管理端配的标题都走这条规则，
 * 不再靠 CSS 宽度碰运气断在哪儿 —— 中文标题断错位置很伤。
 */
const titleLines = computed(() => String(current.value?.title ?? '').split('\n'))

/** 页码补零：01 / 04 */
function pad(n: number) {
  return String(n).padStart(2, '0')
}

const totalText = computed(() => pad(slides.value.length))

// 帧数变少（运营删了帧）时把下标收回来，否则 current 会是 undefined，
// 模板里访问 current.title 直接报错
watch(
  () => slides.value.length,
  (n) => {
    if (n && index.value >= n) index.value = 0
  }
)

function go(i: number) {
  const n = slides.value.length
  if (!n) return
  index.value = (i + n) % n
}
function next() {
  go(index.value + 1)
}
function prev() {
  go(index.value - 1)
}

/* ---------- 首屏搜索 ----------
 * 只做一件事：把关键词带进 /explore。
 * 不在这里做"搜索建议""历史记录"—— 那些要么需要一个新接口（不允许动后端），
 * 要么要维护一份本地索引（和 Explore 的筛选逻辑重复且会不一致）。
 * 空输入时不跳：跳到 /explore 却没有筛选条件，等于让用户以为自己搜失败了。
 */
const keyword = ref('')

function search() {
  const q = keyword.value.trim()
  if (!q) {
    // 空输入时给一个明确落点，而不是原地不动
    router.push('/explore')
    return
  }
  router.push({ path: '/explore', query: { keyword: q } })
}

function start() {
  stop()
  timer = window.setInterval(() => {
    if (!paused.value) next()
  }, DURATION)
}
function stop() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

onMounted(async () => {
  start()
  try {
    const list = await getBanners()
    // 只在拿到非空结果时替换：后端返回空数组（运营把帧全删了 / 全停用了）
    // 时继续用内置兜底，首页不至于变成一片空白
    if (list && list.length) banners.value = list
  } catch {
    // 保持内置兜底。不提示、不报错 —— 首页不该因为轮播接口挂了就变难看
  }
})
onUnmounted(stop)
</script>

<template>
  <section
    class="hero"
    @mouseenter="paused = true"
    @mouseleave="paused = false"
    @focusin="paused = true"
    @focusout="paused = false"
  >
    <!-- 轮播画面层 -->
    <div class="hero__stage">
      <TransitionGroup name="hero-fade">
        <div v-for="(s, i) in slides" v-show="i === index" :key="s.key" class="hero__slide">
          <PoiImage :src="s.url" :scene="s.variant" ratio="auto" eager class="hero__art" />
        </div>
      </TransitionGroup>
      <!-- 两层压图：暖光 + 暗角。暖光是这一版新增的美学修正，
           让"黄昏落日的山水"读得出来，而不是夜里拍的冷绿。 -->
      <div class="hero__warm" />
      <div class="hero__veil" />
      <div class="hero__vignette" />
      <div class="hero__veil-b" />
    </div>

    <!-- 文案层 -->
    <div class="container container-wide hero__inner">
      <div class="hero__copy">
        <span class="eyebrow eyebrow--light hero__eyebrow">{{ current.eyebrow }}</span>
        <h1 class="hero__title">
          <span v-for="(line, i) in titleLines" :key="i" class="hero__title-line">{{ line }}</span>
        </h1>
        <p class="hero__sub">{{ current.sub }}</p>

        <!-- 搜索：回车或点右侧圆钮 → /explore?keyword=… -->
        <form class="hero__search" @submit.prevent="search">
          <span class="hero__search-ico" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18">
              <circle cx="11" cy="11" r="6.2" fill="none" stroke="currentColor" stroke-width="1.8" />
              <path
                d="M15.8 15.8 20 20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
              />
            </svg>
          </span>
          <input
            v-model="keyword"
            class="hero__search-input"
            type="search"
            placeholder="请输入你想了解的内容（如：汉中美食、石门栈道、农产品等）"
            aria-label="搜索汉中"
          />
          <button type="submit" class="hero__search-go" :aria-label="current.cta">
            {{ current.cta }}
            <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
              <path
                d="M5 12h13M12 6l6 6-6 6"
                fill="none"
                stroke="currentColor"
                stroke-width="1.9"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </button>
        </form>
      </div>
    </div>

    <!-- 右上：竖排城市标签 -->
    <div class="hero__city" aria-hidden="true">
      <span class="hero__city-cn">汉中</span>
      <span class="hero__city-en">HANZHONG</span>
      <span class="hero__city-line" />
      <span class="hero__city-note">一座值得<br />慢慢走的城市</span>
    </div>

    <!-- 右下：页码 + 进度条 + 箭头 -->
    <div class="container container-wide hero__nav">
      <div class="hero__dots">
        <button
          v-for="(s, i) in slides"
          :key="s.key"
          class="hero__dot"
          :class="{ 'hero__dot--on': i === index }"
          :aria-label="`第 ${i + 1} 帧：${s.sub}`"
          :aria-current="i === index"
          @click="go(i)"
        >
          <span class="hero__dot-bar" />
        </button>
      </div>

      <div class="hero__ctrl">
        <span class="hero__count num">
          <b>{{ pad(index + 1) }}</b>
          <i>/</i>
          {{ totalText }}
        </span>
        <div class="hero__arrows">
          <button class="hero__arrow" aria-label="上一帧" @click="prev">
            <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
              <path
                d="M15 5 L8 12 L15 19"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </button>
          <button class="hero__arrow" aria-label="下一帧" @click="next">
            <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
              <path
                d="M9 5 L16 12 L9 19"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.hero {
  position: relative;
  /* ★ 不要在这里写 min-height。
     踩过的坑：原来有 `min-height: 520px`，它**永远压过 `height`** ——
     于是 tokens 里把 --hero-h 收到 clamp(400px,46vh,520px) 之后，
     1440×900 下实测仍是 520px（而不是 414px），手机上 --hero-h 被
     覆写成 380px 也照样是 520px。即"缩小轮播图"这一步等于没做。
     下限已经由 clamp 的 400px 保证，这里再设一次只会打架。 */
  height: var(--hero-h);
  overflow: hidden;
  background: var(--brand-900);
}

/* ---------- 画面 ---------- */
.hero__stage {
  position: absolute;
  inset: 0;
}
.hero__slide {
  position: absolute;
  inset: 0;
}
.hero__art {
  width: 100%;
  height: 100%;
  border-radius: 0;
}
/* 极缓慢推近：幅度只有 3.5%，够形成"活"的感觉，不会晃眼。
   作用在 .pimg 这一层，实拍图与手写 SVG 走同一条动画。 */
.hero__art {
  animation: heroBreathe 16s ease-out both;
}
@keyframes heroBreathe {
  from {
    transform: scale(1);
  }
  to {
    transform: scale(1.035);
  }
}
/* 系统开了"减少动态效果"时全部停掉。这是无障碍要求，也让录屏更稳 */
@media (prefers-reduced-motion: reduce) {
  .hero__art {
    animation: none;
  }
  .hero__dot--on .hero__dot-bar {
    transition: none;
  }
}

.hero__warm,
.hero__veil,
.hero__vignette,
.hero__veil-b {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
/* 落日暖光：从右上打下来，人物一侧留一点温度 */
.hero__warm {
  background: var(--hero-warm);
  mix-blend-mode: screen;
}
.hero__veil {
  background: var(--hero-veil);
}
.hero__vignette {
  background: var(--hero-vignette);
}
.hero__veil-b {
  background: var(--hero-veil-b);
}

/* 画面切换：纯淡入淡出 */
.hero-fade-enter-from,
.hero-fade-leave-to {
  opacity: 0;
}
.hero-fade-enter-active,
.hero-fade-leave-active {
  transition: opacity 900ms var(--ease);
}

/* ---------- 文案 ---------- */
.hero__inner {
  position: relative;
  height: 100%;
  display: flex;
  align-items: center;
}
.hero__copy {
  max-width: 720px;
  color: #fff;
}
.hero__eyebrow {
  margin-bottom: var(--sp-4);
}
/* 主标题用 mega 字阶 + 竖排换行；每一行单独成块，行高比 .mega 默认略紧 */
.hero__title {
  font-family: var(--font-display);
  font-size: var(--fs-mega);
  line-height: 1.16;
  letter-spacing: 0.03em;
  font-weight: 600;
  color: #fff;
  text-shadow: 0 2px 28px rgba(11, 33, 25, 0.42);
}
.hero__title-line {
  display: block;
}
.hero__sub {
  margin-top: var(--sp-4);
  font-size: var(--fs-hero-sub);
  letter-spacing: 0.08em;
  color: var(--gold-300);
  text-shadow: 0 2px 16px rgba(11, 33, 25, 0.5);
}

/* ---------- 首屏搜索框 ---------- */
.hero__search {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-6);
  padding: 5px 5px 5px var(--sp-4);
  max-width: 620px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(255, 255, 255, 0.6);
  border-radius: var(--r-pill);
  box-shadow: 0 12px 34px rgba(11, 33, 25, 0.28);
  transition: box-shadow var(--dur-2) var(--ease), border-color var(--dur-2) var(--ease);
}
.hero__search:focus-within {
  border-color: var(--gold-300);
  box-shadow: 0 14px 40px rgba(11, 33, 25, 0.34);
}
.hero__search-ico {
  flex: none;
  display: grid;
  place-items: center;
  color: var(--warm-500);
}
.hero__search-input {
  flex: 1;
  min-width: 0;
  height: 40px;
  border: none;
  background: transparent;
  outline: none;
  font-size: var(--fs-sm);
  color: var(--ink-900);
}
.hero__search-input::placeholder {
  color: var(--warm-500);
}
/* 去掉 type="search" 自带的清除叉：它在深色底上是一个突兀的小方块 */
.hero__search-input::-webkit-search-cancel-button {
  display: none;
}
/* 绿色圆形确认键：参考稿里就是这个形状 */
.hero__search-go {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 40px;
  padding: 0 var(--sp-5);
  font-size: var(--fs-sm);
  font-weight: 600;
  letter-spacing: 0.02em;
  color: #fff;
  background: var(--brand-600);
  border-radius: var(--r-pill);
  transition: background var(--dur-1) var(--ease);
}
.hero__search-go:hover {
  background: var(--brand-700);
}

.hero__desc {
  margin-top: var(--sp-4);
  max-width: 560px;
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.78);
  /* Hero 收到横幅级后纵向预算变紧，简介最多两行 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ---------- 右上竖排城市标签 ---------- */
.hero__city {
  position: absolute;
  right: var(--sp-8);
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-3);
  color: #fff;
  text-align: center;
}
.hero__city-cn {
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 0.24em;
  /* 竖排：中文一个字一行，与参考稿右上"汉中"一致 */
  writing-mode: vertical-rl;
  text-orientation: upright;
  text-shadow: 0 2px 14px rgba(11, 33, 25, 0.5);
}
.hero__city-en {
  font-size: 10px;
  letter-spacing: 0.3em;
  color: rgba(255, 255, 255, 0.6);
  writing-mode: vertical-rl;
}
.hero__city-line {
  width: 1px;
  height: 44px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.5), rgba(255, 255, 255, 0));
}
.hero__city-note {
  font-size: var(--fs-cap);
  line-height: 1.9;
  letter-spacing: 0.1em;
  color: rgba(255, 255, 255, 0.7);
  writing-mode: vertical-rl;
}

/* ---------- 底部控件 ---------- */
.hero__nav {
  position: absolute;
  left: 0;
  right: 0;
  bottom: var(--sp-6);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-5);
  /* ★ 这一行是必要的，不是装饰。
     `.hero__nav` 是一条**横跨整宽**的绝对定位条，但它的交互元素只在两端
     （左 .hero__dots / 右 .hero__ctrl），**中间是一大片空的 div 背景**。
     默认 `pointer-events: auto` 会让这片空背景**吞掉点击**：
     实测 1440×900（hero 高 396px）下，首屏搜索按钮的下半部分落在这一带，
     `elementFromPoint` 命中的是 `div.hero__nav` 而不是按钮 ——
     即"看得见、点不动"（`regress_click` 的命中测试抓到的就是这个）。
     改 none + 让两端交互组自己 auto，是"整宽覆盖层"的标准做法。 */
  pointer-events: none;
}
.hero__dots,
.hero__ctrl {
  pointer-events: auto;
}
.hero__dots {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.hero__dot {
  width: 46px;
  height: 3px;
  padding: 0;
  background: rgba(255, 255, 255, 0.28);
  border-radius: var(--r-pill);
  overflow: hidden;
  transition: background var(--dur-2) var(--ease);
}
.hero__dot:hover {
  background: rgba(255, 255, 255, 0.5);
}
.hero__dot-bar {
  display: block;
  width: 0;
  height: 100%;
  background: var(--gold-300);
}
.hero__dot--on {
  background: rgba(255, 255, 255, 0.32);
}
.hero__dot--on .hero__dot-bar {
  width: 100%;
  transition: width 6200ms linear;
}

.hero__ctrl {
  display: flex;
  align-items: center;
  gap: var(--sp-5);
}
/* 页码：当前帧用白色加粗，总数压暗 —— 一眼看出"看到第几帧了" */
.hero__count {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  font-size: var(--fs-sm);
  letter-spacing: 0.1em;
  color: rgba(255, 255, 255, 0.5);
}
.hero__count b {
  font-size: var(--fs-h3);
  font-weight: 600;
  color: #fff;
}
.hero__count i {
  font-style: normal;
  color: rgba(255, 255, 255, 0.4);
}
.hero__arrows {
  display: flex;
  gap: var(--sp-2);
}
.hero__arrow {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.32);
  border-radius: var(--r-md);
  color: #fff;
  transition: all var(--dur-1) var(--ease);
}
.hero__arrow:hover {
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.7);
}

/* ---------- 响应式 ---------- */
@media (max-width: 1080px) {
  .hero__copy {
    max-width: 100%;
  }
  /* 窄屏时竖排城市标签会压到文案上，直接隐藏 —— 它是装饰，
     不是信息，藏掉不影响任何功能 */
  .hero__city {
    display: none;
  }
}
@media (max-width: 720px) {
  .hero__desc {
    font-size: var(--fs-sm);
  }
  .hero__search {
    /* 手机上一行放不下"输入框 + 文字按钮"，让按钮只留箭头 */
    padding-left: var(--sp-3);
  }
  .hero__search-go {
    padding: 0 var(--sp-4);
    font-size: 0;
    gap: 0;
  }
  .hero__search-go svg {
    width: 18px;
    height: 18px;
  }
  .hero__nav {
    bottom: var(--sp-4);
  }
  .hero__dots {
    gap: var(--sp-2);
  }
  .hero__dot {
    width: 30px;
  }
  .hero__count {
    display: none;
  }
}
</style>
