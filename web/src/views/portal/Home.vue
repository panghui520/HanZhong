<script setup lang="ts">
/**
 * Home —— 首页
 *
 * ★ 定位：**面向游客的汉中文旅首页**，不是项目演示页、也不是技术介绍页。
 *   所以这里只出现"游客能获得什么"，不出现"系统是怎么实现的"。
 *   实现说明（承载余量、规则引擎、大模型边界、业务闭环、各服务的就绪状态）
 *   一律留在管理端与答辩材料里 —— 那些词出现在首页就是演示页的味道。
 *
 * 节奏（自上而下，靠"尺寸 + 留白 + 字号层级"形成落差，而不是每区放同样大小的卡片）：
 *   1.  超大轮播 Hero（满屏，深色压图）
 *   1.5 今日游览提示（M5 续，有生效公告时才出现）
 *   2.  汉中精选目的地（大图主推 + 次级列表，左右不对称）
 *   3.  智能行程规划（浅色强调带，横向四步）
 *   4.  乡村体验（深绿整幅带）—— 讲"避开拥挤"，不讲"分流"
 *   5.  汉中特色好物（把味道带回家）
 *   6.  离境复购（三栏，收束）
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

/**
 * 底部三个数字。**标签要说游客的话**：
 * 原来写的是"文旅资源点"（行业口径），游客不会这么叫自己想去的地方。
 */
const stats = computed(() => [
  { label: '汉中好去处', value: data.value?.pois.length ?? 0, unit: '处' },
  { label: '乡村体验', value: experiences.value.length, unit: '项' },
  { label: '乡村好物', value: products.value.length, unit: '款' },
])

/**
 * 传给 Hero 的三个数字。
 * 数据没到之前给**空数组**（Hero 里 `v-if="stats.length"` 整行不渲染），
 * 而不是给 0 —— 否则首屏会先闪一下"0 处 / 0 项 / 0 款"再跳成真实值。
 */
const heroStats = computed(() => (data.value ? stats.value : []))

/**
 * AI 行程规划的四步。
 *
 * ★ 这一栏原来是给评委看的实现说明（"召回候选 / 承载过滤 / LLM 只负责写成可读方案"），
 *   游客读不懂也不需要懂。改成**"你会得到什么"**：
 *   每一步的主语都是"你"，描述的是游客拿到的东西，而不是系统的内部步骤。
 *   （实现细节留给答辩材料，不进游客首页。）
 */
const flow = [
  { no: '01', title: '说说你的行程', desc: '天数、同行的人、想走多快、偏爱什么' },
  { no: '02', title: '挑出合适的地方', desc: '景点、美食、住宿、乡村体验，按偏好来选' },
  { no: '03', title: '避开人多的时段', desc: '结合客流，把热门点位排到更从容的时候' },
  { no: '04', title: '给你能照着走的方案', desc: '每天的动线、停留时长与推荐理由' },
]

