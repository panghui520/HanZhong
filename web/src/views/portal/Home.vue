<script setup lang="ts">
/**
 * Home —— 首页
 *
 * ★ 定位：**面向游客的汉中文旅首页**，不是项目演示页、也不是技术介绍页。
 *   只出现"游客能获得什么"，不出现"系统是怎么实现的"。
 *   实现说明（承载余量、规则引擎、大模型边界、业务闭环）
 *   一律留在管理端与答辩材料里 —— 那些词出现在首页就是演示页的味道。
 *
 * ============================================================
 * 这一版的版面（对齐参考设计稿的"文旅官网"结构）
 * ============================================================
 *   1.  Hero 满屏大图：主标题 + 副标 + 搜索框 + 竖排城市标签 + 页码
 *   2.  五联功能入口（景点 / 乡村体验 / 美食 / 住宿 / 交通）
 *   3.  中部三栏：推荐景点大图卡 ／ 热门推荐列表 ／ AI 助手对话卡
 *   4.  精选景点：四张横排图卡 + 向上按钮
 *   5.  乡村体验（深绿整幅带）
 *   6.  汉中特色好物
 *   7.  离境复购（三栏收束）
 *
 * ★ 与前版的差别，只有"呈现方式"，没有"数据来源"：
 *   全部数据仍来自 getCityPack() 与 getBanners()，本文件不新增后端接口、
 *   不改数据库、不动路由。功能入口的落点全部使用**既有路由**，
 *   进入 Explore 后靠它既有的 `?type=` 参数落到对应类别。
 *
 * ★ 也保留了前版两条"面向游客"的措辞原则：
 *   ① 不写"承载 / 溢出 / 调度 / 分流贡献"这类调度口径，写"避开拥挤"；
 *   ② 功能入口卡上的类别名沿用 `BUSINESS_LABEL`，不另起一套文案 ——
 *      详情页、卡片角标、管理端用的都是同一套 `business_type` 词汇，
 *      这里改成口语名就会出现同一个东西两个名字。
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PoiImage from '@/components/PoiImage.vue'
import HeroCarousel from '@/components/HeroCarousel.vue'
import InkMotif from '@/components/InkMotif.vue'
import DiversionNoticeBar from '@/components/DiversionNoticeBar.vue'
import SectionHead from '@/components/SectionHead.vue'
import { getCityPack } from '@/api/citypack'
import { isEmpty, useAsync } from '@/composables/useAsync'
import { useDiversionNotices } from '@/composables/useDiversionNotices'
import { BUSINESS_LABEL, type BusinessType, type Product } from '@/types'

const router = useRouter()

const { data, loading, error, reload } = useAsync(getCityPack)

/**
 * 分流公告（M5 续）。
 *
 * 用 `actionable` 而不是 `list`：**候选全满的公告不显示**。
 * 一条"建议改往 A、B、C"而 A、B、C 现在都已不宽裕的提示，
 * 对游客只是噪音；它该由运营撤下（运营列表会提示"该撤下了"）。
 */
const { actionable: diversionNotices } = useDiversionNotices()

const allPois = computed(() => data.value?.pois ?? [])
const scenics = computed(() => allPois.value.filter((p) => p.business_type === 'SCENIC'))
const rurals = computed(() => allPois.value.filter((p) => p.business_type === 'RURAL_SPOT'))
const products = computed(() => data.value?.products ?? [])
const experiences = computed(() => data.value?.experiences ?? [])

function countOfType(t: BusinessType) {
  return allPois.value.filter((p) => p.business_type === t).length
}

/* ============================================================
 * 2. 五个功能入口
 * ------------------------------------------------------------
 * ★ 图标是**内联 SVG**，不是图标库：这个项目没有引 icon 依赖，
 *   为五个图标装一个包不划算，而手写 path 只有十几行。
 * ★ 落点全部走既有路由。Explore 支持 `?type=`（见 Explore.vue
 *   的 initialType），所以"美食 / 住宿 / 交通"直接落到对应类别，
 *   与参考稿里"点进去就是那一类"的期望一致 —— 这不是新功能，
 *   是 Explore 早就支持的参数。
 * ============================================================ */
interface Entry {
  key: string
  label: string
  desc: string
  to: string
  /** 图标 path 集合，24×24 视口 */
  paths: string[]
}

const ENTRIES: Entry[] = [
  {
    key: 'SCENIC',
    label: '景点',
    desc: '山水人文 · 诗画汉中',
    to: '/explore?type=SCENIC',
    paths: [
      'M2.6 17.2 8.4 8.6l3.4 4.6 2.4-3 5.2 7H2.6Z',
      'M15.6 4.6a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2Z',
    ],
  },
  {
    key: 'RURAL_SPOT',
    label: '乡村体验',
    desc: '走进乡村 · 体验田园',
    to: '/explore?type=RURAL_SPOT',
    paths: [
      'M3 10.6 12 3.6l9 7',
      'M5.2 10.2v9.6h13.6v-9.6',
      'M9.6 19.8v-5.6h4.8v5.6',
    ],
  },
  {
    key: 'FOOD',
    label: '美食',
    desc: '地道风味 · 舌尖汉中',
    to: '/explore?type=FOOD',
    paths: [
      'M6.2 3v6.4a2 2 0 0 0 4 0V3',
      'M8.2 9.4v11.6',
      'M15.4 3v18',
    ],
  },
  {
    key: 'LODGING',
    label: '住宿',
    desc: '精选好宿 · 舒适之选',
    to: '/explore?type=LODGING',
    paths: [
      'M3.2 15v4.4',
      'M3.2 12.2h17.6V15',
      'M20.8 19.4V15',
      'M3.2 12.2V8.4h9.6a3.6 3.6 0 0 1 3.6 3.6V15',
      'M6.6 8.4h4.6',
    ],
  },
  {
    key: 'TRANSPORT',
    label: '交通',
    desc: '便捷出行 · 轻松抵达',
    to: '/explore?type=TRANSPORT',
    paths: [
      'M7 3.4h10a2 2 0 0 1 2 2v9.2a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5.4a2 2 0 0 1 2-2Z',
      'M5 8.6h14',
      'M6.6 20.6 5.4 16.6',
      'M17.4 20.6l1.2-4',
    ],
  },
]

/**
 * 入口卡的副标题保持"编辑口中的一句话"。
 * ★ 但数字部分不用写死 —— 卡上带真实条数，数据一变就跟着变，
 *   这与 Explore 的类别按钮同源（都数 allPois）。没有数据的类别不显示。
 */
const entries = computed(() =>
  ENTRIES.filter((e) => countOfType(e.key as BusinessType) > 0)
)

/**
 * 今日游览提示的收起状态。
 * 公告条默认展开，按钮只做"看过了"的收起 —— 不做"再展开"，
 * 因为它的位置就在首屏正中，收起来之后没有理由再拉开。
 */
const noticeHidden = ref(false)

/* ============================================================
 * 3. 中部三栏
 * ============================================================ */

/** 左：推荐景点大图卡 —— 首条做大幅主推 */
const featureScenic = computed(() => scenics.value[0])

