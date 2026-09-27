<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PoiImage from '@/components/PoiImage.vue'
import DiversionNoticeBar from '@/components/DiversionNoticeBar.vue'
import { getExperiences, getPoiDetail, getProducts } from '@/api/citypack'
import { getPoiImages } from '@/api/media'
import { createCheckin } from '@/api/trips'
import { ApiError } from '@/api/http'
import { useAsync } from '@/composables/useAsync'
import { usePoiStats } from '@/composables/usePoiStats'
import { useDiversionNotices } from '@/composables/useDiversionNotices'
import { useSessionStore } from '@/stores/session'
import { BUSINESS_LABEL, type Experience, type Poi, type Product, type RelationItem } from '@/types'
// 承载力已接真实数据（M5，见下方 usePoiStats）。本文件仍留在 mock 里的只有
// ratingOf / reviewsOf —— 那是 M6 评价域的数据，poi 表没有评分列、
// order_review 也还没有面向资源点的查询接口，等 M6 补齐后一并去掉。
import { ratingOf } from '@/mock/stats'
import { reviewsOf } from '@/mock/reviews'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

// ----------------------------------------------------------------------
// 到访打卡（M6 到访消费链）
//
// 这是整条消费链的**起点**：打卡 → 足迹 → 「乡村好物」先给你看体验过的
// → 订单被标成"到访消费（TRIP）"而不是"离境复购"。
//
// 只传 poi_id，**不传 source**：source 是"这条足迹是怎么来的"的标注，
// 只有系统能说"这次到访是分流引导来的"。由前端传的话，
// 大屏上的"分流贡献量"就成了可以自己填的数。服务端会忽略这个键。
//
// 未登录时给提示再跳登录页，与 Goods.vue 的加购同一处理：
// 提示必须比跳转早 0.8 秒 —— 页面一跳，提示条（组件状态）就没了，
// 用户看到的是"点了按钮，莫名其妙到了登录页"。
// ----------------------------------------------------------------------
/**
 * "我今天打过卡没有"的本地态（修复 A10：离开页面再回来按钮回到初始态）。
 *
 * 为什么用 sessionStorage（与本项目已有的 frontend-session-persistence 一致）：
 * ① 它是"这一次访问的状态"，不是长期资产 —— 关掉标签页就该清掉，跟
 *    `Agent.vue` 的对话历史同一处取舍。
 * ② localStorage 会把同一台机器上别人的打卡记录留给下一个人看。
 *
 * 为什么不直接调接口"我今天打卡了没"：
 * 之前在这里写过 —— "为了一个纯展示的状态多打一次接口，而这个状态
 * 本来就不影响任何别的显示"。sessionStorage 的代价是**关掉标签页
 * 就丢**：用户重新打开页面会看到"我到过这里"按钮可点 —— 但服务端
 * 打卡是**幂等**的（B7），第二次点击仍然成功，所以最坏只是文案误导。
 *
 * 这里的 key 是按天滚动的：换日自然就过期，无需主动清理。
 */
const CHECKIN_KEY_PREFIX = 'hanyou_trip_checkins_'
function loadCheckedPoisToday(): Set<string> {
  const key = CHECKIN_KEY_PREFIX + new Date().toISOString().slice(0, 10)
  try {
    const raw = sessionStorage.getItem(key)
    return new Set(raw ? (JSON.parse(raw) as string[]) : [])
  } catch {
    return new Set()
  }
}
function markCheckedPoiToday(poiId: string) {
  const key = CHECKIN_KEY_PREFIX + new Date().toISOString().slice(0, 10)
  const set = loadCheckedPoisToday()
  set.add(poiId)
  try {
    sessionStorage.setItem(key, JSON.stringify([...set]))
  } catch {
    /* 配额爆掉就当没记，不影响主体功能：服务端幂等兜底 */
  }
}

const checkinBusy = ref(false)
const checkedIn = ref(false)
const checkinMsg = ref('')
/** 正在被送去登录页。防重复点击，与 Goods.vue 的 leaving 同一用途 */
const checkinLeaving = ref(false)

async function doCheckin() {
  if (!session.isLoggedIn) {
    if (checkinLeaving.value) return
    checkinLeaving.value = true
    checkinMsg.value = '请先登录，再把这次到访记下来'
    window.setTimeout(() => {
      void router.push({ path: '/login', query: { redirect: route.fullPath } })
    }, 800)
    return
  }
  if (checkinBusy.value || !poi.value) return
  checkinBusy.value = true
  try {
    await createCheckin({ poi_id: poi.value.id })
    checkedIn.value = true
    markCheckedPoiToday(poi.value.id)
    checkinMsg.value = '已记入我的足迹 —— 下次它会决定先给你看什么'
  } catch (e) {
    checkinMsg.value = e instanceof ApiError ? e.message : '打卡失败，请稍后重试'
  } finally {
    checkinBusy.value = false
  }
}