/**
 * 离境复购三栏。
 *
 * ★ 替换掉原来的"智慧文旅平台价值"三栏（承载失衡靠分流/体验是消费入口/一次到访延伸成消费链）。
 *   那三栏讲的是**系统的设计主张**，是答辩语言；这里讲的是**游客回家之后能得到什么**。
 *   事实依据都来自数据包：每款产品都有 origin_village 与所属体验，可核对。
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
    <HeroCarousel :stats="heroStats" />

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
          desc="从秦岭深处的云海，到汉江两岸的古镇与栈道。挑出最值得先去的几处，帮你把汉中一次看够。"
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
          <div class="empty__title">暂时没有可推荐的目的地</div>
          <!-- ★ 面向游客：这里原来写"请确认后端服务已启动，且城市数据包已导入"——
               那是开发自检话术。游客看不懂，也不该看到。真正的接口报错走上一个分支。 -->
          <div class="empty__desc">内容可能正在更新，稍后再来看看，或先去「探索汉中」翻一翻。</div>
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

    <!-- ============ 3. AI 智能行程规划 ============
         ★ 这一块原来讲的是实现（承载余量 / 规则判定 / 大模型各守边界），
           是写给评委看的。现在整块改成"游客能得到什么"：
           标题说的是体验，四步说的是游客拿到的东西。 -->
    <section class="section-xl ai-band">
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
              <router-link to="/itinerary" class="btn btn-primary btn-lg">开始规划我的行程</router-link>
              <router-link to="/assistant" class="btn btn-ghost btn-lg">先问问有什么好玩的</router-link>
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

    <!-- ============ 4. 乡村体验（深绿整幅带） ============
         ★ 原来这块讲的是"分流"（把溢出的客流送进村子 / 承载吃紧 / 车程 30–60 分钟匹配），
           是系统的调度逻辑。现在换成游客视角：**避开拥挤**，说的是游客得到的从容。
           分流的机制留在答辩材料里，首页不出现"承载""溢出""调度"这些词。 -->
    <section class="section-xl rural-band">
      <div class="container">
        <SectionHead
          eyebrow="汉中乡村体验"
          title="避开拥挤，把时间留给风景"
          desc="景区人多的日子，不如拐进山里。茶园、稻田、橘园、非遗工坊都在一小时车程内，人少、安静，能坐下来慢慢待上半天。"
          size="xl"
          tone="light"
          more-text="看看乡村体验"
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
          这些村子大多不在热门榜单上，却都离景区不远。赶上人多的时候来这里，反而更自在。
        </p>
      </div>
    </section>

    <!-- ============ 5. 汉中特色好物 ============
         ★ 三处改动：
           ① 标题按用户要求突出"把汉中的味道带回家"（原来偏文艺的"你走过的那片山"
              移到正文里保留）；
           ② 原来开头的"溯源条"（到访汉中→乡村体验→带走好物→离境复购）挪到下一块
              「离境复购」去 —— 它讲的是整段旅程，放在"复购"那块才顺，也让好物区
              更聚焦在"有什么、多少钱"；
           ③ 删掉页尾那句"产品不设独立商城入口…这是刻意的设计取舍"（开发说明）。
           另外把 more-to 从 /assistant 改成 /goods —— "了解更多"该去好物页，
           原来指到 AI 问答是错的。 -->
    <section class="section-xl goods">
      <div class="container">
        <SectionHead
          eyebrow="汉中特色好物"
          title="把汉中的味道，带回家"
          desc="汉中仙毫、洋县黑米、镇巴腊肉、略阳乌鸡——都来自你走过的那片山。由村里的合作社和农户做出来，带回家就能接着吃。"
          size="xl"
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

    <!-- ============ 6. 离境复购 ============
         ★ 这一块原来是「智慧文旅平台价值」，讲的是系统的设计主张
           （"传统智慧文旅止步于…汉游智脑把链条向后延伸…"），
           底下还挂着一条七步"业务闭环"流程（游客需求→AI 规划→乡村引流→…→AI 运营归因）。
           这两样都是答辩材料，游客既读不懂也不关心，全部删除。
           整块改成游客视角的「离境复购」：回家之后还能买到什么、怎么买。

         ★ 顺带两件事：
           ① 好物区那条溯源条挪到这里 —— 它讲的是整段旅程，收在"复购"这块才顺；
           ② 末步"离境复购"改成"回家复购"。"离境"是行业口径，游客嘴里说的是"回家"。
           ③ 删掉"运营管理入口"按钮：Footer 的「运营方」栏已经有管理端入口了，
              首页不该再挂一个面向评委的按钮（这正是"演示页"的痕迹）。 -->
    <section class="section-xl value-band">
      <div class="container">
        <SectionHead
          eyebrow="离境复购"
          title="回家之后，汉中的味道还在"
          desc="旅程会结束，味觉记得住。离开汉中以后，随时可以循着买过的那一款再下一单，从同一片产地、同一家作坊寄到家。"
          size="xl"
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
  /* 整幅深色带比普通区块多给一点上下留白（88 vs 72）：
     色块本身已经是一次强分隔，内容再贴边会显得局促。 */
  padding: 88px 0;
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
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
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

/* ============================================================
   6. 离境复购（原「平台价值」）
   ============================================================ */
.value-band {
  background: var(--paper-3);
  padding: 88px 0;
  /* goods 是 --paper(#faf8f3)、这里是 --paper-3(#ece8de)，两者色差很小，
     只靠背景分不出模块边界 —— 补一条极细分割线把边界落实。
     这正是"不要单纯依靠巨大留白来区分 section"的落点。 */
  border-top: 1px solid var(--line);
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

/* 「业务闭环」七步流程的样式已随该模块一起删除（游客不需要看系统链路）。
   它的位置改由上面的 .trace 旅程线承担 —— 那是游客自己走过的四步。 */

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
  /* 整幅带在桌面端给 88px（比普通区块多，因为色块本身就是分隔），
     但手机上要跟着一起收到 48px —— 不然全页最"空"的反而是这两块深色带。
     Home.vue 的 scoped 样式在 base.css 之后加载，所以必须在这里显式覆盖，
     只靠 base.css 里的 .section-xl 规则压不住。 */
  .rural-band,
  .value-band {
    padding: var(--sp-7) 0;
  }
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
  .value__cta .btn {
    flex: 1 1 auto;
  }
}
</style>
