<script setup lang="ts">
import { computed } from 'vue'
import SceneArt from '@/components/SceneArt.vue'
import { getCityPack } from '@/api/citypack'
import { isEmpty, useAsync } from '@/composables/useAsync'
import type { Product } from '@/types'

const { data, loading, error, reload } = useAsync(getCityPack)

const scenics = computed(() => (data.value?.pois ?? []).filter((p) => p.business_type === 'SCENIC'))
const rurals = computed(() =>
  (data.value?.pois ?? []).filter((p) => p.business_type === 'RURAL_SPOT')
)
const products = computed(() => data.value?.products ?? [])
const experiences = computed(() => data.value?.experiences ?? [])

const hotScenic = computed(() => scenics.value.slice(0, 4))
const ruralFeature = computed(() => rurals.value.slice(0, 3))

/**
 * 乡村好物只放 4 张卡，按分类各取一款。
 *
 * 后端的列表顺序是产品编码升序（即数据包的编号顺序），直接切前四条会得到
 * 三款茶加一款米——读起来像"某一类好物"。改成每个分类取第一款，
 * 四张卡覆盖四个品类；同时因为茶叶编码在最前，招牌的汉中仙毫仍然排在首位。
 * 想换首页推荐哪几款，改数据包里的编号顺序即可。
 */
const goods = computed<Product[]>(() => {
  const seen = new Set<string>()
  const picked: Product[] = []
  for (const p of products.value) {
    if (seen.has(p.category)) continue
    seen.add(p.category)
    picked.push(p)
    if (picked.length === 4) break
  }
  return picked
})

const stats = computed(() => [
  { label: '文旅资源点', value: data.value?.pois.length ?? 0, unit: '处' },
  { label: '乡村体验项目', value: experiences.value.length, unit: '项' },
  { label: '乡村特色产品', value: products.value.length, unit: '款' },
])

const pillars = [
  {
    no: '01',
    title: 'AI 智能行程规划',
    desc: '把天数、预算、同行人群、步行意愿与兴趣一起交给多智能体，景区、餐饮、住宿、交通与乡村资源协同求解，输出可直接执行的逐日方案。',
  },
  {
    no: '02',
    title: '承载力驱动的乡村引流',
    desc: '当热点景区承载吃紧而周边乡村闲置时，系统识别失衡、生成分流工单，并在游客行程中给出乡村替代方案——把溢出需求导向乡村。',
  },
  {
    no: '03',
    title: '离境复购的消费链延伸',
    desc: '乡村体验沉淀为旅行足迹，产品与体验锚定。游客回到自己的城市后，仍可循着记忆复购，让一次到访持续产生乡村收入。',
  },
]
</script>