/**
 * 详情页数据。
 * 四份数据全部来自后端：资源本体与四组关系走 /api/pois/{id}，体验与产品按 poi_id 过滤后取，
 * 配图走 /api/media/poi-images?poi_id=（M9）。
 * 关系是后端按球面距离与业态规则算好的，体验与产品的所属名称也由后端补全，
 * 前端不再自己算距离、也不再多取一份全量列表来查名字。
 *
 * 多发了这一个配图请求，是为了让 M9 的"每个景点多张图 + 可排序"真的用得上：
 * 只显示封面的话，运营传第二张、调顺序在游客端都看不出效果。
 *
 * 资源不存在（后端错误码 1001）返回 null，走"未找到"空状态而不是错误态：
 * 链接过期、资源下架是正常的产品情况，不该给用户看一行技术性红字。
 * 错误态留给"连不上后端""服务异常"这类真正需要重试的情况。
 */
async function loadDetail(id: string) {
  try {
    const [detail, experiences, products, images] = await Promise.all([
      getPoiDetail(id),
      getExperiences({ poiId: id }),
      getProducts({ poiId: id }),
      getPoiImages(id),
    ])
    return { detail, experiences, products, images }
  } catch (e) {
    if (e instanceof ApiError && e.code === 1001) return null
    throw e
  }
}

const { data, loading, error, reload } = useAsync(() => loadDetail(String(route.params.id)))

// 从「周边联动」点进另一个资源时组件会被复用（路由参数变化不会重建组件），
// 必须显式重新取数，否则页面会停在上一个资源上。
watch(
  () => route.params.id,
  () => reload()
)

const poi = computed<Poi | undefined>(() => data.value?.detail.poi)
const experiences = computed<Experience[]>(() => data.value?.experiences ?? [])
const products = computed<Product[]>(() => data.value?.products ?? [])

// poi 切换时，从 sessionStorage 恢复"已打卡"本地态。
// 注意：只有"今天"的打卡算 —— KEY 是按天滚动的，明天自动失效。
watch(
  () => poi.value?.id,
  (id) => {
    if (!id) {
      checkedIn.value = false
      checkinMsg.value = ''
      return
    }
    checkedIn.value = loadCheckedPoisToday().has(id)
    checkinMsg.value = ''
  },
  { immediate: true }
)

/** 该景点的实拍图，按后端给的 sort_order 排。url 为空的（理论上不会有）滤掉 */
const images = computed(() => (data.value?.images ?? []).filter((i) => !!i.url))

/**
 * 当前展示在 Hero 上的那张。
 *
 * 默认 0 号 —— 也就是运营在管理端排在第一位的那张（通常就是封面）。
 * 点缩略图可以切换，这样"直观看到该景点的照片"不止一张。
 */
const heroIndex = ref(0)
const heroSrc = computed(() => images.value[heroIndex.value]?.url || '')

// 换了资源要回到第一张，否则会拿上一个景点的下标去取这个景点的图
watch(
  () => route.params.id,
  () => {
    heroIndex.value = 0
  }
)
watch(images, () => {
  if (heroIndex.value >= images.value.length) heroIndex.value = 0
})

/**
 * 距离文案。
 * 有些住宿点与景区坐标相同（客栈就在古镇里、民宿就在景区内），
 * 后端算出来是 0km——直接显示 "0 km" 会被当成 bug，这里改说"同址"。
 */
function fmtKm(km?: number) {
  if (km == null) return ''
  return km < 1 ? '同址' : `${Math.round(km)} km`
}

/**
 * 承载力（M5，真实数据）。
 *
 * 数字来自 `/api/stats/pois`（合成客流，`synthetic=true`），不再是按 id 派生的
 * 伪随机值。`stat` 为 undefined 表示"还没加载完 / 接口失败 / 该点位确实没编数据"，
 * 三种情况在界面上统一显示 "—" —— **不能回落成 0**：
 * 0% 是"很空"（一个结论），读不到是"不知道"（另一个结论），
 * 把后者显示成前者等于凭空给了一个结论。
 */
const { statOf, usageOf } = usePoiStats()
const stat = computed(() => (poi.value ? statOf(poi.value.id) : undefined))
const usage = computed(() => (stat.value?.has_data ? stat.value.capacity_usage : undefined))
const overloaded = computed(() => (usage.value ?? 0) >= 0.8)
const usageText = computed(() => (usage.value == null ? '—' : `${Math.round(usage.value * 100)}%`))
const usageWidth = computed(() => `${Math.min(usage.value ?? 0, 1) * 100}%`)