/**
 * 中：热门推荐。
 *
 * ★ 这是参考稿里最"编辑部"的一块：它混排了**景点、美食、乡村**，
 *   读起来像一本旅行册的目录，而不是按类别切开的三张表。
 *   所以这里刻意跨业态取数（1 景区 + 1 餐饮 + 1 乡村），
 *   顺序由数据包编码顺序决定 —— 想换推荐谁，改数据包顺序即可。
 *   若某一类没有数据（比如数据包里没有餐饮），这一格自动让位给下一个景点，
 *   不会留空。
 */
const hotPicks = computed(() => {
  const picks = [...allPois.value].filter((p) =>
    ['SCENIC', 'FOOD', 'RURAL_SPOT', 'LODGING'].includes(p.business_type)
  )
  const want: BusinessType[] = ['SCENIC', 'FOOD', 'RURAL_SPOT']
  const out: typeof picks = []
  for (const t of want) {
    const hit = picks.find((p) => p.business_type === t && !out.includes(p))
    if (hit) out.push(hit)
  }
  // 不足 3 条时用同类补齐，保证列表不塌
  for (const p of picks) {
    if (out.length >= 3) break
    if (!out.includes(p)) out.push(p)
  }
  return out.slice(0, 3)
})

/**
 * 右：AI 助手对话卡。
 *
 * ★ 这一块**不放输入框**：对话要带上下文、要流式、要处理中断与错误，
 *   那套逻辑在 Assistant / Agent 两个页面里已经完整存在。
 *   在首页塞一个"半成品输入框"会引出两类问题：
 *   ① 提交后要么跳走（那不如直接给按钮）、要么在原地开一段没有历史的对话
 *      （刷新即丢，用户以为丢了记录）；
 *   ② 首页还要额外承担流式渲染与中断处理的复杂度。
 *   所以这里做成**"快捷提问入口"**：点一下带着问题进 /assistant，
 *   由那一页接着答。问题文案取自已上线的离线语料覆盖范围
 *   （见 M3 的知识库），不承诺知识库里没有的东西。
 */
const aiAsks = [
  '帮我规划 3 天汉中行程',
  '石门栈道怎么去？',
  '汉中特产有哪些？',
]

function askInAssistant(q: string) {
  router.push({ path: '/assistant', query: { q } })
}

/* ============================================================
 * 4. 精选景点（四张横排图卡）
 * ------------------------------------------------------------
 * ★ 与左上的"推荐景点"是同一批数据、不同取法：
 *   上面取 1 条做**深度**（大图 + 简介 + 停留时长），
 *   这里取 4 条做**广度**（一屏看完汉中能去哪）。两处都从
 *   数据包顺序取，不额外请求。
 * ============================================================ */
const showcaseScenics = computed(() => {
  const list = scenics.value
  // 已经在大图位出现过的那一条往后排，避免同一张图连着出现两次
  return [...list.slice(1), ...list.slice(0, 1)].slice(0, 4)
})

/** 景点的业态角标：用数据包里第一个标签，没有则回落"景区" */
function chipOf(tags: string[], fallback: string) {
  return tags?.[0] || fallback
}

/**
 * 乡村好物只放 4 张卡，按分类各取一款。
 *
 * 后端的列表顺序是产品编码升序（即数据包的编号顺序），直接切前四条会得到
 * 三款茶加一款米——读起来像"某一类好物"。改成每个分类取第一款，
 * 四张卡覆盖四个品类；同时因为茶叶编码在最前，招牌的汉中仙毫仍然排在首位。
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

/**
 * 底部三个数字的取值。**标签要说游客的话**：
 * 原来写的是"文旅资源点"（行业口径），游客不会这么叫自己想去的地方。
 *
 * 这三个数字原来也传给 Hero 展示一份，现已从 Hero 移除：
 * Hero 收到参考稿那种"横幅级"高度后（见 tokens.css --hero-h 的注释），
 * 在它里面塞三个数字会把标题压到很挤；而下方"精选景点"末尾
 * 已经有一处同样口径的数字带，重复出现反而弱化它。
 */
const stats = computed(() => [
  { label: '汉中好去处', value: allPois.value.length, unit: '处' },
  { label: '乡村体验', value: experiences.value.length, unit: '项' },
  { label: '乡村好物', value: products.value.length, unit: '款' },
])

const flow = [
  { no: '01', title: '说说你的行程', desc: '天数、同行的人、想走多快、偏爱什么' },
  { no: '02', title: '挑出合适的地方', desc: '景点、美食、住宿、乡村体验，按偏好来选' },
  { no: '03', title: '避开人多的时段', desc: '结合客流，把热门点位排到更从容的时候' },
  { no: '04', title: '给你能照着走的方案', desc: '每天的动线、停留时长与推荐理由' },
]

/**
 * 离境复购三栏。事实依据都来自数据包：
 * 每款产品都有 origin_village 与所属体验，可核对。
 */
const repurchase = [
  {
    no: '01',
    title: '认准你买过的那一款',
    desc: '每一样好物都记着它的产地与作坊。想再买时循着同一款下单就行，不必重新挑一遍。',
  },
  {
    no: '02',
    title: '从同一片产地寄出',
    desc: '茶叶、黑米、腊味、橘酱都从村里直接发出，不经过层层转手，价格和来路都清楚。',
  },
  {
    no: '03',
    title: '过了季节也买得到',
    desc: '油菜花只开一个月，茶园与作坊却一年都在。这次没赶上，回家下单也不耽误。',
  },
]

/* ---------- 滚动进入视口淡入（只用 IntersectionObserver，不引第三方库） ----------
   注意两件事：
   1. 阈值不能用固定比例。首页有大区块，在 1050 高的视口里即使完全可见
      也未必达到某个交叉比，会让整块永停在 opacity:0。
      改成"元素进入视口即可触发"，用负的 rootMargin 控制延迟。
   2. 大多数 .reveal 元素在 loading === true 时还不存在（在 v-else 分支里），
      所以必须在数据到达、DOM 更新之后再挂 observer。这里用 watch + nextTick。 */
let io: IntersectionObserver | undefined
let mo: MutationObserver | undefined
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
onMounted(() => {
  nextTick(observeReveals)
  // AI 对话卡与热门推荐的数据源都在同一份 pack 里，但列表条数是后来才定的，
  // 用 MutationObserver 兜底：任何后插入的 .reveal 都会被接管，不会留白。
  if (typeof MutationObserver !== 'undefined' && root.value) {
    mo = new MutationObserver(() => nextTick(observeReveals))
    mo.observe(root.value, { childList: true, subtree: true })
  }
})
onUnmounted(() => {
  io?.disconnect()
  mo?.disconnect()
})

/** 回到顶部（精选景点区右侧的向上按钮） */
function backToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}
</script>

