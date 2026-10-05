<script setup lang="ts">
/**
 * 「资源型业态」业务面板 —— 景点 / 餐饮 / 住宿三个页面共用
 *
 * 这三个页面在结构上是**同一件事**：一个资源清单 + 一组关于它的图表
 * （客流 / 承载 / 分布 / 区县）。差别只有三处：
 *
 *   · `bizType`（scenic / rural / food / lodging）
 *   · 有没有**分段**（只有景点页要分「核心景区 / 乡村景点」）
 *   · 主图是**冷热散点**（景点，要回答"哪些点人不多但已经很挤"）
 *     还是**热度排行**（餐饮 / 住宿，点位少，排行就够）
 *
 * 复制三份的代价不是行数，而是"某一页的图表口径被改坏了没人发现"。
 *
 * ★ **餐饮只有 6 个点、住宿只有 4 个点**，所以排行的标题一律写"全部 N 家"，
 *   不写"Top10" —— 那是个假标题（`§九` / `§十` 明确要求）。
 *
 * ★ **权限口径一行没放宽**：编辑走 `ResourceFormDrawer`（PACK 行先接管确认）、
 *   删除对 PACK 行禁用、上下架从不禁用 —— 与旧 `Resources.vue` 完全一致。
 */
import { computed, onMounted, ref, watch } from 'vue'
import EChart from '@/components/EChart.vue'
import ChartCard from '@/components/admin/ChartCard.vue'
import KpiBar from '@/components/admin/KpiBar.vue'
import type { KpiItem } from '@/components/admin/kpi'
import RangeSwitch from '@/components/admin/RangeSwitch.vue'
import ResourceFormDrawer from '@/components/admin/ResourceFormDrawer.vue'
import ResourceImagesDrawer from '@/components/admin/ResourceImagesDrawer.vue'
import ResourceTable from '@/components/admin/ResourceTable.vue'
import ResourceToolbar from '@/components/admin/ResourceToolbar.vue'
import { useOpsBusiness } from '@/composables/useOpsBusiness'
import { useResourceList, type ResourceRow } from '@/composables/useResourceList'
import {
  areaLineOption,
  distributionBarOption,
  hBarOption,
  scatterOption,
} from '@/utils/adminCharts'
import type { PoiKind } from '@/api/resources'
import type { AdminPoi, OpsBizType } from '@/types'

interface Segment {
  key: string
  label: string
  bizType: OpsBizType
  /** 这一段包含哪些 `business_type`。后端 scenic 端点同时返回景区 + 乡村，前端再切一刀 */
  types: string[]
}

const props = withDefaults(
  defineProps<{
    eyebrow: string
    title: string
    sub: string
    /** 资源名词：景点 / 餐饮 / 住宿 */
    noun: string
    kind: PoiKind
    /** 主图形态 */
    hero: 'scatter' | 'rank'
    heroTitle: string
    heroSub?: string
    heroTip?: string
    /** 分段（只有景点页给）。给了就渲染分段控件 */
    segments?: Segment[]
    /** 分段初值。来自深链 `?seg=rural`（驾驶舱「乡村到访」卡会带过来） */
    initialSeg?: string
    /** 编辑抽屉的业态可选范围 */
    bizOptions: string[]
    defaultBizType: string
    /** 无分段时的固定业态 */
    bizType: OpsBizType
    /** 承载率阈值口径说明（用于"高负荷"KPI 的 hint） */
    highHint?: string
  }>(),
  { segments: undefined, initialSeg: '', heroSub: '', heroTip: '', highHint: '' }
)

// ============================================================
// 分段（核心景区 / 乡村景点）
// ============================================================
/** 初值优先取深链，其次第一段。不认识的值一律回落第一段 —— 深链是外部输入，不能直接当状态用 */
const segKey = ref(
  props.segments?.some((s) => s.key === props.initialSeg)
    ? props.initialSeg
    : (props.segments?.[0]?.key ?? '')
)
const activeSeg = computed(() => props.segments?.find((s) => s.key === segKey.value))
const bizType = computed<OpsBizType>(() => activeSeg.value?.bizType ?? props.bizType)

/** 列表要显示哪些 business_type */
const segTypes = computed<string[] | null>(() => activeSeg.value?.types ?? null)