/**
 * 乡村分流建议（自动）。
 *
 * 候选来自后端的 diversion 关系（景区 → 可承接的乡村，按距离由近到远）。
 * M1 阶段这里用仿真承载值过滤，是"空间上可承接"；现在换成真实承载，
 * 才是"**当前**可承接"—— 这正是 M5 对这条链路的交付（见模块文档的
 * "M5 → M1（反向）前端层依赖"）。
 *
 * **承载未知的乡村点不进列表**：这一块的说服力全在"当前宽裕"四个字上，
 * 拿一个读不到承载的点来凑数，等于把一个可核对的建议变成不可核对的。
 *
 * <p>★ 它与下方的「分流公告」是**两套来源**，优先级由 `v-if` 决定：
 * 有运营发布的公告时，只显示公告（公告是人工审过、有署名的版本），
 * 这一块让位。没有公告时才由这一块兜底 —— 页面不至于因为"没人发布"
 * 就什么都不提示。两者的候选来源目前不同（这边是 M1 的边，那边是
 * `DiversionAdvisor`），**同一时刻只出现一个**，所以游客看不到两套说法。
 */
const diversions = computed(() =>
  (data.value?.detail.diversion ?? [])
    .flatMap((r) => {
      const u = usageOf(r.id)
      return u != null && u < 0.75 ? [{ ...r, u }] : []
    })
    .slice(0, 3)
)

/**
 * 与本资源点相关的分流公告（M5 续）。
 *
 * 只取 `from_poi_id === 本点` 的：公告说的是"**这个点**挤了，改往别处"，
 * 所以它只属于那个溢出的点。本点作为**候选**出现在别人的公告里时，
 * 不该在这里显示 —— 游客打开候选点详情页时要知道的是"这里现在怎么样"，
 * 而不是"别人被建议来这里"。
 *
 * 不过滤 `available_count`：详情页是游客已经主动点进来之后看到的页面，
 * 这里给的是完整信息（含"当前已不宽裕"），首页那条才做"全满就不显示"的收敛。
 */
const { forPoi: noticesForPoi } = useDiversionNotices()
const diversionNotices = computed(() => (poi.value ? noticesForPoi(poi.value.id) : []))

const nearby = computed<RelationItem[]>(() => data.value?.detail.nearby ?? [])
const nearbyBusiness = computed<RelationItem[]>(() => data.value?.detail.support ?? [])

const reviews = computed(() => (poi.value ? reviewsOf(poi.value.id, poi.value.business_type) : []))

/** 近 7 日客流（不含今天），后端按时间正序给 */
const trend = computed(() => stat.value?.week_visitors ?? [])
const trendMax = computed(() => Math.max(1, ...trend.value))
const visitors = computed(() => (stat.value?.has_data ? stat.value.today_visitors : undefined))
const visitorsText = computed(() =>
  visitors.value == null ? '—' : visitors.value.toLocaleString()
)
const rating = computed(() => (poi.value ? ratingOf(poi.value.id) : 0))
</script>

