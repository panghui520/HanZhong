<script setup lang="ts">
/**
 * 探索汉中（M1 资源网络 + M6 分类改版）
 *
 * ============================================================
 * 这一版为什么把「主题」从一级降成二级
 * ============================================================
 * 上一版一级是主题（山水 / 人文 / 乡野），业态降成二级标签。
 * 上线后发现它有两个实测问题（都是数出来的，不是感觉）：
 *
 *   1. **餐饮只有 6 条、住宿只有 4 条**，还被 3 个主题拆散 ——
 *      切到「山水」只剩 2 条餐饮，切到「乡野」餐饮和住宿都是 0。
 *      用户想找吃的地方，得先猜它被归进了哪个主题，猜错就是空列表。
 *   2. **主题是"编辑视角"而不是"检索视角"**。游客找住处时想的是
 *      "住宿"，不是"人文"。主题适合做导览叙事，不适合当一级筛选。
 *
 * 所以这一版：
 *   - **一级 = 类别**（景点 / 乡村 / 餐饮 / 住宿 / 交通），条数来自真实数据；
 *   - **二级 = 主题**，降级成一行可取消的筛选标签；
 *   - **三级 = 区县 + 关键词**。
 *
 * 刻意**不做「全部」这一档**：混着看正是"一进来很乱"的来源。
 * 想看全局有页头那四个数字，想换类有 5 个带条数的类别按钮。
 *
 * 类别可以进 URL（`?type=SCENIC`），刷新和分享都能保持在同一类里。
 * ============================================================
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SceneArt from '@/components/SceneArt.vue'
import PoiImage from '@/components/PoiImage.vue'
import SectionHead from '@/components/SectionHead.vue'
import { getCityPack } from '@/api/citypack'
import { isEmpty, useAsync } from '@/composables/useAsync'
import { LOAD_LEVEL_LABEL, usePoiStats } from '@/composables/usePoiStats'
import { useReveal } from '@/composables/useReveal'
import { BUSINESS_LABEL, BUSINESS_ORDER, type BusinessType, type Poi } from '@/types'

const route = useRoute()
const router = useRouter()

const { data, loading, error, reload } = useAsync(getCityPack)

/**
 * 承载力的全局单例（M5，来自 `/api/stats/pois`，合成客流）。
 *
 * 这里**不参与页面的 loading 状态**：客流读不到时页面照常展示资源列表，
 * 只是承载位显示 "—"。把整页挂起来等一个次要指标，代价比收益大得多。
 * 它也不会造成"先显示错的、再跳成对的"—— 取不到时显示的是"未知"而不是某个猜测值。
 */
const { usageOf, usageTextOf, levelOf } = usePoiStats()

const root = ref<HTMLElement | null>(null)

/* ============================================================
 * 主题（二级）：把 5 个「业态」（业务口径）重新组织成 3 个「主题」（游客口径）
 *
 * 主题仍然有价值 —— 它是"怎么逛"的叙事（看山水 / 看人文 / 去乡村），
 * 只是不该占着一级的位置。判定顺序：
 *   - RURAL_SPOT 优先归「乡村」—— 它是本项目的主张所在（把客流导向乡村），
 *     不能被"山水"吸走（不少乡村示范点本身也在秦岭里）。
 *   - 餐饮/住宿/交通按 `scene` 归到山水或人文，充当"顺路可去"的补充。
 * ============================================================ */

type ThemeKey = 'landscape' | 'culture' | 'rural'

function themeOf(p: Poi): ThemeKey {
  if (p.business_type === 'RURAL_SPOT') return 'rural'
  const s = p.scene ?? ''
  const isNature =
    s === 'qinling' || s === 'terrace' || s === 'rapeseed' || s === 'river' || s === 'hanjiang'
  return isNature ? 'landscape' : 'culture'
}

const THEMES: { key: ThemeKey; label: string }[] = [
  { key: 'landscape', label: '山水' },
  { key: 'culture', label: '人文' },
  { key: 'rural', label: '乡野' },
]