// ============================================================
// 运营数据
// ============================================================
const { range, data, loading, switching, error, load, pickRange } = useOpsBusiness(bizType)

const kpiItems = computed<KpiItem[]>(() =>
  (data.value?.kpis ?? []).map((k) => ({
    key: k.key,
    label: k.label,
    value: k.value,
    unit: k.unit,
    hint:
      k.key === 'high'
        ? props.highHint || '窗口内平均承载率 ≥ 80% 的资源数（与规则引擎同一阈值）。'
        : k.key === 'usage'
          ? '窗口内的**平均**承载率，可能大于 100%（超载）。不是峰值。'
          : k.key === 'risks'
            ? '窗口内该业态未闭环的风险事件数，与风险工单页同源。'
            : undefined,
  }))
)

const periodNote = computed(() =>
  data.value ? `基准区间 ${data.value.range_from} ~ ${data.value.range_to}` : ''
)

const count = computed(() => data.value?.top.length ?? 0)

// ---- 主图：冷热散点 / 热度排行 ----
const scatterOpt = computed(() => scatterOption(data.value?.scatter ?? []))
const rankOpt = computed(() =>
  hBarOption(data.value?.top ?? [], { color: '#3d8b74', unit: ' 人次' })
)
/** 次图：承载档分布（充裕 / 适中 / 高位） */
const distOpt = computed(() => distributionBarOption(data.value?.distribution ?? [], { unit: ' 个资源' }))
/** 趋势：逐日到访 */
const trendOpt = computed(() =>
  areaLineOption(
    (data.value?.trend ?? []).map((t) => ({ date: t.date, value: t.visitors })),
    { color: '#2e7bc4', unit: ' 人次' }
  )
)
/** 区县客流分布 */
const districtOpt = computed(() =>
  hBarOption(data.value?.districts ?? [], { color: '#c09a4e', unit: ' 人次' })
)

// ============================================================
// 图表点击 → 列表高亮
// ============================================================
/** 散点 / 排行上的每一项就是**一个资源点**，id 与列表行一致，所以能直接高亮 */
const highlightId = ref<string | null>(null)

function onPointClick(p: any) {
  const id = String(p?.data?.id ?? '')
  if (!id) return
  highlightId.value = highlightId.value === id ? null : id
}

// ============================================================
// 资源列表
// ============================================================
const rowFilter = computed(() => {
  const types = segTypes.value
  if (!types) return null
  return (row: ResourceRow) => types.includes((row as AdminPoi).business_type)
})

const list = useResourceList(props.kind, { filter: rowFilter })
const formRef = ref<InstanceType<typeof ResourceFormDrawer> | null>(null)
const imgRef = ref<InstanceType<typeof ResourceImagesDrawer> | null>(null)

function onSave(body: Record<string, unknown>, editingId: string | null) {
  void list.write(editingId ? '已保存' : `已新增${props.noun}`, async () => {
    if (editingId) await list.updatePoi(editingId, body)
    else await list.createPoi(body)
    formRef.value?.close()
    await list.reload()
  })
}

/** 切段时清掉高亮与关键词 —— 高亮的那条资源可能已经不在当前段里了 */
watch(segKey, () => {
  highlightId.value = null
})

onMounted(() => {
  void load()
  void list.reload()
})
</script>