<template>
  <div ref="root" class="home">
    <!-- ============ 1. 超大轮播 Hero（含搜索框 / 城市标签 / 页码） ============ -->
    <HeroCarousel />

    <!-- ============ 1.2 宣纸淡墨背景层 ============
         它不是"一整幅山水铺满"，而是**十来块拆开的淡墨**，分别落在
         上边缘、左右两侧、留白处、下边缘 —— 见下方每个 .hi--* 的位置。

         三条口径（用户明确要求，也是这一层的验收标准）：
         · 浓度 5%~12%（opacity 逐个给，不是统一值 —— 统一值会让
           每一块一样重，反而看出是"贴图"）；
         · 中间主内容区**不放**任何墨块，保证文字阅读不被干扰；
         · 远看是干净的暖白网页，近看才发现有山。

         放在 Hero 之后、所有 section 之前，`.home__ink` 的 top 取
         --hero-h —— 墨层从 Hero 下沿开始，Hero 是深色大图，墨块铺在
         它后面也看不见，不如直接从下面开始，省得白算。 -->
    <div class="home__ink" aria-hidden="true">
      <!-- 上边缘：Hero 正下方，两片不同朝向的群峰，接住首屏视线 -->
      <InkMotif motif="peaks" tone="ink" :opacity="0.12" :blur="1.3" width="44%" class="hi hi--t1" />
      <InkMotif motif="peaks" tone="moss" :opacity="0.09" :blur="1.8" flip-x width="30%" class="hi hi--t2" />

      <!-- 左侧：一片侧峰（内缘收细、不切硬边）+ 一块远渚 -->
      <InkMotif
        motif="cliff" tone="ink" :opacity="0.105" :blur="1.6" width="330px"
        fade-x="0%" fade-y="46%" class="hi hi--l1"
      />
      <InkMotif
        motif="islet" tone="moss" :opacity="0.1" :blur="1.8" width="330px"
        fade-x="0%" fade-y="56%" class="hi hi--l2"
      />

      <!-- 右侧：一片侧峰（镜像）+ 一块远渚 -->
      <InkMotif
        motif="cliff" tone="ash" :opacity="0.09" :blur="2" flip-x width="300px"
        fade-x="100%" fade-y="58%" class="hi hi--r1"
      />
      <InkMotif
        motif="islet" tone="ash" :opacity="0.085" :blur="2.2" flip-x width="300px"
        fade-x="100%" fade-y="44%" class="hi hi--r2"
      />

      <!-- 云雾：只落在留白里，靠大 blur 化成雾。三块错开、浓度不同 -->
      <InkMotif motif="mist" tone="ash" :opacity="0.1" :blur="11" width="54%" class="hi hi--m1" />
      <InkMotif motif="mist" tone="ink" :opacity="0.08" :blur="14" flip-x width="46%" class="hi hi--m2" />
      <InkMotif motif="mist" tone="moss" :opacity="0.09" :blur="13" width="50%" class="hi hi--m3" />

      <!-- 下边缘：长山脊压底 + 一道水纹收尾 + 角上一小片群峰 -->
      <InkMotif motif="ridge" tone="ink" :opacity="0.115" :blur="1.3" width="86%" class="hi hi--b1" />
      <InkMotif motif="water" tone="moss" :opacity="0.095" :blur="1.2" width="64%" class="hi hi--b2" />
      <InkMotif motif="peaks" tone="ash" :opacity="0.085" :blur="2.2" flip-x width="36%" class="hi hi--b3" />
    </div>

    <!-- ============ 1.5 今日游览提示（M5 续） ============
         排在 Hero 之后、所有内容之前：这是整站唯一一条"系统主动对游客说话"的
         内容，埋到下半页等于没发。没有生效公告时整块不渲染，不留空占位。 -->
    <section v-if="diversionNotices.length && !noticeHidden" class="dnbsec">
      <div class="container dnbsec__stack">
        <DiversionNoticeBar
          v-for="n in diversionNotices"
          :key="n.id"
          :notice="n"
          variant="band"
        />
        <button class="dnbsec__hide" @click="noticeHidden = true">知道了，先收起来</button>
      </div>
    </section>

    <!-- ============ 2. 五联功能入口 ============
         参考稿把它做成"五个横向入口条"，压在 Hero 与内容之间，
         起到"这一站能做什么"的索引作用。数值来自真实数据，不写死。
         背景的水墨由 .home__ink 统一负责，这里不再单独铺。 -->
    <section class="entries">
      <div class="container">
        <ul class="entries__grid">
          <li v-for="e in entries" :key="e.key">
            <router-link :to="e.to" class="ecard">
              <span class="ecard__ico" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="22" height="22">
                  <path
                    v-for="(d, i) in e.paths"
                    :key="i"
                    :d="d"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.7"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </span>
              <span class="ecard__main">
                <span class="ecard__label">
                  {{ e.label }}
                  <em class="num ecard__n">{{ countOfType(e.key as BusinessType) }}</em>
                </span>
                <span class="ecard__desc">{{ e.desc }}</span>
              </span>
              <span class="ecard__arrow" aria-hidden="true">→</span>
            </router-link>
          </li>
        </ul>
      </div>
    </section>

    <!-- ============ 3. 中部三栏：推荐景点 ／ 热门推荐 ／ AI 助手 ============ -->
    <section class="section-xl mid">
      <div class="container">
        <div v-if="loading" class="mid__sk">
          <div class="skeleton mid__sk-feat" />
          <div class="skeleton mid__sk-col" />
          <div class="skeleton mid__sk-col" />
        </div>

        <div v-else-if="error" class="state-error">
          <p>{{ error }}</p>
          <button class="btn btn-ghost btn-sm" @click="reload">重新加载</button>
        </div>

        <div v-else-if="isEmpty(featureScenic)" class="empty">
          <div class="empty__title">暂时没有可推荐的目的地</div>
          <div class="empty__desc">内容可能正在更新，稍后再来看看，或先去「探索汉中」翻一翻。</div>
        </div>

        <div v-else class="mid__grid">
          <!-- 3a. 推荐景点：大图卡，带 1/3 角标 -->
          <article class="mid__featwrap reveal">
            <router-link :to="`/poi/${featureScenic!.id}`" class="ftile">
              <PoiImage
                :poi-id="featureScenic!.id"
                :scene="featureScenic!.scene"
                ratio="auto"
                eager
                :alt="featureScenic!.name"
                class="ftile__art"
              />
              <div class="ftile__veil" />
              <span class="ftile__badge">推荐景点</span>
              <div class="ftile__body">
                <h3 class="display ftile__title">{{ featureScenic!.name }}</h3>
                <div class="ftile__meta">
                  <span>{{ featureScenic!.district }}</span>
                  <i class="ftile__dot" />
                  <span v-if="featureScenic!.duration_min">
                    {{ (featureScenic!.duration_min / 60).toFixed(1) }}h 游览
                  </span>
                  <i v-if="featureScenic!.level" class="ftile__dot" />
                  <span v-if="featureScenic!.level">{{ featureScenic!.level }}</span>
                </div>
                <p class="ftile__desc">{{ featureScenic!.summary }}</p>
                <span class="ftile__cta">查看详情 <em aria-hidden="true">→</em></span>
              </div>
              <span class="ftile__idx num">1 / 3</span>
            </router-link>
          </article>

          <!-- 3b. 热门推荐：跨业态混排列表 -->
          <section class="mid__hot reveal">
            <header class="mid__hot-head">
              <h3 class="mid__hot-title">
                <span class="mid__hot-ico" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="16" height="16">
                    <path
                      d="M12 3.4c.6 3.2-.4 5-1.9 6.4-1.3 1.2-2.1 2.4-2.1 4a4 4 0 1 0 8 0c0-1.7-1-3-1.7-4.3-.5-.9-.8-1.8-.6-2.7-1 .6-1.6 1.6-1.7 2.6-1.3-1.3-1.4-3.6 0-6Z"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="1.6"
                      stroke-linejoin="round"
                    />
                  </svg>
                </span>
                热门推荐
              </h3>
              <router-link to="/explore" class="mid__hot-more">查看更多 →</router-link>
            </header>
            <ul class="hlist">
              <li v-for="p in hotPicks" :key="p.id">
                <router-link :to="`/poi/${p.id}`" class="hrow">
                  <PoiImage
                    :poi-id="p.id"
                    :scene="p.scene"
                    ratio="1 / 1"
                    :alt="p.name"
                    class="hrow__art"
                  />
                  <span class="hrow__main">
                    <span class="hrow__name">{{ p.name }}</span>
                    <span class="hrow__sub">
                      {{ BUSINESS_LABEL[p.business_type] }}
                      <template v-if="p.district"> · {{ p.district }}</template>
                    </span>
                    <span class="hrow__desc">{{ p.summary }}</span>
                  </span>
                  <span class="hrow__arrow" aria-hidden="true">→</span>
                </router-link>
              </li>
            </ul>
          </section>

          <!-- 3c. AI 助手：快捷提问入口（不带半成品输入框，理由见 script 注释） -->
          <aside class="mid__ai reveal">
            <header class="aicard__head">
              <span class="aicard__mark" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="18" height="18">
                  <path
                    d="M4.6 5.4h14.8a1.6 1.6 0 0 1 1.6 1.6v8.2a1.6 1.6 0 0 1-1.6 1.6H9.6L5.4 20.4v-3.6H4.6A1.6 1.6 0 0 1 3 15.2V7a1.6 1.6 0 0 1 1.6-1.6Z"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linejoin="round"
                  />
                </svg>
              </span>
              <span class="aicard__id">
                <span class="aicard__name">汉游智脑</span>
                <span class="aicard__role">你的专属旅行助手</span>
              </span>
            </header>

            <p class="aicard__hello">
              你好！我是汉游智脑，可以帮你推荐路线、解答景点信息、规划行程，
              也能为你推荐当地美食和特产。
            </p>

            <ul class="aicard__asks">
              <li v-for="q in aiAsks" :key="q">
                <button class="aicard__ask" @click="askInAssistant(q)">
                  {{ q }}
                </button>
              </li>
            </ul>

            <router-link to="/assistant" class="aicard__go">
              向我提问 <em aria-hidden="true">→</em>
            </router-link>
          </aside>
        </div>
      </div>
    </section>

    <!-- ============ 4. 精选景点（四张横排 + 向上按钮） ============
         参考稿里这一段是**纸感底 + 右下角一片极淡云山**，
         标题左下、卡片成排，末尾接一条概览数字。
         这里把云山放在右下（`.picks__lead` 那片留白正对的位置）。 -->
    <section class="section-xl picks">
      <div class="container picks__wrap">
        <div class="picks__lead reveal">
          <!-- 参考稿这里是一条"小字 + 竖排英文"的眉标，不是标准 SectionHead，
               所以不复用组件，单独排版；右侧留出向上按钮的位置 -->
          <span class="picks__eyebrow">汉中必打卡</span>
          <h2 class="picks__title">精选景点</h2>
          <!-- 飞鸟装饰：参考稿标题区右侧有一只飞鸟，这里用极简 SVG 呼应 -->
          <svg class="picks__bird" viewBox="0 0 64 24" width="64" height="24" aria-hidden="true">
            <path
              d="M2 15c6-1 10-6 14-6s6 5 10 5 5-6 9-6 8 5 12 5"
              fill="none"
              stroke="currentColor"
              stroke-width="1.2"
              stroke-linecap="round"
            />
            <path
              d="M30 12c3-1.6 5.6-4 8-4s4.4 1.6 6.4 3"
              fill="none"
              stroke="currentColor"
              stroke-width="1"
              stroke-linecap="round"
              opacity="0.55"
            />
          </svg>
          <p class="picks__sub">十里青山，百里画廊 —— 选一处，慢慢走</p>
        </div>

        <button class="picks__top" title="回到顶部" @click="backToTop">
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <path
              d="M12 19V5M6 11l6-6 6 6"
              fill="none"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          <span>回到顶部</span>
        </button>
      </div>

      <div class="container">
        <div v-if="loading" class="picks__grid">
          <div v-for="i in 4" :key="i" class="skeleton picks__sk" />
        </div>
        <div v-else class="picks__grid">
          <router-link
            v-for="p in showcaseScenics"
            :key="p.id"
            :to="`/poi/${p.id}`"
            class="ptile reveal"
          >
            <PoiImage :poi-id="p.id" :scene="p.scene" ratio="auto" :alt="p.name" class="ptile__art" />
            <div class="ptile__veil" />
            <div class="ptile__body">
              <h3 class="ptile__name">{{ p.name }}</h3>
              <span class="ptile__chip">{{ chipOf(p.tags, BUSINESS_LABEL[p.business_type]) }}</span>
            </div>
            <span class="ptile__more">发现更多 →</span>
          </router-link>
        </div>

        <!-- 概览数字：压在区块底部，细线分隔，不做卡片堆 -->
        <div v-if="!loading && !error" class="stats reveal">
          <div v-for="s in stats" :key="s.label" class="stat">
            <span class="num stat__num">{{ s.value }}</span>
            <span class="stat__unit">{{ s.unit }}</span>
            <span class="stat__label">{{ s.label }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- ============ 4–8. 下半页四处主栏目 ============
         智能行程规划 / 汉中乡村体验 / 汉中特色好物 / 离境复购
         统一包在一个 `.lower` 容器里：四个 section 自己**不设背景**，
         共用 `.home` 的纸感底，于是 .home__ink 的墨块能一路贯通过来。
         每个 section 各铺一层墨会看出接缝，所以墨只铺在 .home 这一层。 -->
    <div class="lower">

      <!-- ============ 5. AI 智能行程规划 ============ -->
      <section class="section-lg ai-band">
        <div class="container">
          <div class="ai__grid">
            <div class="ai__copy reveal">
              <span class="eyebrow">智能行程规划</span>
              <h2 class="h1 ai__title">让每一段汉中旅程，<br />都恰到好处</h2>
              <p class="lead ai__desc">
                告诉我出行时间、同行人数和偏好，帮你安排景点、美食、住宿与乡村体验。
                不用自己排表、不用查攻略，拿到一份能直接照着走的行程。
              </p>
              <div class="ai__cta">
                <router-link to="/agent" class="btn btn-primary btn-lg">开始规划我的行程</router-link>
                <router-link to="/explore" class="btn btn-ghost btn-lg">先逛逛汉中</router-link>
              </div>
              <!--
                /itinerary 的入口（D1）。
                ★ 主按钮**不再**指向它：那一页当前是纯前端规则演示、一次模型调用都没有，
                  而这一段的文案承诺的是"帮你安排行程"。主按钮指向真正接了 AI 的 /agent，
                  纯规则那一页降级成"自己动手排"的备用工具，收在按钮下面一行小字里。
                  不留这一行的话，/itinerary 会变成没有任何入口的孤儿页。
              -->
              <p class="ai__alt">
                想自己动手排？
                <router-link to="/itinerary" class="ai__alt-link">用行程工具按天排 →</router-link>
              </p>
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

      <!-- ============ 6. 乡村体验 ============ -->
      <section class="section-lg rural-band">
        <div class="container">
          <SectionHead
            eyebrow="汉中乡村体验"
            title="避开拥挤，把时间留给风景"
            desc="景区人多的日子，不如拐进山里。茶园、稻田、橘园、非遗工坊都在一小时车程内，人少、安静，能坐下来慢慢待上半天。"
            size="lg"
            more-text="看看乡村体验"
            more-to="/explore?type=RURAL_SPOT"
          />

          <div v-if="loading" class="grid grid-3">
            <div v-for="i in 3" :key="i" class="skeleton" style="height: 300px; border-radius: 10px" />
          </div>

          <div v-else class="rural__grid">
            <router-link
              v-for="(p, i) in rurals.slice(0, 3)"
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
            这些村子大多不在热门榜单上，却都离景区不远。赶上人多的时候来这里，反而更自在。
          </p>
        </div>
      </section>

      <!-- ============ 7. 汉中特色好物 ============ -->
      <section class="section-lg goods">
        <div class="container">
          <SectionHead
            eyebrow="汉中特色好物"
            title="把汉中的味道，带回家"
            desc="汉中仙毫、洋县黑米、镇巴腊肉、略阳乌鸡——都来自你走过的那片山。由村里的合作社和农户做出来，带回家就能接着吃。"
            size="lg"
            more-text="看全部好物"
            more-to="/goods"
          />

          <div v-if="loading" class="grid grid-4">
            <div v-for="i in 4" :key="i" class="skeleton" style="height: 320px; border-radius: 10px" />
          </div>

          <div v-else class="goods__grid">
            <article v-for="g in goods" :key="g.id" class="gcard reveal">
              <div class="gcard__art-wrap">
                <!-- 产品没有自己的图片，用产地乡村点的实拍图 ——
                     标题说的"你走过的那片山"，这里正是要显示那片山 -->
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
        </div>
      </section>

      <!-- ============ 8. 离境复购 ============ -->
      <section class="section-lg value-band">
        <div class="container">
          <SectionHead
            eyebrow="离境复购"
            title="回家之后，汉中的味道还在"
            desc="旅程会结束，味觉记得住。离开汉中以后，随时可以循着买过的那一款再下一单，从同一片产地、同一家作坊寄到家。"
            size="lg"
            align="center"
          />

          <!-- 旅程线：说的是游客自己走过的四步，不是系统的链路 -->
          <div class="trace reveal">
            <span class="trace__step">到访汉中</span>
            <span class="trace__line" />
            <span class="trace__step">走进村子</span>
            <span class="trace__line" />
            <span class="trace__step">带走好物</span>
            <span class="trace__line" />
            <span class="trace__step trace__step--end">回家复购</span>
          </div>

          <div class="pillars">
            <article v-for="p in repurchase" :key="p.no" class="pillar reveal">
              <span class="num pillar__no">{{ p.no }}</span>
              <h3 class="h3 pillar__title">{{ p.title }}</h3>
              <p class="pillar__desc">{{ p.desc }}</p>
            </article>
          </div>

          <div class="value__cta">
            <router-link to="/goods" class="btn btn-primary btn-lg">去看看能带什么</router-link>
            <router-link to="/orders" class="btn btn-ghost btn-lg">我的订单</router-link>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ============================================================
   0. 宣纸淡墨背景层
   ------------------------------------------------------------
   整页只有**这一层**墨。它是 .home 的绝对定位子元素，从 Hero 下沿
   一直铺到页面底部，十来个墨块各自落位（位置见 .hi--*）。

   三条不能动的规则：
   1. **`.home` 是纸感底的唯一持有者**。下面 .mid / .picks / .lower
      全部 `background: transparent` —— 只要有一块不透明，墨就被挡住。
   2. **`.home__ink` 必须 `pointer-events: none`**（组件里已设，这里
      再兜一层）。它是背景，绝不能拦住点击。
   3. **内容层要抬到墨层之上**（`z-index: 1`），否则墨会盖住文字。
      这里用 `> :not(.home__ink)` 而不是逐个列 section，
      免得以后新增 section 忘了加。
   ============================================================ */
.home {
  position: relative;
  background: var(--paper);
}
.home__ink {
  position: absolute;
  top: var(--hero-h);
  left: 0;
  right: 0;
  bottom: 0;
  overflow: hidden;
  pointer-events: none;
  z-index: 0;
}
.home > :not(.home__ink) {
  position: relative;
  z-index: 1;
}

/* ---------- 墨块落位 ----------
   横向用 % 是为了跟着容器走；纵向用 % 是因为这一层很高（约 4400px），
   固定的 px 偏移在长页面上会全部挤到顶部。
   中间主内容区（大致 y 28%~58% 的中央列）刻意留空，避免压到正文。
   纵向大致按 5% / 12% / 24% / 36% / 44% / 54% / 66% / 76% / 底 分布，
   不让任何一段超过约 12% 的空白 —— 实测第一版中段 y 2500~3000 是空的，
   整页看起来"上面有点东西、中间一大片白"。 */
.hi {
  /* 默认都靠下，由各条自己覆盖 */
  bottom: 0;
}

/* 上边缘：Hero 正下方左右各一片 */
.hi--t1 {
  top: 0;
  left: -6%;
  bottom: auto;
}
.hi--t2 {
  top: 7%;
  right: 1%;
  bottom: auto;
}

/* 左侧 */
.hi--l1 {
  top: 23%;
  left: -6%;
  bottom: auto;
}
.hi--l2 {
  top: 53%;
  left: -4%;
  bottom: auto;
}

/* 右侧 */
.hi--r1 {
  top: 35%;
  right: -6%;
  bottom: auto;
}
.hi--r2 {
  top: 65%;
  right: -5%;
  bottom: auto;
}

/* 云雾：填留白，避开中央正文列 */
.hi--m1 {
  top: 14%;
  right: 3%;
  bottom: auto;
}
.hi--m2 {
  top: 44%;
  left: 1%;
  bottom: auto;
}
.hi--m3 {
  top: 76%;
  right: 2%;
  bottom: auto;
}

/* 下边缘 */
.hi--b1 {
  left: -7%;
}
.hi--b2 {
  right: 3%;
  bottom: 8%;
}
.hi--b3 {
  right: -5%;
}

/* ============================================================
   1.5 分流公告
   ------------------------------------------------------------
   上 32px：公告要贴着 Hero，离得远就不像"当前正在发生的事"。
   下不设留白：紧接的 .entries 与 .mid 自带留白。
   ============================================================ */
.dnbsec {
  padding-top: var(--sp-6);
}
.dnbsec__stack {
  display: grid;
  gap: var(--sp-3);
}
.dnbsec__hide {
  justify-self: start;
  font-size: var(--fs-cap);
  letter-spacing: 0.04em;
  color: var(--warm-500);
  padding: 2px 0;
  transition: color var(--dur-1) var(--ease);
}
.dnbsec__hide:hover {
  color: var(--brand-700);
}

/* ============================================================
   2. 五联功能入口
   ============================================================ */
.entries {
  padding-top: var(--sp-7);
}
.entries__grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--sp-4);
}
.ecard {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  height: 100%;
  padding: var(--sp-5) var(--sp-5);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
  transition: box-shadow var(--dur-2) var(--ease), transform var(--dur-2) var(--ease),
    border-color var(--dur-2) var(--ease);
}
.ecard:hover {
  transform: translateY(-3px);
  box-shadow: var(--sh-3);
  border-color: var(--brand-300);
}
/* 图标：圆形浅底 + 品牌色描边，克制、不加渐变 */
.ecard__ico {
  flex: none;
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  color: var(--brand-600);
  background: var(--brand-50);
  border-radius: var(--r-pill);
  transition: background var(--dur-2) var(--ease), color var(--dur-2) var(--ease);
}
.ecard:hover .ecard__ico {
  background: var(--brand-700);
  color: var(--gold-300);
}
.ecard__main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.ecard__label {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--ink-900);
  letter-spacing: 0.02em;
}
.ecard__n {
  font-size: var(--fs-cap);
  font-style: normal;
  color: var(--gold-600);
}
.ecard__desc {
  font-size: 11px;
  letter-spacing: 0.02em;
  color: var(--warm-500);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ecard__arrow {
  margin-left: auto;
  flex: none;
  color: var(--warm-400);
  transition: transform var(--dur-2) var(--ease), color var(--dur-2) var(--ease);
}
.ecard:hover .ecard__arrow {
  transform: translateX(4px);
  color: var(--gold-500);
}

/* ============================================================
   3. 中部三栏
   ------------------------------------------------------------
   参考稿的比例是"左大图 ≈ 2 份、中列表 ≈ 1.4 份、右对话 ≈ 1.1 份"。
   这里照这个比例给列宽，三栏顶部对齐、各自独立成块，
   不做等宽三列 —— 等宽会让三块读起来像三个平行指标卡，
   而参考稿要的是"一张主图 + 两条辅助"的从属关系。
   ============================================================ */
.mid {
  /* 透明：纸感底与水墨都由 .home / .home__ink 统一提供。
     这里只要有一点不透明，背景的墨就被整段挡住。 */
  background: transparent;
}
.mid__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.72fr) minmax(0, 1.16fr) minmax(0, 1.02fr);
  gap: var(--sp-5);
  align-items: stretch;
}
.mid__sk {
  display: grid;
  grid-template-columns: minmax(0, 1.72fr) minmax(0, 1.16fr) minmax(0, 1.02fr);
  gap: var(--sp-5);
}
.mid__sk-feat {
  height: 480px;
}
.mid__sk-col {
  height: 480px;
}