<template>
  <div class="home">
    <!-- ============ Hero ============ -->
    <section class="hero">
      <SceneArt variant="qinling" ratio="auto" class="hero__art" />
      <div class="hero__veil" />
      <div class="container hero__inner">
        <span class="eyebrow hero__eyebrow">智慧文旅 · 乡村振兴</span>
        <h1 class="display hero__title">
          汉游智脑
          <span class="hero__title-sub">把一次到访，变成一条持续的乡村消费链</span>
        </h1>
        <p class="hero__desc">
          面向文旅管理者与游客的一体化平台：以 RAG
          知识库与多智能体协同，打通景区、餐饮、住宿、交通与乡村业态，
          在承载力失衡时把客流引向乡村，并让乡村体验沉淀为可复购的长期客源。
        </p>
        <div class="hero__actions">
          <router-link to="/explore" class="btn btn-gold btn-lg">探索汉中</router-link>
          <router-link to="/admin/dashboard" class="btn btn-ghost btn-lg hero__btn-light">
            查看管理驾驶舱
          </router-link>
        </div>
      </div>

      <div class="container hero__stats-wrap">
        <div class="hero__stats">
          <template v-if="loading">
            <div v-for="i in 3" :key="i" class="skeleton" style="height: 46px" />
          </template>
          <template v-else>
            <div v-for="s in stats" :key="s.label" class="hero__stat">
              <span class="num hero__stat-num">{{ s.value }}</span>
              <span class="hero__stat-unit">{{ s.unit }}</span>
              <span class="hero__stat-label">{{ s.label }}</span>
            </div>
          </template>
        </div>
      </div>
    </section>

    <!-- ============ 平台主张 ============ -->
    <section class="section-lg">
      <div class="container">
        <div class="pillars__head">
          <span class="eyebrow">平台主张</span>
          <h2 class="h1 pillars__title">文旅的价值，不该在游客离境那一刻归零</h2>
          <p class="body pillars__desc">
            传统智慧文旅止步于"游客—景区—离场"。汉游智脑把链条向后延伸：乡村体验成为锚点，
            特色产品成为可带走、可复购的延续，运营数据再回流到资源匹配。
          </p>
        </div>

        <div class="pillars">
          <article v-for="p in pillars" :key="p.no" class="pillar">
            <span class="num pillar__no">{{ p.no }}</span>
            <h3 class="h3 pillar__title">{{ p.title }}</h3>
            <p class="body pillar__desc">{{ p.desc }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- ============ 热门景区 ============ -->
    <section class="section scene-band">
      <div class="container">
        <div class="row-between band__head">
          <div>
            <span class="eyebrow">核心景区</span>
            <h2 class="h2 band__title">从栈道到古镇，汉中的骨架</h2>
          </div>
          <router-link to="/explore" class="band__more">查看全部资源 →</router-link>
        </div>

        <div v-if="loading" class="grid grid-4">
          <div v-for="i in 4" :key="i" class="skeleton" style="height: 300px; border-radius: 10px" />
        </div>

        <div v-else-if="error" class="state-error">
          <p>{{ error }}</p>
          <button class="btn btn-ghost btn-sm" @click="reload">重新加载</button>
        </div>

        <div v-else-if="isEmpty(hotScenic)" class="empty">
          <div class="empty__title">暂无景区数据</div>
          <div class="empty__desc">请确认后端服务已启动，且城市数据包已导入</div>
        </div>

        <div v-else class="grid grid-4">
          <router-link
            v-for="p in hotScenic"
            :key="p.id"
            :to="`/poi/${p.id}`"
            class="pcard card card-hover"
          >
            <SceneArt :variant="(p.scene as any) || 'qinling'" ratio="4 / 3" class="pcard__art" />
            <div class="pcard__body">
              <div class="row pcard__meta">
                <span class="tag tag-brand">{{ p.district }}</span>
                <span v-if="p.level" class="tag">{{ p.level }}</span>
              </div>
              <h3 class="h3 pcard__title">{{ p.name }}</h3>
              <p class="pcard__summary">{{ p.summary }}</p>
              <div class="pcard__foot">
                <span class="num pcard__price">
                  {{ p.ticket_price > 0 ? `¥${p.ticket_price}` : '免费' }}
                </span>
                <span class="muted small">建议 {{ p.duration_min }} 分钟</span>
              </div>
            </div>
          </router-link>
        </div>
      </div>
    </section>

    <!-- ============ 乡村旅游 ============ -->
    <section class="section-lg rural-band">
      <div class="container">
        <div class="rural__head">
          <span class="eyebrow eyebrow--light">乡村振兴</span>
          <h2 class="h1 rural__title">把溢出的客流，送进秦岭深处的村子</h2>
          <p class="body rural__desc">
            当核心景区承载吃紧，系统会匹配车程 30–60
            分钟内的乡村点，用一次真实的乡村体验承接需求——茶园、稻田、橘园、非遗工坊。
          </p>
        </div>

        <div v-if="loading" class="grid grid-3">
          <div v-for="i in 3" :key="i" class="skeleton" style="height: 220px; border-radius: 10px" />
        </div>

        <div v-else class="grid grid-3">
          <router-link
            v-for="p in ruralFeature"
            :key="p.id"
            :to="`/poi/${p.id}`"
            class="rcard card card-hover"
          >
            <SceneArt :variant="(p.scene as any) || 'terrace'" ratio="16 / 10" class="rcard__art" />
            <div class="rcard__body">
              <span class="tag tag-gold">{{ p.district }}</span>
              <h3 class="h3 rcard__title">{{ p.name }}</h3>
              <p class="rcard__summary">{{ p.summary }}</p>
            </div>
          </router-link>
        </div>
      </div>
    </section>

    <!-- ============ 乡村好物（体验溯源） ============ -->
    <section class="section">
      <div class="container">
        <div class="row-between band__head">
          <div>
            <span class="eyebrow">乡村好物</span>
            <h2 class="h2 band__title">每一样，都来自你体验过的那片山</h2>
          </div>
          <span class="muted small">产品与乡村体验锚定，不是货架</span>
        </div>

        <div v-if="loading" class="grid grid-4">
          <div v-for="i in 4" :key="i" class="skeleton" style="height: 260px; border-radius: 10px" />
        </div>

        <div v-else class="grid grid-4">
          <article v-for="g in goods" :key="g.id" class="gcard card card-hover">
            <SceneArt :variant="(g.scene as any) || 'terrace'" ratio="1 / 1" class="gcard__art" />
            <div class="gcard__body">
              <span class="tag tag-brand gcard__from">来自「{{ g.experience_name || '乡村体验' }}」</span>
              <h3 class="h3 gcard__title">{{ g.name }}</h3>
              <p class="gcard__spec muted small">{{ g.spec }} · {{ g.origin_village }}</p>
              <p class="gcard__story">{{ g.story }}</p>
              <div class="gcard__foot">
                <span class="num gcard__price">¥{{ g.price }}</span>
                <span class="muted small">可复购</span>
              </div>
            </div>
          </article>
        </div>
      </div>
    </section>

    <!-- ============ 闭环 ============ -->
    <section class="section-lg loop-band">
      <div class="container">
        <span class="eyebrow">业务闭环</span>
        <h2 class="h2 loop__title">一次旅游，如何在系统里走完一整圈</h2>

        <ol class="loop">
          <li class="loop__item">
            <span class="num loop__no">1</span>
            <span class="loop__label">游客需求</span>
          </li>
          <li class="loop__item">
            <span class="num loop__no">2</span>
            <span class="loop__label">AI 行程规划</span>
          </li>
          <li class="loop__item">
            <span class="num loop__no">3</span>
            <span class="loop__label">乡村引流</span>
          </li>
          <li class="loop__item">
            <span class="num loop__no">4</span>
            <span class="loop__label">乡村体验</span>
          </li>
          <li class="loop__item">
            <span class="num loop__no">5</span>
            <span class="loop__label">产品消费</span>
          </li>
          <li class="loop__item">
            <span class="num loop__no">6</span>
            <span class="loop__label">离境复购</span>
          </li>
          <li class="loop__item loop__item--ai">
            <span class="num loop__no">7</span>
            <span class="loop__label">AI 运营分析 → 优化匹配</span>
          </li>
        </ol>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* ---------- Hero ---------- */
.hero {
  position: relative;
  padding-bottom: var(--sp-9);
}
.hero__art {
  position: absolute;
  inset: 0;
  aspect-ratio: auto !important;
  height: 78%;
  border-radius: 0;
}
.hero__veil {
  position: absolute;
  inset: 0;
  height: 78%;
  background: linear-gradient(
      180deg,
      rgba(11, 33, 25, 0.52) 0%,
      rgba(11, 33, 25, 0.18) 38%,
      rgba(250, 248, 243, 0.92) 92%,
      var(--paper) 100%
    ),
    linear-gradient(90deg, rgba(11, 33, 25, 0.42) 0%, rgba(11, 33, 25, 0) 62%);
}
.hero__inner {
  position: relative;
  padding-top: calc(var(--sp-9) + var(--sp-5));
  max-width: var(--container);
}
.hero__eyebrow {
  color: var(--gold-300);
}
.hero__eyebrow::before {
  background: var(--gold-300);
}
.hero__title {
  margin-top: var(--sp-4);
  color: #fff;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.hero__title-sub {
  font-family: var(--font-sans);
  font-size: 19px;
  font-weight: 400;
  letter-spacing: 0.04em;
  color: rgba(255, 255, 255, 0.86);
  max-width: 30em;
}
.hero__desc {
  margin-top: var(--sp-5);
  max-width: 46em;
  font-size: var(--fs-body);
  line-height: 1.85;
  color: rgba(255, 255, 255, 0.78);
}
.hero__actions {
  margin-top: var(--sp-6);
  display: flex;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.hero__btn-light {
  color: #fff;
  border-color: rgba(255, 255, 255, 0.45);
}
.hero__btn-light:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.12);
  border-color: #fff;
}