<template>
  <div class="detail">
    <!-- 加载 -->
    <div v-if="loading" class="container section">
      <div class="skeleton" style="height: 340px; border-radius: 10px; margin-bottom: 24px" />
      <div class="skeleton" style="height: 180px; border-radius: 10px" />
    </div>

    <!-- 错误 -->
    <div v-else-if="error" class="container section">
      <div class="state-error">
        <p>{{ error }}</p>
        <button class="btn btn-ghost btn-sm" @click="reload">重新加载</button>
      </div>
    </div>

    <!-- 不存在 -->
    <div v-else-if="!poi" class="container section">
      <div class="empty">
        <div class="empty__title">没有找到这个资源</div>
        <div class="empty__desc">它可能已被调整，或链接不正确</div>
        <router-link to="/explore" class="btn btn-ghost btn-sm" style="margin-top: 16px">
          返回探索
        </router-link>
      </div>
    </div>

    <template v-else>
      <!-- ============ Hero ============ -->
      <header class="dhero">
        <PoiImage
          :poi-id="poi.id"
          :src="heroSrc"
          :scene="poi.scene"
          ratio="auto"
          eager
          :alt="poi.name"
          class="dhero__art"
        />
        <div class="dhero__veil" />
        <div class="dhero__veil-b" />
        <div class="container dhero__inner">
          <nav class="crumb">
            <router-link to="/">首页</router-link>
            <span>/</span>
            <router-link to="/explore">探索</router-link>
            <span>/</span>
            <span class="crumb__now">{{ poi.name }}</span>
          </nav>
          <div class="row dhero__tags">
            <span class="tag tag-gold">{{ BUSINESS_LABEL[poi.business_type] }}</span>
            <span class="tag tag-light">{{ poi.district }}</span>
            <span v-if="poi.level" class="tag tag-light">{{ poi.level }}</span>
          </div>
          <h1 class="display dhero__title">{{ poi.name }}</h1>
          <p class="dhero__sub">{{ poi.summary }}</p>

          <!--
            到访打卡（M6 到访消费链）。放在首屏标题下面而不是侧栏：
            它是整条消费链的起点，而且用户"到过这里"这件事只在他
            站在这个页面的这一刻说得清 —— 埋进侧栏等于让人去找。
            按钮的文案与状态都由本地维护（打卡成功即置灰），
            不额外请求"我今天打卡了没"：那会为了一个纯展示的状态
            多打一次接口，而这个状态本来就不影响任何别的显示。
          -->
          <div class="dhero__acts">
            <button
              class="btn btn-gold btn-sm"
              :disabled="checkinBusy || checkedIn"
              @click="doCheckin"
            >
              <span v-if="checkinBusy">记录中…</span>
              <span v-else-if="checkedIn">已记入足迹</span>
              <span v-else>我到过这里</span>
            </button>
            <router-link v-if="checkedIn" to="/footprints" class="dhero__actlink">
              查看我的足迹
            </router-link>
            <span v-if="checkinMsg" class="dhero__actmsg">{{ checkinMsg }}</span>
          </div>

          <!-- Hero 底部速览：把原来只存在于侧栏的关键信息提到首屏 -->
          <div class="dhero__quick">
            <div class="quick">
              <span class="quick__label">门票</span>
              <span class="num quick__val">
                {{ poi.ticket_price > 0 ? `¥${poi.ticket_price}` : '免费' }}
              </span>
            </div>
            <span class="quick__sep" />
            <div class="quick">
              <span class="quick__label">建议时长</span>
              <span class="num quick__val">{{ poi.duration_min }}<i>分钟</i></span>
            </div>
            <span class="quick__sep" />
            <div class="quick">
              <span class="quick__label">当前承载</span>
              <span class="num quick__val" :class="{ 'quick__val--hot': overloaded }">
                {{ usageText }}
              </span>
            </div>
            <span class="quick__sep" />
            <div class="quick">
              <span class="quick__label">口碑</span>
              <span class="num quick__val">{{ rating }}</span>
            </div>
          </div>
        </div>
      </header>

      <!--
        ============ 分流公告（M5 续）============
        排在 Hero 之后、正文之前：游客打开这个页面最先要回答的问题是
        "我还该不该来这里"。公告是运营审过、有署名的版本，所以它比
        侧栏那块自动的「分流建议」更靠前 —— 但两者不会同时出现，
        见 script 里 diversions 的注释。
      -->
      <section v-if="diversionNotices.length" class="container dnwrap">
        <DiversionNoticeBar
          v-for="n in diversionNotices"
          :key="n.id"
          :notice="n"
          variant="inline"
        />
      </section>

      <!-- ============ 实景照片（M9）============ -->
      <!--
        只在有多张图时出现：一张图的时候 Hero 已经把它显示出来了，
        再放一条只有一个缩略图的横条是多余的。
        顺序就是运营在管理端排的顺序，不在这里重排 —— 排好序是运营的决定。
      -->
      <section v-if="images.length > 1" class="gallery">
        <div class="container">
          <div class="row-between gallery__head">
            <div class="gallery__title">
              <span class="eyebrow">实景照片</span>
              <h2 class="h3">这一处，从不同角度看</h2>
            </div>
            <span class="muted small">共 {{ images.length }} 张 · 点击切换上方主图</span>
          </div>

          <div class="gallery__strip">
            <button
              v-for="(im, i) in images"
              :key="im.id"
              class="gthumb"
              :class="{ 'gthumb--on': i === heroIndex }"
              :aria-label="`查看第 ${i + 1} 张照片`"
              :aria-current="i === heroIndex"
              @click="heroIndex = i"
            >
              <img
                v-if="im.url"
                class="gthumb__img"
                :src="im.url"
                :alt="im.alt_text || poi.name"
                loading="lazy"
                decoding="async"
              />
              <span v-if="im.is_cover === 1" class="gthumb__badge">封面</span>
            </button>
          </div>
        </div>
      </section>

      <!-- ============ 主体 ============ -->
      <div class="container dbody">
        <div class="dbody__main">
          <section class="block">
            <span class="eyebrow">资源简介</span>
            <p class="dbody__lead">{{ poi.summary }}</p>
            <div class="row dbody__tags" style="margin-top: 16px">
              <span v-for="t in poi.tags" :key="t" class="tag tag-brand">{{ t }}</span>
            </div>
          </section>

          <!-- 乡村体验 -->
          <section v-if="experiences.length" class="block">
            <div class="row-between block__head">
              <div>
                <span class="eyebrow">乡村体验</span>
                <h2 class="h2 block__title">在这里可以做的事</h2>
              </div>
              <span class="muted small">{{ experiences.length }} 项可预约</span>
            </div>

            <div class="exps">
              <article v-for="e in experiences" :key="e.id" class="exp">
                <div class="exp__left">
                  <h3 class="h3 exp__name">{{ e.name }}</h3>
                  <p class="exp__desc">{{ e.desc }}</p>
                  <div class="row exp__tags">
                    <span v-for="t in e.tags" :key="t" class="tag">{{ t }}</span>
                  </div>
                </div>
                <div class="exp__right">
                  <span class="num exp__price">¥{{ e.price }}</span>
                  <span class="muted small">{{ e.duration_min }} 分钟 · {{ e.season }}</span>
                </div>
              </article>
            </div>
          </section>

          <!-- 关联产品（体验溯源） -->
          <section v-if="products.length" class="block">
            <div class="row-between block__head">
              <div>
                <span class="eyebrow">乡村好物</span>
                <h2 class="h2 block__title">这一次体验，可以带回家</h2>
              </div>
              <span class="muted small">产品与体验锚定，离境可复购</span>
            </div>

            <div class="goods">
              <article v-for="g in products" :key="g.id" class="good card card-hover">
                <PoiImage
                  :poi-id="g.poi_id"
                  :scene="g.scene"
                  ratio="1 / 1"
                  :alt="`${g.origin_village} · ${g.name}`"
                  class="good__art"
                />
                <div class="good__body">
                  <span class="tag tag-brand good__from">来自「{{ g.experience_name || '乡村体验' }}」</span>
                  <h3 class="h3 good__name">{{ g.name }}</h3>
                  <p class="muted small">{{ g.spec }} · {{ g.origin_village }}</p>
                  <p class="good__story">{{ g.story }}</p>
                  <div class="good__foot">
                    <span class="num good__price">¥{{ g.price }}</span>
                    <span class="muted small">库存 {{ g.stock }}</span>
                  </div>
                </div>
              </article>
            </div>
          </section>

          <!-- 周边联动 -->
          <section v-if="nearby.length" class="block">
            <span class="eyebrow">周边联动</span>
            <h2 class="h2 block__title">同一线路上的其他资源</h2>
            <div class="near">
              <router-link v-for="n in nearby" :key="n.id" :to="`/poi/${n.id}`" class="near__item">
                <span class="near__name">{{ n.name }}</span>
                <span class="num near__km">{{ fmtKm(n.distance_km) }}</span>
              </router-link>
            </div>
          </section>

          <!-- 配套业态 -->
          <section v-if="nearbyBusiness.length" class="block">
            <div class="row-between block__head">
              <div>
                <span class="eyebrow">业态融合</span>
                <h2 class="h2 block__title">同一片区域里的吃、住、行</h2>
              </div>
              <span class="muted small">与本站点共同构成一条可执行的行程</span>
            </div>
            <div class="biz">
              <router-link
                v-for="b in nearbyBusiness"
                :key="b.id"
                :to="`/poi/${b.id}`"
                class="biz__item"
              >
                <span class="tag tag-brand">{{ BUSINESS_LABEL[b.business_type] }}</span>
                <span class="biz__name">{{ b.name }}</span>
                <span class="num biz__km">{{ fmtKm(b.distance_km) }}</span>
              </router-link>
            </div>
          </section>

          <!-- 游客评价 -->
          <section v-if="reviews.length" class="block">
            <div class="row-between block__head">
              <div>
                <span class="eyebrow">游客反馈</span>
                <h2 class="h2 block__title">来过的人怎么说</h2>
              </div>
              <span class="muted small">用于负面评价率与风险规则</span>
            </div>
            <ul class="reviews">
              <li v-for="(r, i) in reviews" :key="i" class="review">
                <div class="review__head">
                  <span class="review__author">{{ r.author }}</span>
                  <span class="review__stars">{{ '★'.repeat(r.rating) }}<i>{{ '★'.repeat(5 - r.rating) }}</i></span>
                  <span class="review__date num">{{ r.date }}</span>
                </div>
                <p class="review__text">{{ r.text }}</p>
                <span class="tag review__tag">{{ r.tag }}</span>
              </li>
            </ul>
          </section>

          <p class="disclaimer">
            数据来源：名称、等级、简介整理自汉中市文化和旅游局等公开渠道；
            承载力、客流、评分与游客评价均为演示用仿真数据（SIMULATED），不代表真实统计口径。
          </p>
        </div>

        <!-- ============ 侧栏 ============ -->
        <aside class="dside">
          <div class="card infocard">
            <div class="infocard__row">
              <span class="muted small">门票</span>
              <span class="num infocard__val">
                {{ poi.ticket_price > 0 ? `¥${poi.ticket_price}` : '免费开放' }}
              </span>
            </div>
            <hr class="hairline" />
            <div class="infocard__row">
              <span class="muted small">开放时间</span>
              <span class="infocard__val small">{{ poi.open_hours }}</span>
            </div>
            <hr class="hairline" />
            <div class="infocard__row">
              <span class="muted small">建议时长</span>
              <span class="infocard__val small">{{ poi.duration_min }} 分钟</span>
            </div>
            <hr class="hairline" />
            <div class="infocard__row">
              <span class="muted small">口碑评分</span>
              <span class="num infocard__val">{{ rating }}</span>
            </div>
          </div>

          <!-- 承载状态。数字来自 /api/stats/pois（合成客流），读不到时显示 "—" -->
          <div class="card loadcard">
            <div class="loadcard__head">
              <span class="eyebrow">当日承载</span>
              <span class="num loadcard__pct" :class="{ 'loadcard__pct--hot': overloaded }">
                {{ usageText }}
              </span>
            </div>
            <div class="loadcard__bar">
              <i :class="{ 'loadcard__fill--hot': overloaded }" :style="{ width: usageWidth }" />
            </div>
            <p class="muted small loadcard__note">
              今日到访约 {{ visitorsText }} 人 / 承载上限
              {{ poi.capacity.toLocaleString() }} 人
            </p>

            <div class="spark">
              <div
                v-for="(v, i) in trend"
                :key="i"
                class="spark__bar"
                :style="{ height: Math.max(6, (v / trendMax) * 100) + '%' }"
                :title="`近七日 ${v} 人`"
              />
            </div>
            <p class="muted small">近 7 日客流趋势（仿真）</p>
          </div>

          <!--
            分流建议（自动兜底）。候选是后端算好的 diversion 关系，再按**真实承载**
            过滤，所以这里是"当前可承接"而不是 M1 阶段的"空间上可承接"。

            **有运营发布的公告时整块让位**（`!diversionNotices.length`）：
            两块的候选来源不同，同时显示会变成"两套说法"。
            公告是人工审过的，优先级更高。

            标题**刻意不写「AI」**：这一块的判定全程是确定性的 ——
            距离与方向来自 poi_relation，承载阈值来自 risk_rule，
            没有一步经过模型。把确定性结论标成 AI 生成，
            恰好丢掉了本系统最值得讲的那一点（规则引擎判、模型只负责解释）。
          -->
          <div
            v-if="overloaded && diversions.length && !diversionNotices.length"
            class="card divcard"
          >
            <span class="eyebrow">分流建议 · 规则引擎判定</span>
            <p class="divcard__desc">
              该点位承载已接近上限，系统建议把部分行程引导至以下乡村点——
              车程更短、当前承载宽裕，且具备可体验、可消费的乡村业态。
            </p>
            <router-link
              v-for="d in diversions"
              :key="d.id"
              :to="`/poi/${d.id}`"
              class="divcard__item"
            >
              <span class="divcard__name">{{ d.name }}</span>
              <span class="muted small">{{ fmtKm(d.distance_km) }} · 承载 {{ Math.round(d.u * 100) }}%</span>
            </router-link>
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* ---------- Hero ---------- */
.dhero {
  position: relative;
  padding: var(--sp-9) 0 var(--sp-7);
  min-height: 500px;
  display: flex;
  align-items: flex-end;
}
.dhero__art {
  position: absolute;
  inset: 0;
  aspect-ratio: auto !important;
  border-radius: 0;
}
.dhero__veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    180deg,
    rgba(11, 33, 25, 0.25) 0%,
    rgba(11, 33, 25, 0.55) 52%,
    rgba(11, 33, 25, 0.9) 100%
  );
}
.dhero__veil-b {
  position: absolute;
  inset: 0;
  background: linear-gradient(100deg, rgba(11, 33, 25, 0.5) 0%, rgba(11, 33, 25, 0) 62%);
}
.dhero__inner {
  position: relative;
  padding-bottom: var(--sp-2);
}
.crumb {
  display: flex;
  gap: var(--sp-2);
  font-size: var(--fs-cap);
  color: rgba(255, 255, 255, 0.6);
  margin-bottom: var(--sp-5);
}
.crumb a:hover {
  color: #fff;
}
.crumb__now {
  color: rgba(255, 255, 255, 0.9);
}
.dhero__tags {
  gap: var(--sp-2);
  margin-bottom: var(--sp-4);
}
.tag-light {
  background: rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.88);
}
.dhero__title {
  color: #fff;
  max-width: 18em;
  text-shadow: 0 2px 24px rgba(11, 33, 25, 0.35);
}
.dhero__sub {
  margin-top: var(--sp-4);
  max-width: 42em;
  font-size: var(--fs-body);
  line-height: 1.85;
  color: rgba(255, 255, 255, 0.76);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/*
  到访打卡那一条（M6 到访消费链）。
  与速览条同为 Hero 内的附加行，但排在它上面：打卡是**动作**，
  速览是**信息**，动作要更靠近标题。
  消息文字用半透明白而不是独立色块 —— Hero 上叠任何实色块
  都会把照片压暗一块，而这句话只是按钮的注解，不是独立提示条。
*/
.dhero__acts {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: var(--sp-5);
}
.dhero__actlink {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.86);
  text-decoration: underline;
  text-underline-offset: 3px;
}
.dhero__actmsg {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.72);
}

