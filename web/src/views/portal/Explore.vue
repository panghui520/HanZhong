<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import SceneArt from '@/components/SceneArt.vue'
import PoiImage from '@/components/PoiImage.vue'
import SectionHead from '@/components/SectionHead.vue'
import { getCityPack } from '@/api/citypack'
import { isEmpty, useAsync } from '@/composables/useAsync'
import { useReveal } from '@/composables/useReveal'
import { BUSINESS_LABEL, BUSINESS_ORDER, type BusinessType, type Poi } from '@/types'
import { capacityUsage } from '@/mock/stats'

const { data, loading, error, reload } = useAsync(getCityPack)

const root = ref<HTMLElement | null>(null)

/* ============================================================
 * 主题分类：把 5 个「业态」（业务口径）重新组织成 3 个「主题」
 * （游客口径）。
 *
 * 为什么要多这一层：业态是系统调度用的分类，游客不按"业态"想事情 ——
 * 他想的是"我要看山水 / 看人文 / 去乡村"。而 `business_type` 里
 * 只有 SCENIC / RURAL_SPOT / FOOD / LODGING / TRANSPORT 五种，
 * 直接铺出来是"资源台账"，不是"目的地探索"。
 *
 * 主题的判定顺序有讲究：
 *   - RURAL_SPOT 优先归「乡村」—— 它是本项目的主张所在（把客流导向乡村），
 *     不能被"山水"吸走（不少乡村示范点本身也在秦岭里）。
 *     数据实测 12 条 RURAL_SPOT 全部落进乡村主题。
 *   - 餐饮/住宿/交通不单独成主题（它们不是"目的地"，是配套），
 *     而是按 `scene` 归到山水或人文主题里，充当"顺路可去"的补充。
 * ============================================================ */

/** 三个主题的键。声明在 themeOf 之前，下面的函数签名要用到。 */
type ThemeKey = 'landscape' | 'culture' | 'rural'

/**
 * 一条 POI 属于哪个主题。`scene` 作为辅助信号。
 *
 * 注意 `scene` 在类型上是可选字段（`string | undefined`），
 * 所以判断的是"是否属于自然场景集合"，而不是 `includes(undefined)`。
 */
function themeOf(p: Poi): ThemeKey {
  // 乡村优先，理由见上方注释：这是项目主张所在，不能被"山水"吸走
  if (p.business_type === 'RURAL_SPOT') return 'rural'
  // terrace/rapeseed 是山野田园，river/hanjiang 是水，都算"山水"；
  // qinling 是秦岭云海；ancient 是古建街巷 → 人文
  const s = p.scene ?? ''
  const isNature = s === 'qinling' || s === 'terrace' || s === 'rapeseed' || s === 'river' || s === 'hanjiang'
  return isNature ? 'landscape' : 'culture'
}

const THEMES: {
  key: ThemeKey
  label: string
  latin: string
  desc: string
  scene: 'qinling' | 'ancient' | 'terrace'
}[] = [
  {
    key: 'landscape',
    label: '山水汉中',
    latin: 'Landscape',
    desc: '秦岭云海、汉江碧水与茶园梯田。占据全境最集中的自然观光资源，也是最需要做承载力分流的区域。',
    scene: 'qinling',
  },
  {
    key: 'culture',
    label: '人文汉中',
    latin: 'Culture',
    desc: '两汉三国旧地。古建街巷、栈道遗迹与市井小吃，多集中在城区与盆地，适合与山水行程错峰组合。',
    scene: 'ancient',
  },
  {
    key: 'rural',
    label: '乡野汉中',
    latin: 'Countryside',
    desc: '省级乡村旅游示范村、非遗工坊与有机农业基地。它们离热门景区不远，是分流承接的主力。',
    scene: 'terrace',
  },
]

const activeTheme = ref<ThemeKey>('landscape')
const activeType = ref<BusinessType | 'ALL'>('ALL')
const keyword = ref('')
const district = ref<string>('')

const allPois = computed<Poi[]>(() => data.value?.pois ?? [])

/** 每个主题下有多少条 —— 数字来自真实数据，不是写死的 */
function countOf(k: ThemeKey) {
  return allPois.value.filter((p) => themeOf(p) === k).length
}