.hero__stats-wrap {
  position: relative;
  margin-top: var(--sp-7);
}
.hero__stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--sp-5);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-3);
  padding: var(--sp-5) var(--sp-6);
}
.hero__stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}
.hero__stat + .hero__stat {
  border-left: 1px solid var(--line-soft);
  padding-left: var(--sp-5);
}
.hero__stat-num {
  font-size: 34px;
  font-weight: 700;
  color: var(--brand-700);
  line-height: 1;
}
.hero__stat-unit {
  font-size: var(--fs-sm);
  color: var(--warm-500);
}
.hero__stat-label {
  flex-basis: 100%;
  font-size: var(--fs-sm);
  color: var(--ink-500);
  margin-top: 2px;
}

/* ---------- 平台主张 ---------- */
.pillars__head {
  max-width: 720px;
}
.pillars__title {
  margin-top: var(--sp-3);
}
.pillars__desc {
  margin-top: var(--sp-4);
}
.pillars {
  margin-top: var(--sp-7);
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-7);
}
.pillar {
  padding-top: var(--sp-4);
  border-top: 1px solid var(--line);
}
.pillar__no {
  display: block;
  font-size: var(--fs-cap);
  letter-spacing: 0.2em;
  color: var(--gold-600);
  font-weight: 700;
}
.pillar__title {
  margin-top: var(--sp-3);
}
.pillar__desc {
  margin-top: var(--sp-3);
  font-size: var(--fs-sm);
}