/* Hero 首屏速览条 */
.dhero__quick {
  margin-top: var(--sp-7);
  padding-top: var(--sp-5);
  border-top: 1px solid rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  gap: var(--sp-6);
  flex-wrap: wrap;
}
.quick {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.quick__label {
  font-size: var(--fs-cap);
  letter-spacing: 0.1em;
  color: rgba(255, 255, 255, 0.58);
}
.quick__val {
  font-size: 24px;
  font-weight: 600;
  color: #fff;
  line-height: 1.2;
}
.quick__val i {
  font-size: var(--fs-sm);
  font-style: normal;
  font-weight: 400;
  margin-left: 3px;
  color: rgba(255, 255, 255, 0.6);
}
.quick__val--hot {
  color: #f0a08c;
}
.quick__sep {
  width: 1px;
  height: 34px;
  background: rgba(255, 255, 255, 0.2);
}

/* ---------- 主体 ---------- */
.dbody {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: var(--sp-7);
  padding: var(--sp-7) var(--sp-6) var(--sp-8);
  align-items: start;
}
.block + .block {
  margin-top: var(--sp-7);
}
.block__head {
  margin-bottom: var(--sp-4);
}
.block__title {
  margin-top: var(--sp-2);
}
.dbody__lead {
  margin-top: var(--sp-3);
  font-size: 16px;
  line-height: 1.9;
  color: var(--ink-700);
}
.dbody__tags {
  gap: var(--sp-2);
  flex-wrap: wrap;
}

/* 体验 */
.exps {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.exp {
  position: relative;
  display: flex;
  gap: var(--sp-5);
  justify-content: space-between;
  padding: var(--sp-5) var(--sp-6);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  transition: border-color var(--dur-2) var(--ease), box-shadow var(--dur-2) var(--ease),
    transform var(--dur-2) var(--ease);
}
/* 左侧金线：强调"可预约"的体验项，比加投影更克制 */
.exp::before {
  content: "";
  position: absolute;
  left: 0;
  top: var(--sp-5);
  bottom: var(--sp-5);
  width: 2px;
  background: var(--gold-500);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  opacity: 0.7;
  transition: opacity var(--dur-2) var(--ease);
}
.exp:hover {
  border-color: var(--brand-300);
  box-shadow: var(--sh-2);
  transform: translateX(3px);
}
.exp:hover::before {
  opacity: 1;
}
.exp__name {
  font-family: var(--font-display);
  font-size: 19px;
  color: var(--ink-900);
}
.exp__desc {
  margin-top: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--ink-500);
  line-height: 1.8;
  max-width: 44em;
}
.exp__tags {
  gap: var(--sp-2);
  margin-top: var(--sp-3);
  flex-wrap: wrap;
}
.exp__right {
  text-align: right;
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: none;
}
.exp__price {
  font-size: 22px;
  font-weight: 600;
  color: var(--gold-600);
}

/* 产品 */
.goods {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sp-4);
}
.good {
  display: flex;
  gap: var(--sp-4);
  padding: var(--sp-4);
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--line-soft);
  border-top: 2px solid var(--brand-600);
  border-radius: 0 0 var(--r-lg) var(--r-lg);
  transition: box-shadow var(--dur-2) var(--ease), transform var(--dur-2) var(--ease);
}
.good:hover {
  box-shadow: var(--sh-2);
  transform: translateY(-3px);
}
.good__art {
  width: 120px;
  flex: none;
  border-radius: var(--r-md);
  overflow: hidden;
}
.good__body {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  min-width: 0;
}
.good__from {
  align-self: flex-start;
  height: auto;
  padding: 3px 8px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--gold-600);
  background: transparent;
  border-left: 2px solid var(--gold-500);
  border-radius: 0;
  padding-left: 7px;
}
.good__name {
  font-family: var(--font-display);
  font-size: 17px;
  color: var(--ink-900);
}
.good__story {
  font-size: var(--fs-sm);
  color: var(--ink-500);
  line-height: 1.75;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.good__foot {
  margin-top: auto;
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line-soft);
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.good__price {
  font-size: 19px;
  font-weight: 600;
  color: var(--gold-600);
}

/* 周边 */
.near {
  display: flex;
  flex-direction: column;
}
.near__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sp-3) 0;
  border-bottom: 1px solid var(--line-soft);
  transition: padding-left var(--dur-1) var(--ease);
}
.near__item:hover {
  padding-left: var(--sp-2);
}
.near__name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-700);
}
.near__km {
  font-size: var(--fs-sm);
  color: var(--warm-500);
}