/**
 * 一级：类别
 *
 * 顺序沿用 `BUSINESS_ORDER`（景区 → 乡村 → 餐饮 → 住宿 → 交通），
 * 而不是按条数排 —— 顺序稳定，用户第二次来时按钮还在原位。
 * 条数为 0 的类别（当前数据包里 SHOPPING 是 0 条）不显示，
 * 免得给出一个点进去必然为空的入口。
 *
 * 类别名一律取 `BUSINESS_LABEL`，不在这里另起一套文案：
 * 第一类显示的是「景区」而不是口语里的「景点」—— 因为详情页、卡片角标、
 * 管理端用的都是同一套 `business_type` 词汇，这一页改成"景点"就会出现
 * 同一个东西两个名字，用户在两页之间来回看会以为不是一回事。
 */

/** 每个类别一句话说明。文案讲的是"这类资源在调度网络里扮演什么角色" */
const CAT_DESC: Record<BusinessType, string> = {
  SCENIC: '秦岭与汉江之间的自然人文景观，是最需要做承载力分流的入口',
  RURAL_SPOT: '乡村旅游示范村与非遗工坊，离热门景区不远，是分流承接的主力',
  FOOD: '市井小吃与地方菜，多在城区与县城，适合与山水行程错峰组合',
  LODGING: '民宿、乡村会客厅与县城酒店，是过夜消费与次日分流的落点',
  TRANSPORT: '高铁站、客运枢纽与旅游专线，决定客流怎么进城、往哪散',
  SHOPPING: '特产与手作门店，与乡村好物互为补充',
}

const allPois = computed<Poi[]>(() => data.value?.pois ?? [])

function countOfType(t: BusinessType) {
  return allPois.value.filter((p) => p.business_type === t).length
}

const cats = computed(() =>
  BUSINESS_ORDER.filter((t) => countOfType(t) > 0).map((t) => ({
    key: t,
    label: BUSINESS_LABEL[t],
    n: countOfType(t),
    desc: CAT_DESC[t],
  }))
)

/** 初始类别：URL 里给了合法值就用它，否则默认「景点」——多数人来汉中就是看景点 */
const VALID_TYPES = new Set<string>(BUSINESS_ORDER)
function initialType(): BusinessType {
  const q = route.query.type
  return typeof q === 'string' && VALID_TYPES.has(q) ? (q as BusinessType) : 'SCENIC'
}

const activeType = ref<BusinessType>(initialType())
const activeTheme = ref<ThemeKey | 'ALL'>('ALL')
const keyword = ref('')
const district = ref<string>('')

/** 当前类别下的全部资源（未做二级/三级筛选） */
const catPois = computed<Poi[]>(() => allPois.value.filter((p) => p.business_type === activeType.value))

/** 二级：当前类别下真实存在的主题，带条数 */
const themeChips = computed(() =>
  THEMES.map((t) => ({
    ...t,
    n: catPois.value.filter((p) => themeOf(p) === t.key).length,
  })).filter((t) => t.n > 0)
)

/** 三级：当前类别覆盖的区县 */
const districts = computed(() =>
  Array.from(new Set(catPois.value.map((p) => p.district))).sort()
)

/**
 * 是否处于"有二级/三级筛选"的状态 —— 决定要不要隐藏主推区。
 *
 * 注意：**类别不算筛选条件**。它是一级导航，永远有一个选中值，
 * 所以不进这个判断，否则主推区永远不会出现。
 */
const filtered = computed(
  () => activeTheme.value !== 'ALL' || !!district.value || !!keyword.value.trim()
)

/* ============================================================
 * 主推与精选：在当前类别内按「等级 + 承载力」挑
 *
 * 为什么按承载力选：这个页面要讲的主张是"承载力驱动分流"，
 * 把带 4A 头衔又最拥挤的那一条放在头条位置，页面自己就在陈述问题。
 *
 * 承载取不到时按 -1 参与排序（排到最后），**不是按 0**：
 * 0 会让一个"读不到承载"的点看起来最空、反而更可能被选成头条，
 * 而这条头条的全部意义就是"它最挤"。
 * ============================================================ */

const featured = computed<Poi | null>(() => {
  const pool = catPois.value
  if (!pool.length) return null
  const ranked = [...pool].sort((a, b) => {
    const la = a.level === '4A' ? 1 : 0
    const lb = b.level === '4A' ? 1 : 0
    if (la !== lb) return lb - la
    return (usageOf(b.id) ?? -1) - (usageOf(a.id) ?? -1)
  })
  return ranked[0] ?? null
})