/* ---------- 3a. 推荐景点大图卡 ---------- */
.mid__featwrap {
  min-height: 480px;
}
.ftile {
  position: relative;
  display: flex;
  align-items: flex-end;
  height: 100%;
  min-height: 480px;
  overflow: hidden;
  border-radius: var(--r-lg);
  box-shadow: var(--sh-2);
}
.ftile__art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
  transition: transform 900ms var(--ease);
}
.ftile:hover .ftile__art {
  transform: scale(1.03);
}
.ftile__veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    180deg,
    rgba(11, 33, 25, 0.16) 0%,
    rgba(11, 33, 25, 0.28) 44%,
    rgba(11, 33, 25, 0.88) 100%
  );
}
/* 「推荐景点」角标压左上 */
.ftile__badge {
  position: absolute;
  top: var(--sp-5);
  left: var(--sp-5);
  height: 26px;
  display: inline-flex;
  align-items: center;
  padding: 0 12px;
  font-size: var(--fs-cap);
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--gold-300);
  background: rgba(11, 33, 25, 0.5);
  border: 1px solid rgba(226, 202, 145, 0.5);
  border-radius: var(--r-sm);
  backdrop-filter: blur(4px);
}
.ftile__body {
  position: relative;
  width: 100%;
  padding: var(--sp-7) var(--sp-7) var(--sp-6);
  color: #fff;
}
.ftile__title {
  font-size: 34px;
  color: #fff;
  text-shadow: 0 2px 18px rgba(11, 33, 25, 0.4);
}
.ftile__meta {
  margin-top: var(--sp-3);
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  font-size: var(--fs-xs);
  color: rgba(255, 255, 255, 0.78);
}
.ftile__dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.5);
}
.ftile__desc {
  margin-top: var(--sp-4);
  /* 右下角有「1 / 3」页码，正文横向让出它的宽度，
     否则两行简介的末行会与页码叠在一起（实拍图里看过一次） */
  max-width: min(34em, calc(100% - 62px));
  font-size: var(--fs-sm);
  line-height: 1.85;
  color: rgba(255, 255, 255, 0.82);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.ftile__cta {
  margin-top: var(--sp-5);
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 var(--sp-5);
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--brand-800);
  background: #fff;
  border-radius: var(--r-md);
  transition: background var(--dur-1) var(--ease), transform var(--dur-1) var(--ease);
}
.ftile__cta em {
  font-style: normal;
  transition: transform var(--dur-2) var(--ease);
}
.ftile:hover .ftile__cta {
  background: var(--gold-300);
}
.ftile:hover .ftile__cta em {
  transform: translateX(4px);
}
/* 「1 / 3」右下：参考稿里是当前卡在组内的位置 */
.ftile__idx {
  position: absolute;
  right: var(--sp-5);
  bottom: var(--sp-6);
  font-size: var(--fs-xs);
  letter-spacing: 0.08em;
  color: rgba(255, 255, 255, 0.66);
}