<template>
  <div class="pg">
    <header class="pg__head">
      <div>
        <span class="eyebrow">{{ eyebrow }}</span>
        <h1 class="h1 pg__title">{{ title }}</h1>
        <p class="pg__sub">{{ sub }}</p>
      </div>
      <div class="pg__meta">
        <span class="badge-sim">仿真数据</span>
      </div>
    </header>

    <div class="pg__bar">
      <div v-if="props.segments" class="seg" role="tablist">
        <button
          v-for="s in props.segments"
          :key="s.key"
          type="button"
          role="tab"
          class="seg__btn"
          :class="{ 'seg__btn--on': segKey === s.key }"
          :aria-selected="segKey === s.key"
          @click="segKey = s.key"
        >
          {{ s.label }}
        </button>
      </div>
      <RangeSwitch
        :model-value="range"
        :loading="switching"
        :note="periodNote"
        @update:model-value="pickRange"
      />
      <span v-if="switching" class="pg__busy">更新中…</span>
    </div>

    <KpiBar
      :items="kpiItems"
      :loading="loading"
      note="客流为窗口内人次（不是独立游客数）；承载率为窗口平均，可能超过 100%。全部为仿真统计。"
    />

    <div v-if="error" class="notice notice--err">{{ error }}</div>

    <template v-if="loading">
      <div class="pg__grid pg__grid--top">
        <div v-for="i in 2" :key="i" class="skeleton" style="height: 300px; border-radius: 10px" />
      </div>
    </template>

    <template v-else>
      <!-- 第一层：主图 + 承载档分布 -->
      <div class="pg__grid pg__grid--top">
        <ChartCard
          v-if="props.hero === 'scatter'"
          tone="hero"
          :title="props.heroTitle"
          :sub="props.heroSub || `X = 窗口到访人次，Y = 窗口平均承载率，气泡大小 = 到访量 · 共 ${data?.scatter.length ?? 0} 个资源点`"
          :tip="props.heroTip || '一眼回答「哪些点人不多但已经很挤」：左上角是低客流、高承载 —— 承载力才是它的瓶颈。金色点表示已越过 80% 高负荷线。'"
          :is-empty="!(data?.scatter.length)"
          empty="当前区间内没有资源点的客流数据"
        >
          <EChart :option="scatterOpt" height="320px" @click="onPointClick" />
          <template #foot>点某个气泡 → 下方列表定位并高亮这个资源点。</template>
        </ChartCard>

        <ChartCard
          v-else
          tone="hero"
          :title="props.heroTitle"
          :sub="props.heroSub || `全部 ${count} 家 · 窗口内到访人次`"
          :tip="props.heroTip || '点位很少，所以这里是全部资源的排行，不是 Top N —— 写成 Top10 会是个假标题。'"
          :is-empty="!count"
          empty="当前区间内没有客流数据"
        >
          <EChart :option="rankOpt" height="320px" @click="onPointClick" />
          <template #foot>点某一根条 → 下方列表定位并高亮这个资源点。</template>
        </ChartCard>

        <ChartCard
          tone="main"
          title="承载档分布"
          :sub="`充裕 < 50% · 适中 50%~80% · 高位 ≥ 80% · 共 ${data?.distribution.reduce((s, r) => s + r.value, 0) ?? 0} 个资源`"
          tip="按窗口**平均**承载率归档。与 KPI 里的「高负荷资源」是同一阈值。"
          :is-empty="!(data?.distribution.length)"
        >
          <EChart :option="distOpt" height="320px" />
        </ChartCard>
      </div>

      <!-- 第二层：趋势 + 区县分布 -->
      <div class="pg__grid pg__grid--mid">
        <ChartCard
          tone="trend"
          title="到访趋势"
          :sub="`逐日到访人次 · ${data?.trend.length ?? 0} 个数据点（不足 7 天时按 7 天窗口展示）`"
          tip="趋势点数下限 7 —— 与驾驶舱同一约定，避免「今日」档只有一根柱看不出走势。"
          :is-empty="!(data?.trend.length)"
        >
          <EChart :option="trendOpt" height="230px" />
        </ChartCard>

        <ChartCard
          tone="main"
          title="区县分布"
          :sub="`按区县聚合的窗口到访 · ${data?.districts.length ?? 0} 个区县`"
          tip="区县口径，与左侧趋势、主图的资源口径都不同 —— 它是「客流落在哪片地界上」。"
          :is-empty="!(data?.districts.length)"
        >
          <EChart :option="districtOpt" height="230px" />
        </ChartCard>
      </div>

      <!-- 第三层：只有景点页（主图是散点）才补一张排行，餐饮/住宿的主图已经是排行 -->
      <ChartCard
        v-if="props.hero === 'scatter'"
        tone="main"
        title="资源热度排行"
        :sub="`全部 ${count} 个资源点 · 窗口内到访人次`"
        tip="与上面的散点同源，但看的是绝对量：散点看「效率」，排行看「体量」。"
        :is-empty="!count"
      >
        <EChart :option="rankOpt" height="260px" @click="onPointClick" />
      </ChartCard>
    </template>

    <!-- ==================== 资源管理 ==================== -->
    <section class="pg__sec">
      <header class="pg__sec-head">
        <div>
          <h2 class="h3">{{ noun }}管理</h2>
          <p class="pg__sec-sub">
            下架优先于删除 —— 被订单、足迹或体验引用的资源不允许物理删除，以免留下悬空引用。
            来自城市数据包的资源，点「编辑」会先接管为运营维护（不可逆）；「删除」对数据包资源禁用。
          </p>
        </div>
      </header>

      <Transition name="notice">
        <div v-if="list.notice.value" class="notice" :class="`notice--${list.notice.value.type}`">
          {{ list.notice.value.text }}
        </div>
      </Transition>

      <ResourceToolbar
        v-model:keyword="list.keyword.value"
        v-model:status="list.statusFilter.value"
        :noun="noun"
        :busy="list.busy.value"
        :loading="list.loading.value"
        @search="list.reload"
        @create="formRef?.openFor(null)"
      />

      <div v-if="list.loading.value" class="stack-3">
        <div v-for="i in 5" :key="i" class="skeleton" style="height: 56px; border-radius: 10px" />
      </div>
      <ResourceTable
        v-else
        :rows="list.rows.value"
        :is-poi="true"
        :noun="noun"
        :busy="list.busy.value"
        :highlight-id="highlightId"
        :empty-hint="
          list.keyword.value || list.statusFilter.value
            ? '换个关键词或状态试试'
            : '点右上角「新增」建一条'
        "
        :can-edit="list.canEdit"
        :edit-hint="list.editHint"
        :remove-hint="list.removeHint"
        @edit="formRef?.openFor($event)"
        @toggle="list.toggleStatus"
        @images="imgRef?.openFor($event)"
        @remove="list.remove"
      />
    </section>

    <!-- 页面专属的补充区块。景点页用它挂「景点评论」—— 评论属于景点这条业务线，
         但它只在景点页出现，所以不做进本组件（餐饮/住宿没有评论明细可管） -->
    <slot name="after" />

    <ResourceFormDrawer
      ref="formRef"
      :is-poi="true"
      :default-biz-type="defaultBizType"
      :biz-options="bizOptions"
      :busy="list.busy.value"
      @save="onSave"
    />
    <ResourceImagesDrawer ref="imgRef" />
  </div>