/* 配套业态 */
.biz {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sp-3);
}
.biz__item {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-md);
  transition: border-color var(--dur-1) var(--ease), box-shadow var(--dur-2) var(--ease);
}
.biz__item:hover {
  border-color: var(--brand-300);
  box-shadow: var(--sh-2);
}
.biz__name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-700);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.biz__km {
  font-size: var(--fs-sm);
  color: var(--warm-500);
  flex: none;
}

/* 评价 */
.reviews {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.review {
  padding: var(--sp-4) var(--sp-5);
  background: var(--paper-2);
  border-radius: var(--r-lg);
}
.review__head {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
}
.review__author {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-700);
}
.review__stars {
  color: var(--gold-500);
  font-size: var(--fs-sm);
  letter-spacing: 1px;
}
.review__stars i {
  color: var(--line);
  font-style: normal;
}
.review__date {
  margin-left: auto;
  font-size: var(--fs-cap);
  color: var(--warm-500);
}
.review__text {
  margin-top: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--ink-600);
  line-height: 1.8;
}
.review__tag {
  margin-top: var(--sp-3);
}

.disclaimer {
  margin-top: var(--sp-7);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--line-soft);
  font-size: var(--fs-cap);
  color: var(--warm-500);
  line-height: 1.8;
}

/* ---------- 侧栏 ---------- */
.dside {
  position: sticky;
  top: calc(var(--nav-h) + var(--sp-4));
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.infocard {
  padding: var(--sp-5);
}
.infocard__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: var(--sp-2) 0;
}
.infocard__val {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-900);
}
.infocard__row + .hairline + .infocard__row {
  padding-top: var(--sp-3);
}