/* ---------- 3b. 热门推荐列表 ---------- */
.mid__hot {
  display: flex;
  flex-direction: column;
  padding: var(--sp-5) var(--sp-5) var(--sp-4);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
}
.mid__hot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding-bottom: var(--sp-4);
  border-bottom: 1px solid var(--line-soft);
}
.mid__hot-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-body);
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--ink-900);
}
.mid__hot-ico {
  display: grid;
  place-items: center;
  color: var(--danger);
}
.mid__hot-more {
  font-size: var(--fs-cap);
  color: var(--warm-500);
  transition: color var(--dur-1) var(--ease);
}
.mid__hot-more:hover {
  color: var(--brand-700);
}
/* 列表：用上细线分隔行，不每行套一张卡 —— 与管理端那种"卡片墙"区分开 */
.hlist {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.hrow {
  display: grid;
  grid-template-columns: 62px minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--sp-4);
  height: 100%;
  padding: var(--sp-4) 0;
  border-bottom: 1px solid var(--line-soft);
  transition: padding-left var(--dur-2) var(--ease);
}
.hlist > li:last-child .hrow {
  border-bottom: none;
}
.hrow:hover {
  padding-left: 6px;
}
.hrow__art {
  width: 62px;
  border-radius: var(--r-md);
}
.hrow__main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.hrow__name {
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--ink-900);
  transition: color var(--dur-1) var(--ease);
}
.hrow:hover .hrow__name {
  color: var(--brand-700);
}
.hrow__sub {
  font-size: var(--fs-cap);
  color: var(--gold-600);
  letter-spacing: 0.02em;
}
.hrow__desc {
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-500);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.hrow__arrow {
  flex: none;
  color: var(--warm-400);
  transition: transform var(--dur-2) var(--ease), color var(--dur-2) var(--ease);
}
.hrow:hover .hrow__arrow {
  transform: translateX(4px);
  color: var(--gold-500);
}

