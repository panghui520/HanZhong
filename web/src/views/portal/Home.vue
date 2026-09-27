<script setup lang="ts">
/**
 * Home —— 首页
 *
 * 节奏（自上而下，靠"尺寸 + 留白 + 字号层级"形成落差，而不是每区放同样大小的卡片）：
 *   1. 超大轮播 Hero（满屏，深色压图）
 *   1.5 分流公告条（M5 续，有生效公告时才出现）
 *   2. 汉中精选目的地（大图主推 + 次级列表，左右不对称）
 *   3. AI 智能行程规划（浅色强调带，横向流程）
 *   4. 乡村体验（深绿整幅带，大留白）
 *   5. 旅行足迹与乡村好物（体验锚定，不是货架）
 *   6. 智慧文旅平台价值（三栏，收束）
 *
 * 数据全部来自 getCityPack()（M1 + M2 已有接口），本文件只做取数与排版，
 * 不新增任何后端接口、不改数据库。
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import PoiImage from '@/components/PoiImage.vue'
import HeroCarousel from '@/components/HeroCarousel.vue'
import DiversionNoticeBar from '@/components/DiversionNoticeBar.vue'
import SectionHead from '@/components/SectionHead.vue'
import { getCityPack } from '@/api/citypack'
import { isEmpty, useAsync } from '@/composables/useAsync'
import { useDiversionNotices } from '@/composables/useDiversionNotices'
import type { Product } from '@/types'

const { data, loading, error, reload } = useAsync(getCityPack)

/**
 * 分流公告（M5 续）。
 *
 * 用 `actionable` 而不是 `list`：**候选全满的公告不显示**。
 * 一条"建议改往 A、B、C"而 A、B、C 现在都已不宽裕的提示，
 * 对游客只是噪音；它该由运营撤下（运营列表会提示"该撤下了"）。
 */
const { actionable: diversionNotices } = useDiversionNotices()

const scenics = computed(() => (data.value?.pois ?? []).filter((p) => p.business_type === 'SCENIC'))
const rurals = computed(() =>
  (data.value?.pois ?? []).filter((p) => p.business_type === 'RURAL_SPOT')
)
const products = computed(() => data.value?.products ?? [])
const experiences = computed(() => data.value?.experiences ?? [])

/** 精选目的地：首条做大幅主推，其余做次级列表 */
const featureScenic = computed(() => scenics.value[0])
const restScenic = computed(() => scenics.value.slice(1, 4))

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

/** AI 行程规划的四步：规则判定 + LLM 生成，讲清边界 */
const flow = [
  { no: '01', title: '理解需求', desc: '天数、同行人群、步行意愿、兴趣偏好' },
  { no: '02', title: '召回候选', desc: '按行政区、类型、距离筛出可达资源池' },
  { no: '03', title: '承载过滤', desc: '读取当日余量，高位点降权、闲时点前置' },
  { no: '04', title: '生成行程', desc: 'LLM 只负责写成可读方案与推荐理由' },
]

/** 平台价值三栏 */
const pillars = [
  {
    no: '01',
    title: '承载失衡，靠分流而不是靠限流',
    desc: '景区高位时不是简单劝返，而是把客流导向车程相邻、承载充足的乡村点，让溢出需求有去处、乡村有客源。',
  },
  {
    no: '02',
    title: '乡村体验，是可锚定的消费入口',
    desc: '每一样乡村好物都挂在一次真实体验或一个产地上，不做孤立货架，让"买"这件事有记忆背书。',
  },
  {
    no: '03',
    title: '一次到访，延伸成持续消费链',
    desc: '旅行足迹沉淀为长期客源。游客离境之后仍可循着体验复购，乡村收入不再随花期与旺季起落。',
  },
]

/* ---------- 滚动进入视口淡入（只用 IntersectionObserver，不引第三方库） ----------
   注意两件事：
   1. 阈值不能用固定比例。首页有大区块（如 dest__grid 高 500px），
      在 1050 高的视口里即使完全可见也未必达到某个交叉比，会让整块永停在 opacity:0。
      改成"元素进入视口即可触发"，用负的 rootMargin 控制延迟。
   2. 大多数 .reveal 元素在 loading === true 时还不存在（在 v-else 分支里），
      所以必须在数据到达、DOM 更新之后再挂 observer。这里用 watch + nextTick。 */