.loadcard {
  padding: var(--sp-5);
}
.loadcard__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.loadcard__pct {
  font-size: 24px;
  font-weight: 700;
  color: var(--brand-700);
}
.loadcard__pct--hot {
  color: var(--danger);
}
.loadcard__bar {
  margin-top: var(--sp-3);
  height: 6px;
  background: var(--paper-3);
  border-radius: var(--r-pill);
  overflow: hidden;
}
.loadcard__bar i {
  display: block;
  height: 100%;
  background: var(--brand-500);
  border-radius: var(--r-pill);
  transition: width var(--dur-3) var(--ease);
}
.loadcard__bar i.loadcard__fill--hot {
  background: var(--danger);
}
.loadcard__note {
  margin-top: var(--sp-3);
}
.spark {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 48px;
  margin: var(--sp-4) 0 var(--sp-2);
}
.spark__bar {
  flex: 1;
  background: var(--brand-300);
  border-radius: 2px 2px 0 0;
  transition: height var(--dur-3) var(--ease);
}
.spark__bar:hover {
  background: var(--brand-500);
}

/* 分流公告：夹在深色 Hero 与正文之间，给上下留出呼吸。
   多条同时生效时叠成一列 —— 12px 卡间距让它们读成一组。 */
.dnwrap {
  margin-top: var(--sp-6);
  display: grid;
  gap: var(--sp-3);
}