/* ---------- 3c. AI 助手对话卡 ---------- */
.mid__ai {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  padding: var(--sp-5);
  background: linear-gradient(168deg, var(--brand-50) 0%, #fff 46%);
  border: 1px solid var(--brand-100);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
}
.aicard__head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
/* 徽标用品牌深绿实心 + 金字，与顶栏"汉"印章同一套语言 */
.aicard__mark {
  flex: none;
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  color: var(--gold-300);
  background: var(--brand-700);
  border-radius: var(--r-sm);
  box-shadow: inset 0 0 0 1px rgba(226, 202, 145, 0.4);
}
.aicard__id {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  min-width: 0;
}
.aicard__name {
  font-family: var(--font-display);
  font-size: var(--fs-body);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--brand-800);
}
.aicard__role {
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
/* 问候语做成"对话气泡"：左侧留一个小缺口指向徽标 */
.aicard__hello {
  position: relative;
  padding: var(--sp-4);
  font-size: var(--fs-xs);
  line-height: 1.8;
  color: var(--ink-600);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-md);
}
.aicard__hello::before {
  content: '';
  position: absolute;
  top: -6px;
  left: 16px;
  width: 10px;
  height: 10px;
  background: #fff;
  border-left: 1px solid var(--line-soft);
  border-top: 1px solid var(--line-soft);
  transform: rotate(45deg);
}
.aicard__asks {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
/* 快捷提问按钮：浅色描边条，hover 时整条变品牌浅底 */
.aicard__ask {
  width: 100%;
  text-align: left;
  padding: 9px var(--sp-4);
  font-size: var(--fs-xs);
  color: var(--ink-700);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  transition: all var(--dur-1) var(--ease);
}
.aicard__ask:hover {
  color: var(--brand-700);
  border-color: var(--brand-300);
  background: var(--brand-50);
  padding-left: var(--sp-5);
}
/* 底部"向我提问"：做成主色的满宽按钮，是这一栏唯一的强动作 */
.aicard__go {
  margin-top: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 42px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: #fff;
  background: var(--brand-700);
  border-radius: var(--r-md);
  box-shadow: var(--sh-brand);
  transition: background var(--dur-1) var(--ease), transform var(--dur-1) var(--ease);
}
.aicard__go em {
  font-style: normal;
  transition: transform var(--dur-2) var(--ease);
}
.aicard__go:hover {
  background: var(--brand-600);
  transform: translateY(-1px);
}
.aicard__go:hover em {
  transform: translateX(4px);
}

/* ============================================================
   4. 精选景点
   ============================================================ */
.picks {
  /* 同 .mid：透明，让 .home__ink 的墨透上来 */
  background: transparent;
}
.picks__wrap {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: var(--sp-6);
  margin-bottom: var(--sp-5);
}
.picks__lead {
  position: relative;
  max-width: 760px;
  padding-bottom: 2px;
}
.picks__eyebrow {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-cap);
  font-weight: 600;
  letter-spacing: 0.18em;
  color: var(--gold-600);
}
.picks__eyebrow::before {
  content: '';
  width: 22px;
  height: 1px;
  background: var(--gold-500);
}
.picks__title {
  position: relative;
  margin-top: 6px;
  font-family: var(--font-display);
  font-size: 38px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ink-900);
}
/* 飞鸟落在标题右上，像一枚闲笔 —— 参考稿的标题区有一只小鸟 */
.picks__bird {
  position: absolute;
  right: -18px;
  top: 6px;
  color: var(--warm-400);
  transform: rotate(-6deg);
}
.picks__sub {
  margin-top: var(--sp-3);
  font-size: var(--fs-sm);
  color: var(--ink-500);
}
/* 向上按钮：与标题同排靠右 */
.picks__top {
  margin-left: auto;
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: var(--sp-2) var(--sp-4);
  font-size: var(--fs-cap);
  letter-spacing: 0.04em;
  color: var(--ink-600);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  transition: all var(--dur-1) var(--ease);
}
.picks__top:hover {
  color: var(--brand-700);
  border-color: var(--brand-300);
  background: var(--brand-50);
}