let io: IntersectionObserver | undefined
const root = ref<HTMLElement | null>(null)

function observeReveals() {
  io?.disconnect()
  const els = root.value?.querySelectorAll<HTMLElement>('.reveal:not(.is-in)')
  if (!els || !els.length) return

  if (typeof IntersectionObserver === 'undefined') {
    els.forEach((el) => el.classList.add('is-in'))
    return
  }

  const vh = window.innerHeight
  io = new IntersectionObserver(
    (entries) => {
      entries.forEach((e) => {
        if (e.isIntersecting) {
          e.target.classList.add('is-in')
          io?.unobserve(e.target)
        }
      })
    },
    { threshold: 0, rootMargin: `0px 0px -${Math.max(60, Math.round(vh * 0.1))}px 0px` }
  )
  els.forEach((el) => io!.observe(el))

  // 兜底：已经在视口内的元素直接点亮（observer 首次回调有延迟，
  // 且元素若比视口还高，交叉判定可能不符合预期）
  requestAnimationFrame(() => {
    els.forEach((el) => {
      const r = el.getBoundingClientRect()
      if (r.top < window.innerHeight && r.bottom > 0) el.classList.add('is-in')
    })
  })
}

// loading 结束后渲染出真实区块，此时才有关注对象
watch(loading, (v) => {
  if (!v) nextTick(observeReveals)
})
onMounted(() => nextTick(observeReveals))
onUnmounted(() => io?.disconnect())
</script>