.divcard {
  padding: var(--sp-5);
  background: linear-gradient(180deg, var(--gold-50) 0%, #fff 100%);
  border-color: var(--gold-300);
}
.divcard__desc {
  margin-top: var(--sp-3);
  font-size: var(--fs-sm);
  color: var(--ink-600);
  line-height: 1.8;
}
.divcard__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: var(--sp-3);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--gold-300);
}
.divcard__item:first-of-type {
  margin-top: var(--sp-4);
}
.divcard__name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--brand-800);
}
.divcard__item:hover .divcard__name {
  color: var(--gold-600);
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

/* ---------- 实景照片（M9）---------- */
.gallery {
  padding: var(--sp-8) 0 var(--sp-2);
  border-bottom: 1px solid var(--line);
}
.gallery__head {
  align-items: flex-end;
  gap: var(--sp-5);
  margin-bottom: var(--sp-5);
}
.gallery__title {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.gallery__strip {
  display: flex;
  gap: var(--sp-3);
  overflow-x: auto;
  /* 滚动条留一点内边距，不然焦点环会被裁掉 */
  padding: 2px 2px var(--sp-3);
  scrollbar-width: thin;
}
.gthumb {
  position: relative;
  flex: none;
  width: 168px;
  aspect-ratio: 4 / 3;
  padding: 0;
  overflow: hidden;
  border-radius: var(--r-md);
  border: 1px solid var(--line);
  background: var(--paper-2);
  transition: border-color var(--dur-1) var(--ease), transform var(--dur-1) var(--ease);
}
.gthumb:hover {
  border-color: var(--line-strong);
  transform: translateY(-2px);
}
/* 当前主图：金色描边。不用外发光，避免和整体的克制风格冲突 */
.gthumb--on {
  border-color: var(--gold-500);
}
.gthumb__img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.gthumb__badge {
  position: absolute;
  left: 6px;
  top: 6px;
  padding: 1px 6px;
  border-radius: var(--r-sm);
  font-size: var(--fs-cap);
  color: #2b1e07;
  background: var(--gold-500);
}

@media (max-width: 1080px) {
  .dbody {
    grid-template-columns: minmax(0, 1fr);
  }
  .dside {
    position: static;
  }
}
@media (max-width: 720px) {
  .goods,
  .biz {
    grid-template-columns: minmax(0, 1fr);
  }
  .exp {
    flex-direction: column;
  }
  .exp__right {
    text-align: left;
    flex-direction: row;
    align-items: baseline;
    gap: var(--sp-3);
  }
}
</style>