.picks__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-4);
}
.picks__sk {
  height: 320px;
  border-radius: var(--r-md);
}
/* 卡片：模板里是"接近正方 + 图上直接排字 + 很淡的压暗"，
   不是"图上盖一块深色再把白字压上去"。
   所以这里做三件事：高度抬到 320（与列宽≈298 合成近 1:1.07）、
   圆角收到 --r-md、遮罩整体减淡并把渐变起点下移。 */
.ptile {
  position: relative;
  display: flex;
  align-items: flex-end;
  min-height: 320px;
  overflow: hidden;
  border-radius: var(--r-md);
  box-shadow: var(--sh-1);
  transition: box-shadow var(--dur-2) var(--ease), transform var(--dur-2) var(--ease);
}
.ptile:hover {
  transform: translateY(-4px);
  box-shadow: var(--sh-3);
}
.ptile__art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
  transition: transform 900ms var(--ease);
}
.ptile:hover .ptile__art {
  transform: scale(1.05);
}
/* 遮罩：只在下三分之一压暗，保证白字可读；上半留亮，
   让图能透出来（模板的卡片上半截几乎没被压过）。 */
.ptile__veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    180deg,
    rgba(11, 33, 25, 0) 0%,
    rgba(11, 33, 25, 0.04) 42%,
    rgba(11, 33, 25, 0.62) 78%,
    rgba(11, 33, 25, 0.82) 100%
  );
}
.ptile__body {
  position: relative;
  width: 100%;
  padding: var(--sp-5);
  color: #fff;
}
.ptile__name {
  font-family: var(--font-display);
  font-size: 21px;
  font-weight: 600;
  color: #fff;
  text-shadow: 0 2px 14px rgba(11, 33, 25, 0.42);
}
.ptile__chip {
  display: inline-block;
  margin-top: var(--sp-2);
  font-size: var(--fs-cap);
  color: rgba(255, 255, 255, 0.8);
}
/* 「发现更多 →」角标压右下 */
.ptile__more {
  position: absolute;
  right: var(--sp-4);
  bottom: var(--sp-4);
  font-size: var(--fs-cap);
  letter-spacing: 0.04em;
  color: rgba(255, 255, 255, 0.72);
  opacity: 0;
  transform: translateX(-6px);
  transition: opacity var(--dur-2) var(--ease), transform var(--dur-2) var(--ease);
}
.ptile:hover .ptile__more {
  opacity: 1;
  transform: none;
}

