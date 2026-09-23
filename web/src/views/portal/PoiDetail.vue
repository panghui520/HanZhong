<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import SceneArt from '@/components/SceneArt.vue'
import { getExperiences, getPoiDetail, getProducts } from '@/api/citypack'
import { ApiError } from '@/api/http'
import { useAsync } from '@/composables/useAsync'
import { BUSINESS_LABEL, type Experience, type Poi, type Product, type RelationItem } from '@/types'
import { capacityUsage, todayVisitors, weekVisitors, ratingOf } from '@/mock/stats'
import { reviewsOf } from '@/mock/reviews'

const route = useRoute()

/**
 * 详情页数据。
 * 资源本体与四组关系来自后端 /api/pois/{id}——关系是后端按球面距离与业态规则算好的，
 * 前端不再自己算距离。体验与产品在 M2 之前仍读本地 JSON。
 *
 * 资源不存在（后端错误码 1001）返回 null，走"未找到"空状态而不是错误态：
 * 链接过期、资源下架是正常的产品情况，不该给用户看一行技术性红字。
 * 错误态留给"连不上后端""服务异常"这类真正需要重试的情况。
 */
async function loadDetail(id: string) {
  try {
    const [detail, experiences, products] = await Promise.all([
      getPoiDetail(id),
      getExperiences(),
      getProducts(),
    ])
    return {
      detail,
      experiences: experiences.filter((e) => e.poi_id === id),
      products: products.filter((p) => p.poi_id === id),
      allExperiences: experiences,
    }
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
const expName = (id?: string) =>
  (data.value?.allExperiences ?? []).find((e) => e.id === id)?.name ?? '乡村体验'

/**
 * 距离文案。
 * 有些住宿点与景区坐标相同（客栈就在古镇里、民宿就在景区内），
 * 后端算出来是 0km——直接显示 "0 km" 会被当成 bug，这里改说"同址"。
 */
function fmtKm(km?: number) {
  if (km == null) return ''
  return km < 1 ? '同址' : `${Math.round(km)} km`
}

const usage = computed(() => (poi.value ? capacityUsage(poi.value.id, poi.value.business_type) : 0))
const overloaded = computed(() => usage.value >= 0.8)

/**
 * 乡村分流建议。
 * 候选来自后端的 diversion 关系（景区 → 可承接的乡村，按距离由近到远）；
 * "当前承载是否宽裕"要等 M5 接入真实客流后才有，这里先用仿真值过滤，
 * 所以 M1 的这条链路是"空间上可承接"，不含承载判断。
 */
const diversions = computed(() =>
  (data.value?.detail.diversion ?? [])
    .map((r) => ({ ...r, u: capacityUsage(r.id, r.business_type) }))
    .filter((r) => r.u < 0.75)
    .slice(0, 3)
)

const nearby = computed<RelationItem[]>(() => data.value?.detail.nearby ?? [])
const nearbyBusiness = computed<RelationItem[]>(() => data.value?.detail.support ?? [])

const reviews = computed(() => (poi.value ? reviewsOf(poi.value.id, poi.value.business_type) : []))

const trend = computed(() => (poi.value ? weekVisitors(poi.value.id, poi.value.capacity, poi.value.business_type) : []))
const trendMax = computed(() => Math.max(1, ...trend.value))
const visitors = computed(() => (poi.value ? todayVisitors(poi.value.id, poi.value.capacity, poi.value.business_type) : 0))
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
        <SceneArt :variant="(poi.scene as any) || 'qinling'" ratio="auto" class="dhero__art" />
        <div class="dhero__veil" />
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
          <h1 class="h1 dhero__title">{{ poi.name }}</h1>
        </div>
      </header>

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
                <SceneArt :variant="(g.scene as any) || 'terrace'" ratio="1 / 1" class="good__art" />
                <div class="good__body">
                  <span class="tag tag-brand good__from">来自「{{ expName(g.experience_id) }}」</span>
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

          <!-- 承载状态 -->
          <div class="card loadcard">
            <div class="loadcard__head">
              <span class="eyebrow">实时承载</span>
              <span class="num loadcard__pct" :class="{ 'loadcard__pct--hot': overloaded }">
                {{ Math.round(usage * 100) }}%
              </span>
            </div>
            <div class="loadcard__bar">
              <i :class="{ 'loadcard__fill--hot': overloaded }" :style="{ width: Math.min(usage, 1) * 100 + '%' }" />
            </div>
            <p class="muted small loadcard__note">
              今日到访约 {{ visitors.toLocaleString() }} 人 / 承载上限
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

          <!-- 分流建议 -->
          <div v-if="overloaded && diversions.length" class="card divcard">
            <span class="eyebrow">AI 分流建议</span>
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
  padding: var(--sp-8) 0 var(--sp-7);
  min-height: 380px;
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
    rgba(11, 33, 25, 0.62) 55%,
    rgba(11, 33, 25, 0.88) 100%
  );
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
  margin-bottom: var(--sp-4);
}
.crumb a:hover {
  color: #fff;
}
.crumb__now {
  color: rgba(255, 255, 255, 0.9);
}
.dhero__tags {
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}
.tag-light {
  background: rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.88);
}
.dhero__title {
  color: #fff;
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
  display: flex;
  gap: var(--sp-5);
  justify-content: space-between;
  padding: var(--sp-4) var(--sp-5);
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-lg);
  transition: border-color var(--dur-1) var(--ease), box-shadow var(--dur-2) var(--ease);
}
.exp:hover {
  border-color: var(--brand-300);
  box-shadow: var(--sh-2);
}
.exp__desc {
  margin-top: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--ink-500);
  line-height: 1.75;
  max-width: 46em;
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
  font-size: 20px;
  font-weight: 700;
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
}
.good__art {
  width: 108px;
  flex: none;
  border-radius: var(--r-md);
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
  line-height: 1.5;
}
.good__name {
  font-size: 16px;
}
.good__story {
  font-size: var(--fs-sm);
  color: var(--ink-500);
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.good__foot {
  margin-top: auto;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.good__price {
  font-size: 17px;
  font-weight: 700;
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