/** 当前主题下的可选业态（只显示该主题里真的有的，不列空标签） */
const tabs = computed(() => {
  const inTheme = allPois.value.filter((p) => themeOf(p) === activeTheme.value)
  const present = BUSINESS_ORDER.filter((t) => inTheme.some((p) => p.business_type === t))
  return [
    { key: 'ALL' as const, label: '全部', n: inTheme.length },
    ...present.map((t) => ({ key: t, label: BUSINESS_LABEL[t], n: inTheme.filter((p) => p.business_type === t).length })),
  ]
})

const districts = computed(() => {
  const set = new Set(
    allPois.value.filter((p) => themeOf(p) === activeTheme.value).map((p) => p.district)
  )
  return Array.from(set).sort()
})

/** 主题下的全部资源（未筛选）—— 用于取"主推"与"精选" */
const themePois = computed<Poi[]>(() => allPois.value.filter((p) => themeOf(p) === activeTheme.value))

/**
 * 主推的那一条：优先 A 级景区，其次取承载力最高的一条。
 *
 * 为什么按承载力选：这个页面要讲的主张是"承载力驱动分流"，
 * 把带 4A 头衔又最拥挤的那条放在头条位置，页面自己就在陈述问题。
 */
const featured = computed<Poi | null>(() => {
  const pool = themePois.value
  if (!pool.length) return null
  const ranked = [...pool].sort((a, b) => {
    const la = a.level === '4A' ? 1 : 0
    const lb = b.level === '4A' ? 1 : 0
    if (la !== lb) return lb - la
    return capacityUsage(b.id, b.business_type) - capacityUsage(a.id, a.business_type)
  })
  return ranked[0] ?? null
})

/** 主推之外的次级精选：3 条，尽量不同区县、不同业态，避免看起来是"同一个地方的三张图" */
const picks = computed<Poi[]>(() => {
  const used = new Set<string>()
  const out: Poi[] = []
  for (const p of themePois.value) {
    if (p.id === featured.value?.id) continue
    if (used.has(p.district) || used.has(p.business_type)) continue
    out.push(p)
    used.add(p.district)
    used.add(p.business_type)
    if (out.length === 3) break
  }
  // 不够 3 条就放宽条件补齐（数据少时不能空着）
  if (out.length < 3) {
    for (const p of themePois.value) {
      if (out.length >= 3) break
      if (p.id === featured.value?.id) continue
      if (out.some((x) => x.id === p.id)) continue
      out.push(p)
    }
  }
  return out
})

/** 筛选后的列表（含主推与精选，它们是"全部"的一部分） */
const list = computed<Poi[]>(() => {
  let arr = themePois.value
  if (activeType.value !== 'ALL') arr = arr.filter((p) => p.business_type === activeType.value)
  if (district.value) arr = arr.filter((p) => p.district === district.value)
  const kw = keyword.value.trim()
  if (kw) {
    arr = arr.filter(
      (p) => p.name.includes(kw) || p.summary.includes(kw) || p.tags.some((t) => t.includes(kw))
    )
  }
  return arr
})

/** 是否处于"有筛选条件"的状态 —— 决定要不要隐藏主推区（筛选时应该直接看结果） */
const filtered = computed(
  () => activeType.value !== 'ALL' || !!district.value || !!keyword.value.trim()
)

/** 全站统计，放在页头 */
const stats = computed(() => ({
  total: allPois.value.length,
  districts: new Set(allPois.value.map((p) => p.district)).size,
  rural: allPois.value.filter((p) => p.business_type === 'RURAL_SPOT').length,
  scenic4a: allPois.value.filter((p) => p.level === '4A').length,
}))

/**
 * 乡村好物。取前 4 款在售产品，每款都带上它挂靠的那次体验名。
 *
 * 为什么要在这页放好物：本页此前只用了 POI 数据，M2 的体验与产品一点没用上。
 * 而"带得走的汉中"恰恰是"把一次到访变成持续消费链"的落点 ——
 * 产品上的 `experience_name` 让这句话可核对：这一款对应哪次体验。
 */
const goods = computed(() => (data.value?.products ?? []).slice(0, 4))

function usage(p: Poi) {
  return capacityUsage(p.id, p.business_type)
}
function usageText(p: Poi) {
  return `${Math.round(usage(p) * 100)}%`
}
function usageLevel(p: Poi) {
  const u = usage(p)
  if (u >= 1) return 'danger'
  if (u >= 0.8) return 'warn'
  return 'ok'
}
const LOAD_LABEL: Record<string, string> = { ok: '舒适', warn: '偏忙', danger: '拥挤' }