</template>

<style scoped>
.pg {
  max-width: 1400px;
}

.pg__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-5);
  margin-bottom: var(--sp-4);
}
.pg__title {
  margin-top: var(--sp-2);
}
.pg__sub {
  margin-top: var(--sp-2);
  max-width: 820px;
  font-size: var(--fs-sm);
  line-height: 1.85;
  color: var(--text-2);
}
.pg__meta {
  flex: none;
  padding-top: var(--sp-3);
}

.pg__bar {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
  margin-bottom: var(--sp-4);
}
.pg__busy {
  font-size: var(--fs-cap);
  color: var(--gold-300);
}

/* ---------- 分段控件（核心景区 / 乡村景点） ---------- */
.seg {
  display: inline-flex;
  padding: 3px;
  border: 1px solid var(--line-strong);
  border-radius: var(--r-md);
  background: rgba(24, 59, 48, 0.7);
}
.seg__btn {
  padding: 6px 16px;
  font-size: var(--fs-xs);
  color: var(--text-2);
  border-radius: var(--r-sm);
  transition: all var(--dur-1) var(--ease);
}
.seg__btn:hover {
  color: #fff;
}
.seg__btn--on {
  color: #fff;
  background: var(--brand-500);
  font-weight: 600;
}

/* ---------- 栅格 ---------- */
.pg__grid {
  display: grid;
  gap: var(--sp-4);
  margin-bottom: var(--sp-4);
}
.pg__grid--top,
.pg__grid--mid {
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
}
@media (max-width: 1100px) {
  .pg__grid--top,
  .pg__grid--mid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.pg__sec {
  margin-top: var(--sp-6);
}
.pg__sec-head {
  margin-bottom: var(--sp-4);
}
.pg__sec-sub {
  margin-top: 6px;
  max-width: 860px;
  font-size: var(--fs-cap);
  line-height: 1.8;
  color: var(--text-3);
}
</style>