/* ---------- 通用标题行 ---------- */
.band__head {
  margin-bottom: var(--sp-6);
}
.band__title {
  margin-top: var(--sp-2);
}
.band__more {
  font-size: var(--fs-sm);
  color: var(--brand-700);
  font-weight: 600;
  transition: gap var(--dur-1) var(--ease);
}
.band__more:hover {
  color: var(--gold-600);
}

/* ---------- 景区卡 ---------- */
.scene-band {
  background: var(--paper);
}
.pcard {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.pcard__art {
  border-radius: 0;
}
.pcard__body {
  padding: var(--sp-4) var(--sp-5) var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  flex: 1;
}
.pcard__meta {
  gap: var(--sp-2);
}
.pcard__title {
  transition: color var(--dur-1) var(--ease);
}
.pcard:hover .pcard__title {
  color: var(--brand-700);
}
.pcard__summary {
  font-size: var(--fs-sm);
  color: var(--ink-500);
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.pcard__foot {
  margin-top: auto;
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line-soft);
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.pcard__price {
  font-size: 17px;
  font-weight: 700;
  color: var(--gold-600);
}

/* ---------- 乡村带（深绿反白） ---------- */
.rural-band {
  background: var(--brand-800);
  color: var(--brand-100);
}
.rural__head {
  max-width: 760px;
  margin-bottom: var(--sp-7);
}
.eyebrow--light {
  color: var(--gold-300);
}
.eyebrow--light::before {
  background: var(--gold-300);
}
.rural__title {
  margin-top: var(--sp-3);
  color: #fff;
}
.rural__desc {
  margin-top: var(--sp-4);
  color: var(--brand-300);
}
.rcard {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(219, 233, 227, 0.14);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.rcard:hover {
  background: rgba(255, 255, 255, 0.07);
  border-color: rgba(226, 202, 145, 0.4);
}
.rcard__body {
  padding: var(--sp-4) var(--sp-5) var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.rcard__title {
  color: #fff;
}
.rcard__summary {
  font-size: var(--fs-sm);
  color: var(--brand-300);
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ---------- 乡村好物 ---------- */
.gcard {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.gcard__art {
  border-radius: 0;
}
.gcard__body {
  padding: var(--sp-4) var(--sp-5) var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  flex: 1;
}
.gcard__from {
  align-self: flex-start;
  height: auto;
  padding: 3px 8px;
  line-height: 1.5;
  white-space: normal;
}
.gcard__title {
  font-size: 16px;
}
.gcard__story {
  font-size: var(--fs-sm);
  color: var(--ink-500);
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.gcard__foot {
  margin-top: auto;
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line-soft);
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.gcard__price {
  font-size: 17px;
  font-weight: 700;
  color: var(--gold-600);
}

/* ---------- 闭环 ---------- */
.loop-band {
  background: var(--paper-2);
}
.loop__title {
  margin-top: var(--sp-2);
  margin-bottom: var(--sp-6);
}
.loop {
  display: flex;
  flex-wrap: wrap;
  align-items: stretch;
  gap: 0;
}
.loop__item {
  flex: 1 1 140px;
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-4) var(--sp-4) var(--sp-4) 0;
  border-top: 2px solid var(--line);
}
.loop__item + .loop__item {
  padding-left: var(--sp-4);
}
.loop__no {
  font-size: var(--fs-cap);
  font-weight: 700;
  color: var(--warm-400);
  letter-spacing: 0.12em;
}
.loop__label {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-700);
}
.loop__item--ai {
  border-top-color: var(--gold-500);
  flex: 1 1 220px;
}
.loop__item--ai .loop__no {
  color: var(--gold-600);
}

/* ---------- 错误态 ---------- */
.state-error {
  padding: var(--sp-6);
  text-align: center;
  border: 1px dashed var(--line);
  border-radius: var(--r-lg);
  color: var(--danger);
  font-size: var(--fs-sm);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-3);
}

/* ---------- 响应式 ---------- */
@media (max-width: 1080px) {
  .pillars {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--sp-5);
  }
}
@media (max-width: 720px) {
  .hero__art,
  .hero__veil {
    height: 88%;
  }
  .hero__inner {
    padding-top: var(--sp-8);
  }
  .hero__stats {
    grid-template-columns: minmax(0, 1fr);
  }
  .hero__stat + .hero__stat {
    border-left: none;
    border-top: 1px solid var(--line-soft);
    padding-left: 0;
    padding-top: var(--sp-3);
  }
  .loop__item {
    flex: 1 1 100%;
  }
}
</style>