function clearFilters() {
  activeType.value = 'ALL'
  district.value = ''
  keyword.value = ''
}

/** 切主题时清掉筛选 —— 否则会出现"新主题 + 旧区县"的空结果，看着像坏了 */
watch(activeTheme, () => {
  clearFilters()
})

// 列表重绘后要重新扫描一遍新的 .reveal 元素
useReveal(root, loading, computed(() => `${activeTheme.value}|${activeType.value}|${district.value}|${keyword.value}`))

function scrollToGrid() {
  nextTick(() => {
    document.getElementById('explore-list')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}
</script>

<template>
  <div ref="root" class="explore">
    <!-- ============ 1. 沉浸式 Banner ============ -->
    <header class="exbanner">
      <SceneArt variant="hanjiang" ratio="auto" class="exbanner__art" />
      <div class="exbanner__veil" />
      <div class="container exbanner__inner">
        <span class="eyebrow eyebrow--light">目的地探索 · 汉中</span>
        <h1 class="display exbanner__title">把一城资源<br />看成一张可以调度的网络</h1>
        <p class="exbanner__desc">
          景区、乡村、餐饮、住宿与交通不是各自独立的模块，而是同一张文旅资源网络上的节点。
          每一条都带着容量上限与实时占用率 —— 这正是"把客流从拥挤处导向有余处"的依据。
        </p>

        <dl v-if="!loading" class="exstats">
          <div class="exstats__i">
            <dt>统一资源</dt>
            <dd class="num">{{ stats.total }}</dd>
          </div>
          <div class="exstats__i">
            <dt>覆盖区县</dt>
            <dd class="num">{{ stats.districts }}</dd>
          </div>
          <div class="exstats__i">
            <dt>乡村承接点</dt>
            <dd class="num">{{ stats.rural }}</dd>
          </div>
          <div class="exstats__i">
            <dt>4A 级景区</dt>
            <dd class="num">{{ stats.scenic4a }}</dd>
          </div>
        </dl>
      </div>
    </header>

    <!-- ============ 2. 空 / 错误 / 加载 ============ -->
    <div v-if="loading" class="container section">
      <div class="skeleton" style="height: 420px; border-radius: 10px" />
      <div class="grid grid-3" style="margin-top: 24px">
        <div v-for="i in 3" :key="i" class="skeleton" style="height: 180px; border-radius: 10px" />
      </div>
    </div>

    <div v-else-if="error" class="container section">
      <div class="state-error">
        <p>{{ error }}</p>
        <button class="btn btn-ghost btn-sm" @click="reload">重新加载</button>
      </div>
    </div>

    <template v-else>
      <!-- ============ 3. 主题带（山水 / 人文 / 乡村） ============ -->
      <section class="container section">
        <SectionHead
          eyebrow="怎么逛"
          title="先选一种走法，再看具体去处"
          desc="按游客的直觉分主题，而不是按系统内部的业态清单。每个主题下的资源由统一网络实时供给。"
        />
        <div class="themes">
          <button
            v-for="t in THEMES"
            :key="t.key"
            class="theme reveal"
            :class="{ 'theme--on': activeTheme === t.key }"
            @click="activeTheme = t.key; scrollToGrid()"
          >
            <div class="theme__art">
              <SceneArt :variant="t.scene" ratio="16 / 9" class="theme__scene" />
              <div class="theme__scrim" />
              <span class="theme__latin">{{ t.latin }}</span>
              <span class="theme__n num">{{ countOf(t.key) }}<i>处</i></span>
            </div>
            <div class="theme__body">
              <h3 class="theme__title">{{ t.label }}</h3>
              <p class="theme__desc">{{ t.desc }}</p>
              <span class="theme__go">
                {{ activeTheme === t.key ? '正在浏览' : '进入主题' }}
                <i class="theme__arrow">→</i>
              </span>
            </div>
            <span class="theme__rule" />
          </button>
        </div>
      </section>

      <!-- ============ 4. 主题主推 + 精选（非对称：1.32fr : 1fr） ============ -->
      <section v-if="featured && !filtered" class="container section-0">
        <SectionHead
          eyebrow="本期主推"
          :title="activeTheme === 'landscape' ? '最该错峰去的那一处' : activeTheme === 'culture' ? '老城里最值得慢下来的一段' : '离景区最近、最能承接客流的一处'"
          desc="主推依据是承载力与等级：把当前最拥挤或最有代表性的那一条放在这里，而不是随机取一条。"
          size="md"
        />

        <div class="feat__grid reveal">
          <!-- 左：大幅主推，文字压在图上 -->
          <router-link :to="`/poi/${featured.id}`" class="feat">
            <PoiImage
              :poi-id="featured.id"
              :scene="featured.scene"
              ratio="auto"
              :alt="featured.name"
              class="feat__art"
            />
            <div class="feat__veil" />
            <div class="feat__body">
              <div class="feat__tags">
                <span class="tag tag-gold">{{ featured.level || BUSINESS_LABEL[featured.business_type] }}</span>
                <span class="tag tag-on-dark">{{ featured.district }}</span>
              </div>
              <h3 class="feat__title">{{ featured.name }}</h3>
              <p class="feat__summary">{{ featured.summary }}</p>
              <div class="feat__foot">
                <span class="feat__load" :class="`feat__load--${usageLevel(featured)}`">
                  <i class="feat__dot" />当前承载 {{ usageText(featured) }} · {{ LOAD_LABEL[usageLevel(featured)] }}
                </span>
                <span class="feat__more">查看详情 →</span>
              </div>
            </div>
          </router-link>

          <!-- 右：3 条次级精选，横向小卡 -->
          <div class="picks">
            <router-link v-for="p in picks" :key="p.id" :to="`/poi/${p.id}`" class="pick">
              <div class="pick__art">
                <PoiImage :poi-id="p.id" :scene="p.scene" ratio="1 / 1" :alt="p.name" class="pick__scene" />
              </div>
              <div class="pick__body">
                <div class="row pick__meta">
                  <span class="tag">{{ p.district }}</span>
                  <span class="muted cap">{{ BUSINESS_LABEL[p.business_type] }}</span>
                </div>
                <h4 class="pick__title">{{ p.name }}</h4>
                <p class="pick__summary">{{ p.summary }}</p>
                <div class="pick__foot">
                  <span class="num pick__price">{{ p.ticket_price > 0 ? `¥${p.ticket_price}` : '免费' }}</span>
                  <span class="muted cap">承载 {{ usageText(p) }}</span>
                </div>
              </div>
            </router-link>
          </div>
        </div>
      </section>

      <!-- ============ 5. 资源列表（筛选 + 网格） ============ -->
      <section id="explore-list" class="container section">
        <SectionHead
          eyebrow="资源清单"
          title="每一处都可以进同一张调度表"
          desc="按业态与区县继续收窄。列表顺序来自数据包编码顺序，不代表推荐排序。"
          size="md"
        />

        <div class="toolbar">
          <div class="tabs">
            <button
              v-for="t in tabs"
              :key="t.key"
              class="tabs__item"
              :class="{ 'tabs__item--on': activeType === t.key }"
              @click="activeType = t.key as any"
            >
              {{ t.label }}<i class="tabs__n num">{{ t.n }}</i>
            </button>
          </div>

          <div class="toolbar__right">
            <select v-model="district" class="select">
              <option value="">全部区县</option>
              <option v-for="d in districts" :key="d" :value="d">{{ d }}</option>
            </select>
            <input v-model="keyword" class="input" type="search" placeholder="搜索名称 / 特色" />
          </div>
        </div>

        <div v-if="isEmpty(list)" class="empty">
          <div class="empty__title">没有符合条件的资源</div>
          <div class="empty__desc">换个关键词，或清掉筛选条件再试</div>
          <button class="btn btn-ghost btn-sm" style="margin-top: 16px" @click="clearFilters">
            清空筛选
          </button>
        </div>

        <template v-else>
          <div class="result-count">
            <span class="num result-count__num">{{ list.length }}</span>
            <span class="muted small">处资源 · 按承载力与距离统一调度</span>
          </div>
          <div class="grid grid-3">
            <router-link
              v-for="(p, i) in list"
              :key="p.id"
              :to="`/poi/${p.id}`"
              class="pcard reveal"
              :style="{ transitionDelay: `${Math.min(i, 6) * 40}ms` }"
            >
              <div class="pcard__art-wrap">
                <PoiImage
                  :poi-id="p.id"
                  :scene="p.scene"
                  ratio="16 / 10"
                  :alt="p.name"
                  class="pcard__art"
                />
                <span class="pcard__kind">{{ BUSINESS_LABEL[p.business_type] }}</span>
                <span class="pcard__load" :class="`pcard__load--${usageLevel(p)}`">
                  {{ LOAD_LABEL[usageLevel(p)] }}
                </span>
              </div>
              <div class="pcard__body">
                <div class="row pcard__meta">
                  <span class="tag">{{ p.district }}</span>
                  <span v-if="p.level" class="tag tag-gold">{{ p.level }}</span>
                </div>
                <h3 class="pcard__title">{{ p.name }}</h3>
                <p class="pcard__summary">{{ p.summary }}</p>

                <div class="load">
                  <div class="load__top">
                    <span class="muted small">当前承载</span>
                    <span class="num load__val" :class="`load__val--${usageLevel(p)}`">
                      {{ usageText(p) }}
                    </span>
                  </div>
                  <div class="load__bar">
                    <i
                      :class="`load__fill load__fill--${usageLevel(p)}`"
                      :style="{ width: Math.min(usage(p), 1) * 100 + '%' }"
                    />
                  </div>
                </div>

                <div class="pcard__foot">
                  <span class="num pcard__price">
                    {{ p.ticket_price > 0 ? `¥${p.ticket_price}` : '免费' }}
                  </span>
                  <span class="muted small">建议 {{ p.duration_min }} 分钟</span>
                </div>
              </div>
            </router-link>
          </div>
        </template>
      </section>

      <!-- ============ 6. 乡村好物（M2 数据） ============ -->
      <section v-if="goods.length" class="container section">
        <SectionHead
          eyebrow="离境之后"
          title="体验过的，可以带走"
          desc="每一款都挂着一次具体的乡村体验 —— 先认下那片产地，再谈复购。这也是消费链从景区延伸到乡村的落点。"
          size="md"
        />
        <div class="goods">
          <router-link
            v-for="(g, i) in goods"
            :key="g.id"
            :to="g.poi_id ? `/poi/${g.poi_id}` : '/explore'"
            class="good reveal"
            :style="{ transitionDelay: `${i * 60}ms` }"
          >
            <div class="good__top">
              <span class="tag tag-gold">{{ g.category }}</span>
              <span class="num good__price">¥{{ g.price }}</span>
            </div>
            <h3 class="good__name">{{ g.name }}</h3>
            <p class="good__spec">{{ g.spec }} · 产地 {{ g.origin_village }}</p>
            <p class="good__story">{{ g.story }}</p>
            <p v-if="g.experience_name" class="good__exp">
              <i class="good__exp-k">体验锚点</i>{{ g.experience_name }}
            </p>
          </router-link>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
/* ============ 1. Banner ============ */
.exbanner {
  position: relative;
  overflow: hidden;
  background: var(--brand-900);
  min-height: min(62vh, 560px);
  display: flex;
  align-items: center;
  padding: var(--sp-9) 0 var(--sp-8);
}
.exbanner__art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
  opacity: 0.62;
}
.exbanner__veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    100deg,
    rgba(11, 33, 25, 0.9) 0%,
    rgba(11, 33, 25, 0.76) 48%,
    rgba(11, 33, 25, 0.5) 100%
  );
}
.exbanner__inner {
  position: relative;
  max-width: 900px;
}
.exbanner__title {
  margin-top: var(--sp-4);
  color: #fff;
  font-size: var(--fs-mega);
  line-height: 1.16;
  letter-spacing: -0.01em;
}
.exbanner__desc {
  margin-top: var(--sp-5);
  color: rgba(219, 233, 227, 0.86);
  max-width: 46em;
  font-size: var(--fs-hero-sub);
  line-height: 1.8;
}