/** 次级精选：尽量不同区县、不同主题，避免看起来是"同一个地方的三张图" */
const picks = computed<Poi[]>(() => {
  const used = new Set<string>()
  const out: Poi[] = []
  for (const p of catPois.value) {
    if (p.id === featured.value?.id) continue
    if (used.has(p.district) || used.has(themeOf(p))) continue
    out.push(p)
    used.add(p.district)
    used.add(themeOf(p))
    if (out.length === 3) break
  }
  // 不够 3 条就放宽条件补齐（数据少时不能空着）
  if (out.length < 3) {
    for (const p of catPois.value) {
      if (out.length >= 3) break
      if (p.id === featured.value?.id) continue
      if (out.some((x) => x.id === p.id)) continue
      out.push(p)
    }
  }
  return out
})

/** 主推区只在资源足够多、且没做二级/三级筛选时出现 */
const showFeatured = computed(
  () => !!featured.value && !filtered.value && catPois.value.length >= 5
)

/** 主推区的标题跟着类别走 —— 每一类"最该去的那一处"理由不一样 */
const featTitle = computed(() => {
  switch (activeType.value) {
    case 'SCENIC':
      return '这一类里最该错峰去的那一处'
    case 'RURAL_SPOT':
      return '离景区最近、最能承接客流的一处'
    case 'FOOD':
      return '到汉中该先吃的那一口'
    case 'LODGING':
      return '住下来，第二天才有得玩'
    default:
      return '客流从这里进城'
  }
})

/** 列表：类别 ∩ 主题 ∩ 区县 ∩ 关键词 */
const list = computed<Poi[]>(() => {
  let arr = catPois.value
  if (activeTheme.value !== 'ALL') arr = arr.filter((p) => themeOf(p) === activeTheme.value)
  if (district.value) arr = arr.filter((p) => p.district === district.value)
  const kw = keyword.value.trim()
  if (kw) {
    arr = arr.filter(
      (p) => p.name.includes(kw) || p.summary.includes(kw) || p.tags.some((t) => t.includes(kw))
    )
  }
  return arr
})

/** 全站统计，放在页头 */
const stats = computed(() => ({
  total: allPois.value.length,
  districts: new Set(allPois.value.map((p) => p.district)).size,
  rural: allPois.value.filter((p) => p.business_type === 'RURAL_SPOT').length,
  scenic4a: allPois.value.filter((p) => p.level === '4A').length,
}))

/* ---------- 交互 ---------- */

function usage(p: Poi) {
  return usageOf(p.id)
}
function usageText(p: Poi) {
  return usageTextOf(p.id)
}
function usageLevel(p: Poi) {
  return levelOf(p.id)
}
/** 承载条的宽度。取不到承载时给 0 —— 与文字 "—" 一致，不画一根假的高度 */
function usageWidth(p: Poi) {
  return `${Math.min(usage(p) ?? 0, 1) * 100}%`
}

/** 清掉二级与三级筛选，但**不动类别** —— 类别是一级导航，不是筛选条件 */
function clearSubFilters() {
  activeTheme.value = 'ALL'
  district.value = ''
  keyword.value = ''
}

function pickType(t: BusinessType) {
  if (activeType.value === t) return
  activeType.value = t
  // 类别进 URL：刷新后还在这一类，链接也能直接分享
  void router.replace({ query: { ...route.query, type: t } })
  scrollToGrid()
}

/**
 * 切类别时清掉二级/三级筛选。
 * 否则会出现"新类别 + 旧区县"的空结果 —— 用户看着像页面坏了，
 * 而实际上只是筛选条件互相矛盾。
 */
watch(activeType, clearSubFilters)

