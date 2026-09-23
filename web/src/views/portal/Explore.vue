<script setup lang="ts">
import { computed, ref } from 'vue'
import SceneArt from '@/components/SceneArt.vue'
import { getCityPack } from '@/api/citypack'
import { isEmpty, useAsync } from '@/composables/useAsync'
import { BUSINESS_LABEL, BUSINESS_ORDER, type BusinessType, type Poi } from '@/types'
import { capacityUsage } from '@/mock/stats'

const { data, loading, error, reload } = useAsync(getCityPack)

const activeType = ref<BusinessType | 'ALL'>('ALL')
const keyword = ref('')
const district = ref<string>('')

const tabs = computed(() => [{ key: 'ALL' as const, label: '全部' }, ...BUSINESS_ORDER.map((t) => ({ key: t, label: BUSINESS_LABEL[t] }))])

const districts = computed(() => {
  const set = new Set((data.value?.pois ?? []).map((p) => p.district))
  return Array.from(set).sort()
})

const list = computed<Poi[]>(() => {
  let arr = data.value?.pois ?? []
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

function usage(p: Poi) {
  return capacityUsage(p.id, p.business_type)
}
function usageText(p: Poi) {
  const u = usage(p)
  return `${Math.round(u * 100)}%`
}
function usageLevel(p: Poi) {
  const u = usage(p)
  if (u >= 1) return 'danger'
  if (u >= 0.8) return 'warn'
  return 'ok'
}
</script>

<template>
  <div class="explore">
    <!-- 头部 -->
    <header class="pagehead">
      <div class="container">
        <span class="eyebrow">探索</span>
        <h1 class="h1 pagehead__title">汉中的每一处资源，都在同一张网络里</h1>
        <p class="body pagehead__desc">
          景区、乡村、餐饮、住宿与交通不是各自独立的模块，而是统一文旅资源网络上的节点。
          系统按业态、区位与承载力共同调度。
        </p>
      </div>
    </header>

    <div class="container toolbar">
      <div class="tabs">
        <button
          v-for="t in tabs"
          :key="t.key"
          class="tabs__item"
          :class="{ 'tabs__item--on': activeType === t.key }"
          @click="activeType = t.key as any"
        >
          {{ t.label }}
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

    <div class="container section">
      <div v-if="loading" class="grid grid-3">
        <div v-for="i in 6" :key="i" class="skeleton" style="height: 320px; border-radius: 10px" />
      </div>

      <div v-else-if="error" class="state-error">
        <p>{{ error }}</p>
        <button class="btn btn-ghost btn-sm" @click="reload">重新加载</button>
      </div>

      <div v-else-if="isEmpty(list)" class="empty">
        <div class="empty__title">没有符合条件的资源</div>
        <div class="empty__desc">换个关键词，或清掉筛选条件再试</div>
        <button class="btn btn-ghost btn-sm" style="margin-top: 16px" @click="activeType = 'ALL'; district = ''; keyword = ''">
          清空筛选
        </button>
      </div>

      <template v-else>
        <div class="result-count muted small">共 {{ list.length }} 处资源</div>
        <div class="grid grid-3">
          <router-link
            v-for="p in list"
            :key="p.id"
            :to="`/poi/${p.id}`"
            class="pcard card card-hover"
          >
            <SceneArt :variant="(p.scene as any) || 'qinling'" ratio="16 / 10" class="pcard__art" />
            <div class="pcard__body">
              <div class="row pcard__meta">
                <span class="tag tag-brand">{{ BUSINESS_LABEL[p.business_type] }}</span>
                <span class="tag">{{ p.district }}</span>
                <span v-if="p.level" class="tag">{{ p.level }}</span>
              </div>
              <h3 class="h3 pcard__title">{{ p.name }}</h3>
              <p class="pcard__summary">{{ p.summary }}</p>

              <div class="load">
                <div class="load__top">
                  <span class="muted small">当前承载</span>
                  <span class="num load__val" :class="`load__val--${usageLevel(p)}`">
                    {{ usageText(p) }}
                  </span>
                </div>
                <div class="load__bar">
                  <i :class="`load__fill load__fill--${usageLevel(p)}`" :style="{ width: Math.min(usage(p), 1) * 100 + '%' }" />
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
    </div>
  </div>
</template>

<style scoped>
.pagehead {
  background: var(--brand-800);
  color: var(--brand-100);
  padding: var(--sp-8) 0 var(--sp-7);
}
.pagehead .eyebrow {
  color: var(--gold-300);
}
.pagehead .eyebrow::before {
  background: var(--gold-300);
}
.pagehead__title {
  margin-top: var(--sp-3);
  color: #fff;
  max-width: 24em;
}
.pagehead__desc {
  margin-top: var(--sp-4);
  color: var(--brand-300);
  max-width: 46em;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
  padding-top: var(--sp-5);
}
.tabs {
  display: flex;
  gap: var(--sp-1);
  border-bottom: 1px solid var(--line-soft);
}
.tabs__item {
  padding: var(--sp-2) var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--ink-500);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: all var(--dur-1) var(--ease);
}
.tabs__item:hover {
  color: var(--brand-700);
}
.tabs__item--on {
  color: var(--brand-800);
  font-weight: 600;
  border-bottom-color: var(--gold-500);
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
  margin-bottom: var(--sp-4);
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
  flex-wrap: wrap;
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

/* 承载力条 */
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

@media (max-width: 720px) {
  .toolbar__right {
    width: 100%;
  }
  .input {
    flex: 1;
    width: auto;
  }
}
</style>