<template>
  <div ref="root" class="home">
    <!-- ============ 1. 超大轮播 Hero ============ -->
    <HeroCarousel />

    <!-- ============ 1.5 分流公告（M5 续） ============
         排在 Hero 之后、所有内容之前：这是整站唯一一条"系统主动对游客说话"的
         内容，埋到下半页等于没发。没有生效公告时整块不渲染，不留空占位。

         多条同时生效（两个景区同时高位）时**叠成一列**，间距由这里的 grid
         统一给。组件只吃一条 —— 两条公告的 expire_at / published_by 各自
         独立，合并成一张大卡反而要在每段各写一次这些字段。
         12px 的卡间距远小于它与下一区块的距离（下一区块是 section-xl，
         上下各 128px），按接近性原则读起来是一组，而不是两张各说各话的公告。 -->
    <section v-if="diversionNotices.length" class="dnbsec">
      <div class="container dnbsec__stack">
        <DiversionNoticeBar
          v-for="n in diversionNotices"
          :key="n.id"
          :notice="n"
          variant="band"
        />
      </div>
    </section>

    <!-- ============ 2. 汉中精选目的地 ============ -->
    <section class="section-xl dest">
      <div class="container">
        <SectionHead
          eyebrow="汉中精选目的地"
          title="山、水、关、城，都在一条动线上"
          desc="从秦岭深处的云海，到汉江两岸的古镇与栈道。资源按距离与承载余量编排，不是一张清单。"
          size="xl"
          more-text="查看全部资源"
          more-to="/explore"
        />

        <div v-if="loading" class="dest__sk">
          <div class="skeleton dest__sk-main" />
          <div class="dest__sk-side">
            <div v-for="i in 3" :key="i" class="skeleton dest__sk-row" />
          </div>
        </div>

        <div v-else-if="error" class="state-error">
          <p>{{ error }}</p>
          <button class="btn btn-ghost btn-sm" @click="reload">重新加载</button>
        </div>

        <div v-else-if="isEmpty(featureScenic)" class="empty">
          <div class="empty__title">暂无景区数据</div>
          <div class="empty__desc">请确认后端服务已启动，且城市数据包已导入</div>
        </div>

        <div v-else class="dest__grid reveal">
          <!-- 主推：大幅 -->
          <router-link :to="`/poi/${featureScenic!.id}`" class="feat">
            <PoiImage
              :poi-id="featureScenic!.id"
              :scene="featureScenic!.scene"
              ratio="auto"
              eager
              :alt="featureScenic!.name"
              class="feat__art"
            />
            <div class="feat__veil" />
            <div class="feat__body">
              <div class="feat__meta">
                <span class="tag tag-gold">{{ featureScenic!.district }}</span>
                <span v-if="featureScenic!.level" class="feat__level">
                  {{ featureScenic!.level }}
                </span>
              </div>
              <h3 class="display feat__title">{{ featureScenic!.name }}</h3>
              <p class="feat__summary">{{ featureScenic!.summary }}</p>
              <div class="feat__foot">
                <span class="num feat__price">
                  {{ featureScenic!.ticket_price > 0 ? `¥${featureScenic!.ticket_price}` : '免费开放' }}
                </span>
                <span class="feat__dur">建议停留 {{ featureScenic!.duration_min }} 分钟</span>
              </div>
            </div>
          </router-link>

          <!-- 次级：紧凑列表 -->
          <div class="dest__side">
            <router-link
              v-for="p in restScenic"
              :key="p.id"
              :to="`/poi/${p.id}`"
              class="scard"
            >
              <PoiImage
                :poi-id="p.id"
                :scene="p.scene"
                ratio="4 / 3"
                :alt="p.name"
                class="scard__art"
              />
              <div class="scard__body">
                <div class="scard__top">
                  <h4 class="scard__name">{{ p.name }}</h4>
                  <span class="scard__arrow">→</span>
                </div>
                <p class="scard__summary">{{ p.summary }}</p>
                <div class="scard__meta">
                  <span class="tag tag-brand">{{ p.district }}</span>
                  <span class="num scard__price">
                    {{ p.ticket_price > 0 ? `¥${p.ticket_price}` : '免费' }}
                  </span>
                </div>
              </div>
            </router-link>
          </div>
        </div>

        <!-- 数据概览：压在区块底部，细线分隔，不做卡片堆 -->
        <div v-if="!loading && !error" class="stats reveal">
          <div v-for="s in stats" :key="s.label" class="stat">
            <span class="num stat__num">{{ s.value }}</span>
            <span class="stat__unit">{{ s.unit }}</span>
            <span class="stat__label">{{ s.label }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- ============ 3. AI 智能行程规划 ============ -->
    <section class="section-xl ai-band">
      <div class="container">
        <div class="ai__grid">
          <div class="ai__copy reveal">
            <span class="eyebrow">AI 智能行程规划</span>
            <h2 class="h1 ai__title">规划不只考虑"去哪里"，<br />还要考虑"哪里装得下"</h2>
            <p class="lead ai__desc">
              系统在生成动线时同步读取各资源点的承载余量，把高位景区的一部分客流，
              顺势引导到承载充足、路程相邻的乡村体验点。判定用规则，生成与解释用大模型，两者各守边界。
            </p>
            <div class="ai__cta">
              <router-link to="/itinerary" class="btn btn-primary btn-lg">生成我的行程</router-link>
              <router-link to="/assistant" class="btn btn-ghost btn-lg">问智脑几个问题</router-link>
            </div>
          </div>

          <ol class="ai__flow reveal">
            <li v-for="f in flow" :key="f.no" class="flowitem">
              <span class="num flowitem__no">{{ f.no }}</span>
              <div class="flowitem__main">
                <span class="flowitem__title">{{ f.title }}</span>
                <span class="flowitem__desc">{{ f.desc }}</span>
              </div>
            </li>
          </ol>
        </div>
      </div>
    </section>

    <!-- ============ 4. 乡村体验（深绿整幅带） ============ -->
    <section class="section-xl rural-band">
      <div class="container">
        <SectionHead
          eyebrow="乡村振兴 · 乡村体验"
          title="把溢出的客流，送进秦岭深处的村子"
          desc="当核心景区承载吃紧，系统会匹配车程 30–60 分钟内的乡村点，用一次真实的乡村体验承接需求——茶园、稻田、橘园、非遗工坊。"
          size="xl"
          tone="light"
          more-text="去看乡村体验"
          more-to="/explore"
        />

        <div v-if="loading" class="grid grid-3">
          <div v-for="i in 3" :key="i" class="skeleton" style="height: 300px; border-radius: 10px" />
        </div>

        <div v-else class="rural__grid">
          <router-link
            v-for="(p, i) in ruralFeature"
            :key="p.id"
            :to="`/poi/${p.id}`"
            class="rcard reveal"
            :class="{ 'rcard--lead': i === 0 }"
          >
            <PoiImage
              :poi-id="p.id"
              :scene="p.scene"
              ratio="auto"
              :alt="p.name"
              class="rcard__art"
            />
            <div class="rcard__veil" />
            <div class="rcard__body">
              <span class="tag tag-gold">{{ p.district }}</span>
              <h3 class="rcard__title">{{ p.name }}</h3>
              <p class="rcard__summary">{{ p.summary }}</p>
            </div>
          </router-link>
        </div>

        <p class="rural__note reveal">
          乡村体验点不计入"热门榜"，而是按承载余量与路程相邻度匹配——这是分流的落点。
        </p>
      </div>
    </section>

    <!-- ============ 5. 旅行足迹与乡村好物 ============ -->
    <section class="section-xl goods">
      <div class="container">
        <SectionHead
          eyebrow="旅行足迹 · 乡村好物"
          title="带回家的，是你走过的那片山"
          desc="每一样好物都锚定在一次乡村体验或一个产地上。不是货架商品，而是可以被回忆复购的旅行余韵。"
          size="xl"
          more-text="了解更多"
          more-to="/assistant"
        />

        <!-- 溯源条：把「体验 → 好物 → 复购」画出来 -->
        <div class="trace reveal">
          <span class="trace__step">到访汉中</span>
          <span class="trace__line" />
          <span class="trace__step">乡村体验</span>
          <span class="trace__line" />
          <span class="trace__step">带走好物</span>
          <span class="trace__line" />
          <span class="trace__step trace__step--end">离境复购</span>
        </div>

        <div v-if="loading" class="grid grid-4">
          <div v-for="i in 4" :key="i" class="skeleton" style="height: 320px; border-radius: 10px" />
        </div>

        <div v-else class="goods__grid">
          <article v-for="g in goods" :key="g.id" class="gcard reveal">
            <div class="gcard__art-wrap">
              <!-- 产品没有自己的图片，用产地乡村点的实拍图 ——
                   "带回家的，是你走过的那片山"，这里正是要显示走过的那片山 -->
              <PoiImage
                :poi-id="g.poi_id"
                :scene="g.scene"
                ratio="4 / 3"
                :alt="`${g.origin_village} · ${g.name}`"
                class="gcard__art"
              />
            </div>
            <div class="gcard__body">
              <span class="gcard__from">来自「{{ g.experience_name || '乡村体验' }}」</span>
              <h3 class="gcard__name">{{ g.name }}</h3>
              <p class="gcard__spec">{{ g.spec }} · {{ g.origin_village }}</p>
              <p class="gcard__story">{{ g.story }}</p>
              <div class="gcard__foot">
                <span class="num gcard__price">¥{{ g.price }}</span>
                <span class="gcard__repurchase">可复购</span>
              </div>
            </div>
          </article>
        </div>

        <p class="goods__note muted small">
          产品不设独立商城入口，全部挂靠体验或产地——这是刻意的设计取舍。
        </p>
      </div>
    </section>

    <!-- ============ 6. 智慧文旅平台价值 ============ -->
    <section class="section-xl value-band">
      <div class="container">
        <SectionHead
          eyebrow="智慧文旅平台价值"
          title="文旅的价值，不该在游客离境那一刻归零"
          desc="传统智慧文旅止步于「游客—景区—离场」。汉游智脑把链条向后延伸：乡村体验成为锚点，特色产品成为可带走的延续，运营数据再回流到资源匹配。"
          size="xl"
          align="center"
        />

        <div class="pillars">
          <article v-for="p in pillars" :key="p.no" class="pillar reveal">
            <span class="num pillar__no">{{ p.no }}</span>
            <h3 class="h3 pillar__title">{{ p.title }}</h3>
            <p class="pillar__desc">{{ p.desc }}</p>
          </article>
        </div>

        <!-- 闭环：收束为一条细线流程 -->
        <div class="loop reveal">
          <span class="loop__label">业务闭环</span>
          <ol class="loop__list">
            <li v-for="(t, i) in ['游客需求', 'AI 规划', '乡村引流', '乡村体验', '产品消费', '离境复购']" :key="t" class="loop__item">
              <span class="num loop__no">{{ i + 1 }}</span>
              <span class="loop__text">{{ t }}</span>
            </li>
            <li class="loop__item loop__item--ai">
              <span class="num loop__no">7</span>
              <span class="loop__text">AI 运营归因 → 优化匹配</span>
            </li>
          </ol>
        </div>

        <div class="value__cta">
          <router-link to="/login" class="btn btn-primary btn-lg">登录 / 注册</router-link>
          <router-link to="/login?role=admin" class="btn btn-ghost btn-lg">运营管理入口</router-link>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* ============================================================
   1.5 分流公告
   ------------------------------------------------------------
   组件本身只是一张卡（不含容器与上下留白），留白在这里给。
   上 32px：公告要贴着 Hero，离得远就不像"当前正在发生的事"了。
   下不设留白：紧接的 .dest 是 section-xl（上下各 128px），
   那 128px 已经足够把两者分开，这里再加就成了双份间距。
   ============================================================ */
.dnbsec {
  padding-top: var(--sp-6);
}
/* 多条叠放：卡间距 12px，让它们读成一组 */
.dnbsec__stack {
  display: grid;
  gap: var(--sp-3);
}

/* ============================================================
   2. 汉中精选目的地 —— 左大右小，刻意不对称
   ============================================================ */
.dest {
  background: var(--paper);
}
.dest__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.32fr) minmax(0, 1fr);
  gap: var(--sp-6);
}

