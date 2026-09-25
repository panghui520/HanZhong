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
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import PoiImage from './PoiImage.vue'
import { getBanners } from '@/api/media'
import type { SiteBanner } from '@/types'

type Variant = 'qinling' | 'terrace' | 'rapeseed' | 'ancient' | 'river' | 'hanjiang' | 'hantai'

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

const FALLBACK_SLIDES: Slide[] = [
  {
    key: 'fallback-qinling',
    variant: 'qinling',
    eyebrow: '智慧文旅 · 乡村振兴',
    title: '汉游智脑',
    sub: '发现汉中，也发现乡村的新可能',
    desc:
      '当景区高位运行，让客流顺着山谷流向乡村。AI 参与的规划、分流与运营，把一次到访延展成一条持续消费链。',
    to: '/explore',
    cta: '探索汉中',
  },
  {
    key: 'fallback-hanjiang',
    variant: 'hanjiang',
    eyebrow: '汉江之畔 · 一城文脉',
    title: '一江汉水，两岸春秋',
    sub: '从石门栈道到汉家发祥地',
    desc:
      '汉中是汉文化的发祥地。我们把散落的景区、街巷、村镇连成可规划的动线，让每一次停留都落在有故事的地方。',
    to: '/assistant',
    cta: '问问智脑',
  },
  {
    key: 'fallback-rapeseed',
    variant: 'rapeseed',
    eyebrow: '油菜花海 · 乡村体验',
    title: '把春天种在田里',
    sub: '花期之外，乡村仍然值得来',
    desc:
      '油菜花、茶园、梯田不只是风景，也是可预约的乡村体验。游客走进来，收益留在村里。',
    to: '/explore',
    cta: '乡村体验',
  },
  {
    key: 'fallback-hantai',
    variant: 'hantai',
    eyebrow: '古汉台 · 东方人文',
    title: '檐下百年，一眼千载',
    sub: '在古建与花树之间读懂汉中',
    desc:
      '以东方人文为底色的视觉与内容体系，让文化资源可阅读、可推荐、可被 AI 准确引用。',
    to: '/assistant',
    cta: '了解文脉',
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
    cta: b.cta || '了解更多',
  }))
})

const index = ref(0)
const paused = ref(false)
const DURATION = 6200
let timer: number | undefined

const current = computed(() => slides.value[index.value] ?? slides.value[0])

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
      <div class="hero__veil" />
      <div class="hero__veil-b" />
    </div>

    <!-- 文案层 -->
    <div class="container container-wide hero__inner">
      <div class="hero__copy">
        <span class="eyebrow eyebrow--light hero__eyebrow">{{ current.eyebrow }}</span>
        <h1 class="mega hero__title">{{ current.title }}</h1>
        <p class="hero__sub">{{ current.sub }}</p>
        <p class="hero__desc">{{ current.desc }}</p>

        <div class="hero__cta">
          <router-link :to="current.to" class="btn btn-gold btn-lg">探索汉中</router-link>
          <router-link to="/itinerary" class="btn btn-line btn-lg">AI 智能行程规划</router-link>
        </div>

        <div class="hero__stats">
          <div class="hero__stat">
            <span class="num hero__stat-num">42</span>
            <span class="hero__stat-label">文旅资源点</span>
          </div>
          <span class="hero__stat-sep" />
          <div class="hero__stat">
            <span class="num hero__stat-num">14</span>
            <span class="hero__stat-label">乡村体验项目</span>
          </div>
          <span class="hero__stat-sep" />
          <div class="hero__stat">
            <span class="num hero__stat-num">16</span>
            <span class="hero__stat-label">乡村特色产品</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 切换控件 -->
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
  </section>
</template>

<style scoped>
.hero {
  position: relative;
  height: var(--hero-h);
  min-height: 520px;
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
/* 极缓慢推近：幅度只有 3%，够形成"活"的感觉，不会晃眼。
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

.hero__veil,
.hero__veil-b {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.hero__veil {
  background: var(--hero-veil);
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
  max-width: 660px;
  color: #fff;
}
.hero__eyebrow {
  margin-bottom: var(--sp-5);
}
.hero__title {
  color: #fff;
  text-shadow: 0 2px 24px rgba(11, 33, 25, 0.35);
}
.hero__sub {
  margin-top: var(--sp-4);
  font-family: var(--font-display);
  font-size: var(--fs-hero-sub);
  letter-spacing: 0.06em;
  color: var(--gold-300);
}
.hero__desc {
  margin-top: var(--sp-5);
  max-width: 560px;
  font-size: var(--fs-body);
  line-height: 1.9;
  color: rgba(255, 255, 255, 0.82);
}
.hero__cta {
  margin-top: var(--sp-7);
  display: flex;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.hero__stats {
  margin-top: var(--sp-8);
  display: flex;
  align-items: center;
  gap: var(--sp-5);
  flex-wrap: wrap;
}
.hero__stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.hero__stat-num {
  font-size: 26px;
  font-weight: 600;
  color: #fff;
  line-height: 1.2;
}
.hero__stat-label {
  font-size: var(--fs-xs);
  letter-spacing: 0.06em;
  color: rgba(255, 255, 255, 0.68);
}
.hero__stat-sep {
  width: 1px;
  height: 30px;
  background: rgba(255, 255, 255, 0.24);
}

/* ---------- 控件 ---------- */
.hero__nav {
  position: absolute;
  left: 0;
  right: 0;
  bottom: var(--sp-6);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-5);
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
  .hero__stats {
    margin-top: var(--sp-6);
  }
}
@media (max-width: 720px) {
  .hero__desc {
    font-size: var(--fs-sm);
  }
  .hero__cta {
    margin-top: var(--sp-5);
  }
  .hero__cta .btn {
    flex: 1 1 auto;
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
  .hero__stat-num {
    font-size: 22px;
  }
}
</style>