/* 页头数字条：靠"大数字 + 极小的说明字"制造层级 */
.exstats {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-7);
  margin-top: var(--sp-8);
  padding-top: var(--sp-5);
  border-top: 1px solid rgba(219, 233, 227, 0.18);
}
.exstats__i dt {
  font-size: var(--fs-cap);
  letter-spacing: 0.14em;
  color: rgba(255, 255, 255, 0.55);
}
.exstats__i dd {
  margin-top: 6px;
  font-size: 30px;
  font-weight: 600;
  color: var(--gold-300);
  line-height: 1;
}

/* ============ 3. 主题带 ============ */
.themes {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--sp-5);
}
.theme {
  position: relative;
  display: flex;
  flex-direction: column;
  text-align: left;
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  overflow: hidden;
  cursor: pointer;
  transition: transform var(--dur-2) var(--ease), box-shadow var(--dur-2) var(--ease),
    border-color var(--dur-2) var(--ease);
}
.theme:hover {
  transform: translateY(-4px);
  box-shadow: var(--sh-3);
  border-color: var(--line);
}
.theme--on {
  border-color: var(--brand-500);
  box-shadow: var(--sh-brand);
}
.theme__art {
  position: relative;
  overflow: hidden;
}
.theme__scene {
  border-radius: 0;
  transition: transform 900ms var(--ease);
}
.theme:hover .theme__scene {
  transform: scale(1.045);
}
.theme__scrim {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(11, 33, 25, 0.1) 0%, rgba(11, 33, 25, 0.55) 100%);
}
.theme__latin {
  position: absolute;
  left: var(--sp-4);
  bottom: var(--sp-3);
  font-family: var(--font-num);
  font-size: var(--fs-cap);
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.72);
}
.theme__n {
  position: absolute;
  right: var(--sp-4);
  bottom: var(--sp-3);
  font-size: 26px;
  font-weight: 600;
  color: #fff;
  line-height: 1;
}
.theme__n i {
  font-style: normal;
  font-size: var(--fs-cap);
  font-weight: 400;
  margin-left: 3px;
  color: rgba(255, 255, 255, 0.7);
}
.theme__body {
  padding: var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  flex: 1;
}
.theme__title {
  font-family: var(--font-display);
  font-size: 22px;
}
.theme--on .theme__title {
  color: var(--brand-700);
}
.theme__desc {
  font-size: var(--fs-sm);
  line-height: 1.75;
  color: var(--ink-500);
  flex: 1;
}
.theme__go {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--brand-600);
  font-weight: 500;
}
.theme__arrow {
  font-style: normal;
  transition: transform var(--dur-2) var(--ease);
}
.theme:hover .theme__arrow {
  transform: translateX(4px);
}
/* 选中的主题在底部拉一条金线，和 tabs 的选中样式呼应 */
.theme__rule {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  background: var(--gold-500);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform var(--dur-3) var(--ease);
}
.theme--on .theme__rule {
  transform: scaleX(1);
}