.dest__sk {
  display: grid;
  grid-template-columns: minmax(0, 1.32fr) minmax(0, 1fr);
  gap: var(--sp-6);
}
.dest__sk-main {
  height: 480px;
}
.dest__sk-side {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.dest__sk-row {
  flex: 1;
}

/* 主推大图 */
.feat {
  position: relative;
  overflow: hidden;
  border-radius: var(--r-lg);
  min-height: 480px;
  display: flex;
  align-items: flex-end;
  box-shadow: var(--sh-2);
}
.feat__art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
  transition: transform 900ms var(--ease);
}
.feat:hover .feat__art {
  transform: scale(1.03);
}
.feat__veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    180deg,
    rgba(11, 33, 25, 0.12) 0%,
    rgba(11, 33, 25, 0.24) 46%,
    rgba(11, 33, 25, 0.86) 100%
  );
  transition: opacity var(--dur-2) var(--ease);
}
.feat__body {
  position: relative;
  padding: var(--sp-7);
  color: #fff;
  width: 100%;
}
.feat__meta {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.feat__level {
  font-size: var(--fs-xs);
  letter-spacing: 0.06em;
  color: var(--gold-300);
}
.feat__title {
  margin-top: var(--sp-4);
  font-size: 34px;
  color: #fff;
}
.feat__summary {
  margin-top: var(--sp-4);
  max-width: 40em;
  font-size: var(--fs-sm);
  line-height: 1.85;
  color: rgba(255, 255, 255, 0.8);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.feat__foot {
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
  border-top: 1px solid rgba(255, 255, 255, 0.22);
  display: flex;
  align-items: baseline;
  gap: var(--sp-4);
}
.feat__price {
  font-size: 22px;
  font-weight: 600;
  color: var(--gold-300);
}
.feat__dur {
  font-size: var(--fs-xs);
  color: rgba(255, 255, 255, 0.66);
}

/* 次级列表 */
.dest__side {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.scard {
  flex: 1;
  display: grid;
  grid-template-columns: 132px minmax(0, 1fr);
  gap: 0;
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  overflow: hidden;
  transition: box-shadow var(--dur-2) var(--ease), border-color var(--dur-2) var(--ease),
    transform var(--dur-2) var(--ease);
}
.scard:hover {
  box-shadow: var(--sh-2);
  border-color: var(--line);
  transform: translateX(3px);
}
.scard__art {
  height: 100%;
  border-radius: 0;
}
.scard__body {
  padding: var(--sp-4) var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  min-width: 0;
}
.scard__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}
.scard__name {
  font-size: 17px;
  font-weight: 600;
  color: var(--ink-900);
  transition: color var(--dur-1) var(--ease);
}
.scard:hover .scard__name {
  color: var(--brand-700);
}
.scard__arrow {
  color: var(--gold-500);
  flex: none;
  transition: transform var(--dur-2) var(--ease);
}
.scard:hover .scard__arrow {
  transform: translateX(4px);
}
.scard__summary {
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--ink-500);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.scard__meta {
  margin-top: auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}
.scard__price {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--gold-600);
}

/* 概览数字：细线分隔，不套卡片 */
.stats {
  margin-top: var(--sp-8);
  padding-top: var(--sp-6);
  border-top: 1px solid var(--line);
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-6);
}
.stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}
.stat + .stat {
  border-left: 1px solid var(--line-soft);
  padding-left: var(--sp-6);
}
.stat__num {
  font-size: 38px;
  font-weight: 600;
  color: var(--brand-700);
  line-height: 1;
}
.stat__unit {
  font-size: var(--fs-sm);
  color: var(--warm-500);
}
.stat__label {
  flex-basis: 100%;
  font-size: var(--fs-sm);
  color: var(--ink-500);
  margin-top: var(--sp-1);
}