/* 概览数字：细线分隔，不套卡片 */
.stats {
  margin-top: var(--sp-6);
  padding-top: var(--sp-5);
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
   下半页四栏目：统一底色
   ------------------------------------------------------------
   用户口径是"智能行程规划 / 汉中乡村体验 / 汉中特色好物 / 离境复购
   统一成一个背景颜色，留白不要太多，仍要有隐隐约约的水墨山"。
   所以：
   - 四个 section 自己**不设任何背景**，全部透明 —— 底色与水墨都由
     `.home` / `.home__ink` 统一提供（`.home__ink` 是 `.lower` 的**兄弟**
     节点，不是子节点；`.lower` 一旦不透明就会把墨整段挡住）；
   - 段间距从 96 收到 56（见下），消掉"一处一个空档"。
   ============================================================ */
.lower {
  position: relative;
}
/* 段间距：全局 .section-lg 是 96px，四段连排下来相邻两段之间就是 192px ——
   在"统一底色、不要太多留白"的前提下太松。收到 56px（相邻合成 112px），
   模块边界靠 SectionHead 的段距与卡片自己撑，不靠空白撑。
   只在本页覆盖，不动全局 .section-lg（Explore/Goods 还在用 96px）。 */
.lower .section-lg {
  padding: 56px 0;
}

/* ============================================================
   5. AI 智能行程规划
   ============================================================ */
.ai-band {
  background: transparent;
}
.ai__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: var(--sp-8);
  align-items: center;
}
.ai__title {
  margin-top: var(--sp-4);
  color: var(--ink-900);
}
.ai__desc {
  margin-top: var(--sp-4);
  max-width: 34em;
}
.ai__cta {
  margin-top: var(--sp-6);
  display: flex;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
/* /itinerary 的次级入口（D1）。刻意做成一行小字而不是第三个按钮 ——
   它指向的是"纯规则、无模型调用"的备用工具，不该和上面两个主行动平起平坐。 */
.ai__alt {
  margin-top: var(--sp-4);
  font-size: 13px;
  color: var(--ink-500);
}
.ai__alt-link {
  color: var(--brand-700);
  border-bottom: 1px solid transparent;
  transition: border-color var(--dur-1) var(--ease);
}
.ai__alt-link:hover {
  border-bottom-color: currentColor;
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
   6. 乡村体验
   ------------------------------------------------------------
   原来是深绿整幅带（--brand-800）。用户要求四个栏目同底色，
   所以它改成与整页一致的纸感底；区分交给 SectionHead 的标题
   与卡片本身，不再靠"翻成深色"。
   ============================================================ */
.rural-band {
  background: transparent;
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
  /* 这一块原来在深绿带上，遮罩可以很重（底色本来就是深的，压不暗）。
     改成纸感底之后，重遮罩会让卡片读成"一块黑"、与周围的亮底打架。
     收成"下三分之一压暗"，与 .ptile 用同一套口径。 */
  background: linear-gradient(
    180deg,
    rgba(11, 33, 25, 0.02) 0%,
    rgba(11, 33, 25, 0.1) 44%,
    rgba(11, 33, 25, 0.68) 80%,
    rgba(11, 33, 25, 0.86) 100%
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
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
  border-top: 1px solid rgba(219, 233, 227, 0.14);
  font-size: var(--fs-sm);
  color: var(--brand-300);
}

/* ============================================================
   7. 汉中特色好物
   ============================================================ */
.goods {
  background: transparent;
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

/* ============================================================
   8. 离境复购
   ============================================================ */
.value-band {
  background: transparent;
}
.trace {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  padding: var(--sp-5) var(--sp-6);
  background: var(--paper-2);
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  margin-bottom: var(--sp-5);
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
.value__cta {
  margin-top: var(--sp-6);
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
   ------------------------------------------------------------
   1440：三栏开始变挤 → 中部改成"大图 + 右侧两栏"两行布局。
   1080：三栏/四栏全部落成单列或两列。
   720 ：全部单列，Hero 与整幅带同步收紧。
   ============================================================ */
@media (max-width: 1440px) {
  .mid__grid,
  .mid__sk {
    grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  }
  /* 第三栏（AI 卡）掉到第二行，占满整行并横向铺开 */
  .mid__ai {
    grid-column: 1 / -1;
    flex-direction: row;
    align-items: center;
    gap: var(--sp-6);
  }
  .mid__ai .aicard__head,
  .mid__ai .aicard__hello {
    flex: none;
    width: 240px;
  }
  .mid__ai .aicard__asks {
    flex: 1;
    flex-direction: row;
    gap: var(--sp-3);
  }
  .mid__ai .aicard__go {
    flex: none;
    width: 180px;
  }
}

@media (max-width: 1080px) {
  .entries__grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .mid__grid,
  .mid__sk {
    grid-template-columns: minmax(0, 1fr);
  }
  .mid__ai {
    flex-direction: column;
    align-items: stretch;
    gap: var(--sp-4);
  }
  .mid__ai .aicard__head,
  .mid__ai .aicard__hello,
  .mid__ai .aicard__go {
    width: 100%;
  }
  .mid__ai .aicard__asks {
    flex-direction: column;
  }
  .ftile,
  .mid__featwrap {
    min-height: 400px;
  }
  .picks__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
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
  /* 四栏目段间距同步收紧（桌面 56 → 手机 40）；
     原来这里给深色带单独收到 48px 的规则已随"统一底色"一起作废。 */
  .lower .section-lg {
    padding: 40px 0;
  }
  .entries {
    padding-top: var(--sp-5);
  }
  .entries__grid {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--sp-3);
  }
  .ecard {
    padding: var(--sp-4) var(--sp-4);
  }
  .ecard__desc {
    white-space: normal;
  }
  .ftile,
  .mid__featwrap {
    min-height: 340px;
  }
  .ftile__body {
    padding: var(--sp-5);
  }
  .ftile__title {
    font-size: 26px;
  }
  .ftile__idx {
    right: var(--sp-5);
    bottom: var(--sp-5);
  }
  .picks__wrap {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--sp-4);
  }
  .picks__title {
    font-size: 26px;
  }
  .picks__bird {
    display: none;
  }
  .picks__top {
    margin-left: 0;
  }
  .picks__grid,
  .rural__grid,
  .goods__grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .rcard--lead {
    grid-column: span 1;
    min-height: 340px;
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
  .trace {
    padding: var(--sp-4);
  }
  .value__cta .btn {
    flex: 1 1 auto;
  }
}
</style>