// 列表重绘后要重新扫描一遍新的 .reveal 元素
useReveal(
  root,
  loading,
  computed(() => `${activeType.value}|${activeTheme.value}|${district.value}|${keyword.value}`)
)

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
          先按类别看：景点、乡村、餐饮、住宿、交通各自成一片。每一处都带着容量上限与当日占用率 ——
          这正是"把客流从拥挤处导向有余处"的依据。
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
      <div class="skeleton" style="height: 168px; border-radius: 10px" />
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
      <!-- ============ 3. 一级：类别 ============ -->
      <section class="container section">
        <SectionHead
          eyebrow="第一步 · 选类别"
          title="先选一类，再看具体去处"
          desc="类别按游客的检索习惯划分，不混着铺。条数是数据包里的真实条数，不是写死的。"
        />

        <div class="cats">
          <button
            v-for="c in cats"
            :key="c.key"
            class="cat reveal"
            :class="{ 'cat--on': activeType === c.key }"
            @click="pickType(c.key)"
          >
            <span class="cat__top">
              <span class="cat__label">{{ c.label }}</span>
              <span class="cat__n num">{{ c.n }}</span>
            </span>
            <span class="cat__desc">{{ c.desc }}</span>
            <span class="cat__rule" />
          </button>
        </div>
      </section>

      <!-- ============ 4. 二级：主题（可取消） ============ -->
      <section class="container section-0">
        <div class="toolbar">
          <div class="toolbar__left">
            <span class="toolbar__k">主题</span>
            <div class="chips">
              <button
                class="chip"
                :class="{ 'chip--on': activeTheme === 'ALL' }"
                @click="activeTheme = 'ALL'"
              >
                全部<i class="chip__n num">{{ catPois.length }}</i>
              </button>
              <button
                v-for="t in themeChips"
                :key="t.key"
                class="chip"
                :class="{ 'chip--on': activeTheme === t.key }"
                @click="activeTheme = activeTheme === t.key ? 'ALL' : t.key"
              >
                {{ t.label }}<i class="chip__n num">{{ t.n }}</i>
              </button>
            </div>
          </div>

          <div class="toolbar__right">
            <select v-model="district" class="select">
              <option value="">全部区县</option>
              <option v-for="d in districts" :key="d" :value="d">{{ d }}</option>
            </select>
            <input v-model="keyword" class="input" type="search" placeholder="搜索名称 / 特色" />
          </div>
        </div>
      </section>

      <!-- ============ 5. 主推 + 精选（非对称：1.32fr : 1fr） ============ -->
      <section v-if="showFeatured && featured" class="container section-0">
        <SectionHead
          eyebrow="本期主推"
          :title="featTitle"
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
                <span class="tag tag-gold">{{
                  featured.level || BUSINESS_LABEL[featured.business_type]
                }}</span>
                <span class="tag tag-on-dark">{{ featured.district }}</span>
              </div>
              <h3 class="feat__title">{{ featured.name }}</h3>
              <p class="feat__summary">{{ featured.summary }}</p>
              <div class="feat__foot">
                <span class="feat__load" :class="`feat__load--${usageLevel(featured)}`">
                  <i class="feat__dot" />当前承载 {{ usageText(featured) }} ·
                  {{ LOAD_LEVEL_LABEL[usageLevel(featured)] }}
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

      <!-- ============ 6. 资源列表 ============ -->
      <section id="explore-list" class="container section">
        <SectionHead
          eyebrow="资源清单"
          :title="`${BUSINESS_LABEL[activeType]} · ${list.length} 处`"
          desc="按主题与区县继续收窄。列表顺序来自数据包编码顺序，不代表推荐排序。"
          size="md"
        />

        <div v-if="isEmpty(list)" class="empty">
          <div class="empty__title">这一类里没有符合条件的资源</div>
          <div class="empty__desc">换个关键词，或清掉主题与区县筛选再试</div>
          <button class="btn btn-ghost btn-sm" style="margin-top: 16px" @click="clearSubFilters">
            清空筛选
          </button>
        </div>

        <template v-else>
          <div class="result-count">
            <span class="num result-count__num">{{ list.length }}</span>
            <span class="muted small">处{{ BUSINESS_LABEL[activeType] }} · 按承载力与距离统一调度</span>
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
                  {{ LOAD_LEVEL_LABEL[usageLevel(p)] }}
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
                      :style="{ width: usageWidth(p) }"
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

      <!-- ============ 7. 离境复购入口（原「乡村好物」网格） ============
           这里不再铺商品卡：农产品有独立的离境复购页（/goods），
           在这一页铺 4 张卡既让层级变乱，也和那一页重复。 -->
      <section class="container section">
        <div class="exit">
          <div class="exit__body">
            <span class="eyebrow">离境之后</span>
            <h2 class="h2 exit__title">体验过的，可以带走</h2>
            <p class="exit__desc">
              乡村好物在独立的一页：按产地逛，每一款都挂着一处乡村点与一次具体体验，
              挑好填收货信息即可，由运营统一发货。
            </p>
          </div>
          <router-link to="/goods" class="btn btn-gold btn-lg exit__btn">
            去乡村好物 →
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