/* ============================================================
   3. AI 智能行程规划
   ============================================================ */
.ai-band {
  background: var(--paper-2);
  border-top: 1px solid var(--line-soft);
  border-bottom: 1px solid var(--line-soft);
}
.ai__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: var(--sp-9);
  align-items: center;
}
.ai__title {
  margin-top: var(--sp-5);
  color: var(--ink-900);
}
.ai__desc {
  margin-top: var(--sp-5);
  max-width: 34em;
}
.ai__cta {
  margin-top: var(--sp-7);
  display: flex;
  gap: var(--sp-3);
  flex-wrap: wrap;
}

.ai__flow {
  display: flex;
  flex-direction: column;
  gap: 0;
  border-top: 1px solid var(--line);
}
.flowitem {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-5);
  padding: var(--sp-5) 0;
  border-bottom: 1px solid var(--line);
  transition: background var(--dur-2) var(--ease);
}
.flowitem:hover {
  background: rgba(255, 255, 255, 0.6);
}
.flowitem__no {
  flex: none;
  width: 34px;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.14em;
  color: var(--gold-600);
  padding-top: 3px;
}
.flowitem__main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.flowitem__title {
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--ink-900);
}
.flowitem__desc {
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--ink-500);
}

/* ============================================================
   4. 乡村体验（深绿整幅带）
   ============================================================ */