/* ============ 4. 主推 + 精选（刻意 1.32fr : 1fr 不对称） ============ */
.feat__grid {
  display: grid;
  grid-template-columns: 1.32fr 1fr;
  gap: var(--sp-5);
  align-items: stretch;
}

.feat {
  position: relative;
  display: block;
  overflow: hidden;
  border-radius: var(--r-lg);
  background: var(--brand-900);
  min-height: 460px;
}
.feat__art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
  transition: transform 1200ms var(--ease);
}
.feat:hover .feat__art {
  transform: scale(1.035);
}
.feat__veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    180deg,
    rgba(11, 33, 25, 0.15) 0%,
    rgba(11, 33, 25, 0.45) 46%,
    rgba(11, 33, 25, 0.92) 100%
  );
}
.feat__body {
  position: absolute;
  inset: auto 0 0 0;
  padding: var(--sp-7);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.feat__tags {
  display: flex;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.feat__title {
  font-family: var(--font-display);
  font-size: clamp(26px, 2.6vw, 38px);
  line-height: 1.24;
  color: #fff;
  max-width: 16em;
}
.feat__summary {
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.78);
  max-width: 40em;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.feat__foot {
  margin-top: var(--sp-2);
  padding-top: var(--sp-4);
  border-top: 1px solid rgba(255, 255, 255, 0.16);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.feat__load {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: var(--fs-sm);
  color: rgba(255, 255, 255, 0.86);
}
.feat__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}
.feat__load--ok {
  color: var(--brand-300);
}
.feat__load--warn {
  color: var(--gold-300);
}
.feat__load--danger {
  color: #e79c88;
}
.feat__more {
  font-size: var(--fs-sm);
  font-weight: 500;
  color: var(--gold-300);
}

.picks {
  display: grid;
  grid-template-rows: repeat(3, 1fr);
  gap: var(--sp-4);
}
.pick {
  display: grid;
  grid-template-columns: 132px 1fr;
  gap: var(--sp-4);
  align-items: center;
  padding: var(--sp-4);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  transition: border-color var(--dur-2) var(--ease), box-shadow var(--dur-2) var(--ease),
    transform var(--dur-2) var(--ease);
}
.pick:hover {
  border-color: var(--line);
  box-shadow: var(--sh-2);
  transform: translateY(-2px);
}
.pick__art {
  overflow: hidden;
  border-radius: var(--r-md);
}
.pick__scene {
  border-radius: 0;
  transition: transform 900ms var(--ease);
}
.pick:hover .pick__scene {
  transform: scale(1.06);
}
.pick__body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.pick__meta {
  gap: var(--sp-2);
}
.pick__title {
  font-family: var(--font-display);
  font-size: 17px;
  line-height: 1.4;
  color: var(--ink-900);
}
.pick:hover .pick__title {
  color: var(--brand-700);
}
.pick__summary {
  font-size: var(--fs-xs);
  line-height: 1.65;
  color: var(--ink-500);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.pick__foot {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
}
.pick__price {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--gold-600);
}

/* ============ 5. 筛选 + 列表 ============ */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
  padding-bottom: var(--sp-5);
  border-bottom: 1px solid var(--line-soft);
  margin-bottom: var(--sp-6);
}
.tabs {
  display: flex;
  gap: var(--sp-1);
  flex-wrap: wrap;
}
.tabs__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--ink-500);
  border: 1px solid transparent;
  border-radius: var(--r-pill);
  transition: all var(--dur-1) var(--ease);
}
.tabs__item:hover {
  color: var(--brand-700);
  background: var(--brand-50);
}
.tabs__item--on {
  color: var(--brand-800);
  font-weight: 600;
  background: var(--brand-50);
  border-color: var(--brand-100);
}
.tabs__n {
  font-style: normal;
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.tabs__item--on .tabs__n {
  color: var(--brand-500);
}

.toolbar__right {
  display: flex;
  gap: var(--sp-3);
}
.select,
.input {
  height: 38px;
  padding: 0 var(--sp-3);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: #fff;
  font-size: var(--fs-sm);
  color: var(--ink-700);
  transition: border-color var(--dur-1) var(--ease), box-shadow var(--dur-1) var(--ease);
}
.select:focus,
.input:focus {
  outline: none;
  border-color: var(--brand-500);
  box-shadow: 0 0 0 3px var(--brand-50);
}
.input {
  width: 200px;
}

.result-count {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  margin-bottom: var(--sp-5);
}
.result-count__num {
  font-size: 26px;
  font-weight: 600;
  color: var(--brand-700);
}

.pcard {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
  transition: box-shadow var(--dur-2) var(--ease), transform var(--dur-2) var(--ease),
    border-color var(--dur-2) var(--ease);
}
.pcard:hover {
  box-shadow: var(--sh-3);
  transform: translateY(-4px);
  border-color: var(--line);
}
.pcard__art-wrap {
  position: relative;
  overflow: hidden;
}
.pcard__art {
  border-radius: 0;
  transition: transform 900ms var(--ease);
}
.pcard:hover .pcard__art {
  transform: scale(1.045);
}
.pcard__kind {
  position: absolute;
  left: var(--sp-4);
  top: var(--sp-4);
  padding: 4px 10px;
  font-size: var(--fs-cap);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: #fff;
  background: rgba(11, 33, 25, 0.62);
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: var(--r-sm);
  backdrop-filter: blur(3px);
}
/* 右上角承载状态：和左下角业态角标形成对角，不挤在一起 */
.pcard__load {
  position: absolute;
  right: var(--sp-4);
  top: var(--sp-4);
  padding: 4px 10px;
  font-size: var(--fs-cap);
  font-weight: 600;
  border-radius: var(--r-sm);
  backdrop-filter: blur(3px);
}
.pcard__load--ok {
  color: #eaf6f1;
  background: rgba(29, 85, 68, 0.72);
  border: 1px solid rgba(255, 255, 255, 0.24);
}
.pcard__load--warn {
  color: #fdf5e4;
  background: rgba(168, 121, 29, 0.78);
  border: 1px solid rgba(255, 255, 255, 0.24);
}
.pcard__load--danger {
  color: #fdeeea;
  background: rgba(168, 64, 43, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.26);
}
.pcard__body {
  padding: var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  flex: 1;
}
.pcard__meta {
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.pcard__title {
  font-family: var(--font-display);
  font-size: 20px;
  transition: color var(--dur-1) var(--ease);
}
.pcard:hover .pcard__title {
  color: var(--brand-700);
}
.pcard__summary {
  font-size: var(--fs-sm);
  color: var(--ink-500);
  line-height: 1.75;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.pcard__foot {
  margin-top: auto;
  padding-top: var(--sp-4);
  border-top: 1px solid var(--line-soft);
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.pcard__price {
  font-size: 19px;
  font-weight: 600;
  color: var(--gold-600);
}

.load {
  margin-top: var(--sp-2);
}
.load__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.load__val {
  font-size: var(--fs-sm);
  font-weight: 700;
}
.load__val--ok {
  color: var(--ok);
}
.load__val--warn {
  color: var(--warn);
}
.load__val--danger {
  color: var(--danger);
}
.load__bar {
  margin-top: 5px;
  height: 4px;
  background: var(--paper-3);
  border-radius: var(--r-pill);
  overflow: hidden;
}
.load__fill {
  display: block;
  height: 100%;
  border-radius: var(--r-pill);
  transition: width var(--dur-3) var(--ease);
}
.load__fill--ok {
  background: var(--brand-500);
}
.load__fill--warn {
  background: var(--warn);
}
.load__fill--danger {
  background: var(--danger);
}

/* 深色底上的描边 tag */
.tag-on-dark {
  color: rgba(255, 255, 255, 0.9);
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.28);
}

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

/* ============ 6. 乡村好物 ============ */
.goods {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--sp-6);
}
/* 用顶边金线代替卡片背景：和上面那一片 pcard 网格区分开，
   避免整页变成"卡片墙"（首页的 gcard 也是同一手法） */
.good {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding-top: var(--sp-5);
  border-top: 2px solid var(--gold-500);
  transition: border-color var(--dur-2) var(--ease);
}
.good:hover {
  border-color: var(--brand-600);
}
.good__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
}
.good__price {
  font-size: 20px;
  font-weight: 700;
  color: var(--gold-600);
}
.good__name {
  font-family: var(--font-display);
  font-size: 18px;
  line-height: 1.4;
  transition: color var(--dur-1) var(--ease);
}
.good:hover .good__name {
  color: var(--brand-700);
}
.good__spec {
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.good__story {
  font-size: var(--fs-sm);
  line-height: 1.75;
  color: var(--ink-500);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.good__exp {
  margin-top: auto;
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line-soft);
  font-size: var(--fs-cap);
  line-height: 1.6;
  color: var(--brand-600);
}
.good__exp-k {
  font-style: normal;
  font-weight: 600;
  margin-right: 6px;
  color: var(--brand-500);
}

/* ============ 响应式 ============ */
@media (max-width: 1080px) {
  .themes {
    grid-template-columns: 1fr;
  }
  .goods {
    grid-template-columns: repeat(2, 1fr);
    gap: var(--sp-5);
  }
  .theme {
    flex-direction: row;
  }
  .theme__art {
    width: 240px;
    flex: none;
  }
  .theme__body {
    justify-content: center;
  }
  .feat__grid {
    grid-template-columns: 1fr;
  }
  .feat {
    min-height: 400px;
  }
  .picks {
    grid-template-rows: none;
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .goods {
    grid-template-columns: 1fr;
  }
  .exbanner {
    min-height: auto;
    padding: var(--sp-8) 0 var(--sp-7);
  }
  .exstats {
    gap: var(--sp-5) var(--sp-6);
    margin-top: var(--sp-6);
  }
  .exstats__i dd {
    font-size: 24px;
  }
  .theme {
    flex-direction: column;
  }
  .theme__art {
    width: 100%;
  }
  .feat__body {
    padding: var(--sp-5) var(--sp-5) var(--sp-6);
  }
  .pick {
    grid-template-columns: 96px 1fr;
  }
  .toolbar__right {
    width: 100%;
  }
  .input {
    flex: 1;
    width: auto;
  }
}
</style>