/* ============ 3. 一级：类别 ============ */
.cats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(196px, 1fr));
  gap: var(--sp-4);
}
.cat {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  text-align: left;
  padding: var(--sp-5) var(--sp-5) var(--sp-6);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  cursor: pointer;
  overflow: hidden;
  transition: transform var(--dur-2) var(--ease), box-shadow var(--dur-2) var(--ease),
    border-color var(--dur-2) var(--ease);
}
.cat:hover {
  transform: translateY(-3px);
  box-shadow: var(--sh-2);
  border-color: var(--line);
}
.cat--on {
  border-color: var(--brand-600);
  box-shadow: var(--sh-brand);
}
.cat__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
}
.cat__label {
  font-family: var(--font-display);
  font-size: 24px;
  color: var(--ink-900);
  letter-spacing: 0.04em;
}
.cat--on .cat__label {
  color: var(--brand-700);
}
.cat__n {
  font-size: 22px;
  font-weight: 600;
  color: var(--warm-400);
  line-height: 1;
}
.cat--on .cat__n {
  color: var(--gold-600);
}
.cat__desc {
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--ink-500);
}
/* 选中的类别在底部拉一条金线：和主题 chip 的选中态呼应，但层级更高 */
.cat__rule {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 3px;
  background: var(--gold-500);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform var(--dur-3) var(--ease);
}
.cat--on .cat__rule {
  transform: scaleX(1);
}

/* ============ 4. 工具栏（二级主题 + 三级区县/关键词） ============ */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-5);
  flex-wrap: wrap;
  padding: var(--sp-4) 0;
  border-top: 1px solid var(--line-soft);
  border-bottom: 1px solid var(--line-soft);
}
.toolbar__left {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.toolbar__k {
  font-size: var(--fs-cap);
  letter-spacing: 0.14em;
  color: var(--warm-500);
}
.chips {
  display: flex;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--ink-500);
  border: 1px solid transparent;
  border-radius: var(--r-pill);
  transition: all var(--dur-1) var(--ease);
}
.chip:hover {
  color: var(--brand-700);
  background: var(--brand-50);
}
.chip--on {
  color: var(--brand-800);
  font-weight: 600;
  background: var(--brand-50);
  border-color: var(--brand-100);
}
.chip__n {
  font-style: normal;
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.chip--on .chip__n {
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

/* ============ 5. 主推 + 精选（刻意 1.32fr : 1fr 不对称） ============ */
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
/* 承载读不到（客流接口未就绪 / 该点位没编数据）。
   刻意做成中性灰：既不能说"舒适"，也不能说"拥挤"，所以不借用任何一侧的颜色。
   写成显式样式而不是让它落回基类，是为了让"这一档存在"在代码里看得见 ——
   否则下一个人会以为只是漏了。 */
.feat__load--unknown {
  color: var(--ink-500);
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

/* ============ 6. 列表 ============ */
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
/* 承载未知：中性灰，不表态（理由同 .feat__load--unknown） */
.pcard__load--unknown {
  color: #eef3f0;
  background: rgba(60, 70, 66, 0.72);
  border: 1px solid rgba(255, 255, 255, 0.2);
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
/* 承载未知：中性灰（理由同 .feat__load--unknown） */
.load__val--unknown {
  color: var(--ink-500);
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
/* 承载未知：宽度本来就是 0，这里给个中性色是为了"这一档存在"在代码里可见 */
.load__fill--unknown {
  background: var(--ink-500);
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

/* ============ 7. 离境复购入口 ============ */
.exit {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-7);
  padding: var(--sp-7);
  background: var(--brand-800);
  border-radius: var(--r-lg);
  flex-wrap: wrap;
}
.exit__body {
  max-width: 620px;
}
.exit__title {
  margin-top: var(--sp-3);
  color: #fff;
}
.exit__desc {
  margin-top: var(--sp-4);
  font-size: var(--fs-sm);
  line-height: 1.85;
  color: var(--brand-300);
}
.exit__btn {
  flex: none;
}

/* ============ 响应式 ============ */
@media (max-width: 1080px) {
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
  .toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
  .toolbar__right {
    width: 100%;
  }
  .input {
    flex: 1;
    width: auto;
  }
  .feat__body {
    padding: var(--sp-5) var(--sp-5) var(--sp-6);
  }
  .pick {
    grid-template-columns: 96px 1fr;
  }
  .exit {
    padding: var(--sp-5);
  }
  .exit__btn {
    width: 100%;
    justify-content: center;
  }
}
</style>