.rural-band {
  background: var(--brand-800);
  color: var(--brand-100);
}
.rural__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-5);
}
.rcard {
  position: relative;
  overflow: hidden;
  border-radius: var(--r-lg);
  min-height: 340px;
  display: flex;
  align-items: flex-end;
  border: 1px solid rgba(219, 233, 227, 0.14);
  transition: border-color var(--dur-2) var(--ease), transform var(--dur-2) var(--ease);
}
.rcard--lead {
  grid-row: span 1;
  min-height: 400px;
}
.rcard:hover {
  border-color: rgba(226, 202, 145, 0.5);
  transform: translateY(-3px);
}
.rcard__art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
  transition: transform 900ms var(--ease);
}
.rcard:hover .rcard__art {
  transform: scale(1.04);
}
.rcard__veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    180deg,
    rgba(11, 33, 25, 0.16) 0%,
    rgba(11, 33, 25, 0.42) 50%,
    rgba(11, 33, 25, 0.9) 100%
  );
}
.rcard__body {
  position: relative;
  padding: var(--sp-6);
  color: #fff;
  width: 100%;
}
.rcard__title {
  margin-top: var(--sp-3);
  font-family: var(--font-display);
  font-size: 21px;
  font-weight: 600;
  color: #fff;
}
.rcard--lead .rcard__title {
  font-size: 26px;
}
.rcard__summary {
  margin-top: var(--sp-3);
  font-size: var(--fs-sm);
  line-height: 1.75;
  color: rgba(255, 255, 255, 0.76);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.rural__note {
  margin-top: var(--sp-6);
  padding-top: var(--sp-5);
  border-top: 1px solid rgba(219, 233, 227, 0.14);
  font-size: var(--fs-sm);
  color: var(--brand-300);
}

/* ============================================================
   5. 旅行足迹与乡村好物
   ============================================================ */
.goods {
  background: var(--paper);
}

/* 溯源条 */
.trace {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  padding: var(--sp-5) var(--sp-6);
  background: var(--paper-2);
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  margin-bottom: var(--sp-6);
}
.trace__step {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--brand-700);
  letter-spacing: 0.04em;
}
.trace__step--end {
  color: var(--gold-600);
}
.trace__line {
  flex: 1 1 32px;
  height: 1px;
  background: linear-gradient(90deg, var(--line), var(--gold-300));
}

.goods__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-5);
}
.gcard {
  display: flex;
  flex-direction: column;
  background: transparent;
  border-top: 2px solid var(--brand-600);
  padding-top: 0;
  transition: transform var(--dur-2) var(--ease);
}
.gcard:hover {
  transform: translateY(-4px);
}
.gcard__art-wrap {
  overflow: hidden;
  border-radius: var(--r-md);
}
.gcard__art {
  border-radius: 0;
  transition: transform 900ms var(--ease);
}
.gcard:hover .gcard__art {
  transform: scale(1.04);
}
.gcard__body {
  padding: var(--sp-4) 0 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  flex: 1;
}
.gcard__from {
  font-size: 11px;
  letter-spacing: 0.04em;
  color: var(--gold-600);
  line-height: 1.6;
}
.gcard__name {
  font-family: var(--font-display);
  font-size: 19px;
  font-weight: 600;
  color: var(--ink-900);
}
.gcard__spec {
  font-size: var(--fs-xs);
  color: var(--warm-500);
}
.gcard__story {
  font-size: var(--fs-sm);
  color: var(--ink-500);
  line-height: 1.8;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.gcard__foot {
  margin-top: auto;
  padding-top: var(--sp-4);
  border-top: 1px solid var(--line-soft);
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.gcard__price {
  font-size: 19px;
  font-weight: 600;
  color: var(--gold-600);
}
.gcard__repurchase {
  font-size: var(--fs-cap);
  letter-spacing: 0.06em;
  color: var(--brand-500);
}
.goods__note {
  margin-top: var(--sp-6);
  text-align: center;
}

/* ============================================================
   6. 平台价值
   ============================================================ */
.value-band {
  background: var(--paper-3);
}
.pillars {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-7);
}
.pillar {
  padding-top: var(--sp-5);
  border-top: 2px solid var(--brand-700);
}
.pillar__no {
  font-size: var(--fs-cap);
  letter-spacing: 0.2em;
  color: var(--gold-600);
  font-weight: 700;
}
.pillar__title {
  margin-top: var(--sp-3);
  color: var(--ink-900);
}
.pillar__desc {
  margin-top: var(--sp-3);
  font-size: var(--fs-sm);
  line-height: 1.85;
  color: var(--ink-500);
}

/* 闭环细线 */
.loop {
  margin-top: var(--sp-9);
  padding-top: var(--sp-6);
  border-top: 1px solid var(--line);
}
.loop__label {
  font-size: var(--fs-cap);
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--warm-500);
  font-weight: 600;
}
.loop__list {
  margin-top: var(--sp-5);
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3) var(--sp-5);
}
.loop__item {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  padding-right: var(--sp-5);
  border-right: 1px solid var(--line);
}
.loop__item:last-child {
  border-right: none;
  padding-right: 0;
}
.loop__no {
  font-size: 11px;
  font-weight: 700;
  color: var(--warm-400);
  letter-spacing: 0.1em;
}
.loop__text {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-700);
}
.loop__item--ai .loop__no,
.loop__item--ai .loop__text {
  color: var(--gold-600);
}

.value__cta {
  margin-top: var(--sp-8);
  display: flex;
  gap: var(--sp-3);
  justify-content: center;
  flex-wrap: wrap;
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

/* ============================================================
   响应式
   ============================================================ */
@media (max-width: 1440px) {
  .feat,
  .dest__sk-main {
    min-height: 440px;
    height: 440px;
  }
}

@media (max-width: 1080px) {
  .dest__grid,
  .dest__sk {
    grid-template-columns: minmax(0, 1fr);
  }
  .feat,
  .dest__sk-main {
    min-height: 400px;
    height: 400px;
  }
  .ai__grid {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--sp-7);
  }
  .rural__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .rcard--lead {
    grid-column: span 2;
  }
  .goods__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .pillars {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--sp-6);
  }
}

@media (max-width: 720px) {
  .feat,
  .dest__sk-main {
    min-height: 340px;
    height: 340px;
  }
  .feat__body {
    padding: var(--sp-5);
  }
  .feat__title {
    font-size: 26px;
  }
  .scard {
    grid-template-columns: 104px minmax(0, 1fr);
  }
  .stats {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--sp-4);
  }
  .stat + .stat {
    border-left: none;
    border-top: 1px solid var(--line-soft);
    padding-left: 0;
    padding-top: var(--sp-4);
  }
  .rural__grid,
  .goods__grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .rcard--lead {
    grid-column: span 1;
    min-height: 340px;
  }
  .trace {
    padding: var(--sp-4);
  }
  .loop__item {
    border-right: none;
    padding-right: 0;
  }
  .value__cta .btn {
    flex: 1 1 auto;
  }
}
</style>
