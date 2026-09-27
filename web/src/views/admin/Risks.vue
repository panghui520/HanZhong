<script setup lang="ts">
/**
 * 风险与工单（M5 运营端）
 *
 * ============================================================
 * 这一页在讲什么
 * ============================================================
 * 规则引擎读 `poi_visit_stats`（客流与经营日度统计），按 `risk_rule` 里
 * **可调的阈值**判出风险事件；运营在这里把它变成一张工单、指派、处置、闭环。
 * 判定全程是确定性的 —— 没有一步经过模型，所以这里不写"AI"。
 *
 * ============================================================
 * 两个列表为什么不做成"风险 → 工单"的层级
 * ============================================================
 * 它们**不是一对一的从属关系**，而是同一件事的两个阶段：
 *   · 风险事件：规则"发现了什么"。没建单的、建了单的、已闭环的都在这里，
 *     因为运营要能回看"上周那批超载后来怎么处理的"。
 *   · 工单：运营"做了什么"。只有建过单的才在这里，带处置人与反馈。
 * 做成树（风险下挂工单）会让"只看工单"变成要展开每一行才能扫一遍，
 * 而处置中的单才是这一页真正要盯的东西。
 *
 * ============================================================
 * 筛选放在客户端
 * ============================================================
 * 与 M6 订单页同一个理由：标签上要显示**各状态的数量**，
 * 按状态逐个请求就得发好几次才能凑齐角标。风险事件是几十条量级，
 * 一次全量拉回来在内存里分组更划算。
 *
 * ============================================================
 * 写操作之后一律重新拉取，不在本地改状态
 * ============================================================
 * 状态由服务端说了算（`status` 与 `work_order_id` 的联动在
 * `OpsServiceImpl` 里）。本地改一份，迟早和下一次刷新打架，
 * 而那种不一致看起来像"页面抽风"，很难复现。
 */
import { computed, onMounted, ref } from 'vue'
import { createWorkOrder, getRisks, getWorkOrders, rescanRisks, updateWorkOrder } from '@/api/ops'
import {
  createNoticeDraft,
  getAdminDiversionNotices,
  updateDiversionNotice,
} from '@/api/diversion'
import { ApiError } from '@/api/http'
import { useNotice } from '@/composables/useNotice'
import { reloadDiversionNotices } from '@/composables/useDiversionNotices'
import { useSessionStore } from '@/stores/session'
import { when } from '@/utils/format'
import {
  NOTICE_STATUS_LABEL,
  RISK_STATUS_LABEL,
  RISK_TYPE_LABEL,
  WORK_ORDER_STATUS_LABEL,
  type DiversionNotice,
  type RiskEvent,
  type RiskLevel,
  type RiskStatus,
  type WorkOrder,
  type WorkOrderStatus,
} from '@/types'

const session = useSessionStore()
const { notice, say } = useNotice()

const tab = ref<'risks' | 'orders' | 'notices'>('risks')
const risks = ref<RiskEvent[]>([])
const orders = ref<WorkOrder[]>([])
const notices = ref<DiversionNotice[]>([])
const loading = ref(true)
/** 正在提交的那一条（风险 id 或工单 id）。非空时禁用所有写按钮 */
const busyId = ref<number | null>(null)
const scanning = ref(false)

// ---- 筛选（客户端，见文件头） ----
const levelFilter = ref<'ALL' | RiskLevel>('ALL')
const statusFilter = ref<'ALL' | RiskStatus>('ALL')

const filteredRisks = computed(() =>
  risks.value.filter(
    (r) =>
      (levelFilter.value === 'ALL' || r.level === levelFilter.value) &&
      (statusFilter.value === 'ALL' || r.status === statusFilter.value)
  )
)

const countByStatus = computed(() => {
  const m: Record<string, number> = { ALL: risks.value.length, OPEN: 0, HANDLED: 0, CLOSED: 0 }
  for (const r of risks.value) m[r.status] = (m[r.status] ?? 0) + 1
  return m
})

/**
 * 拉取三个列表。
 *
 * 一起拉而不是各拉各的：处置完一条工单，风险的 `status` 也会变
 * （`CLOSED`），只刷工单列表的话风险页会停在旧状态上。
 * 公告同理 —— 它挂在风险事件上，风险状态变了这一页的三块都要跟着变。
 * 三个接口都是几十条量级，多两次往返无所谓。
 */
async function load() {
  loading.value = true
  try {
    const [r, w, n] = await Promise.all([
      getRisks(),
      getWorkOrders(),
      getAdminDiversionNotices(),
    ])
    risks.value = r
    orders.value = w
    notices.value = n
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '数据加载失败')
    risks.value = []
    orders.value = []
    notices.value = []
  } finally {
    loading.value = false
  }
}
onMounted(load)

/**
 * 手动跑一次规则扫描。
 *
 * 服务启动时已经自动扫过一次（见 `OpsScanRunner`），这个按钮是给
 * "改了 `risk_rule` 的阈值、现在就想看结果"用的 —— 不用重启服务。
 * 幂等，重复点不会产生重复事件。
 */
async function scan() {
  if (scanning.value) return
  scanning.value = true
  try {
    const hit = await rescanRisks()
    say('ok', `扫描完成，命中 ${hit} 条风险事件`)
    await load()
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '扫描失败')
  } finally {
    scanning.value = false
  }
}

/* ============================================================
   风险 → 工单
   ============================================================ */

/**
 * 为一条风险建单。
 *
 * 只有 `OPEN` 的才给按钮：已建单的再点一次，后端会回 8003，
 * 用户看到的是一个报错 —— 而界面上本来就看得出"这条已经建过了"。
 * 把不可能成功的操作藏起来，比让它失败再解释更好。
 */
async function makeOrder(r: RiskEvent) {
  if (busyId.value !== null) return
  busyId.value = r.id
  try {
    const wo = await createWorkOrder(r.id)
    say('ok', `已建单 ${wo.code} · ${wo.risk_poi_name}`)
    await load()
    // 建完直接切到工单页：下一步动作（指派 / 处置）在那里
    tab.value = 'orders'
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '建单失败')
  } finally {
    busyId.value = null
  }
}

/* ============================================================
   工单处置
   ============================================================ */

/** 正在展开处置表单的工单 id */
const formFor = ref<number | null>(null)
const form = ref({ assignee: '', result: '' })
const formError = ref('')

function openForm(w: WorkOrder) {
  formFor.value = formFor.value === w.id ? null : w.id
  formError.value = ''
  // 处置人默认填当前登录的运营账号：绝大多数情况下就是本人，
  // 默认填上比让人每次手打一遍更省事（也可以改成别人，是自由文本）
  form.value = { assignee: w.assignee || session.displayName, result: '' }
}

/** 置为处置中（可同时指派处置人） */
async function submitProcessing(w: WorkOrder) {
  if (busyId.value !== null) return
  busyId.value = w.id
  formError.value = ''
  try {
    await updateWorkOrder(w.id, { status: 'PROCESSING', assignee: form.value.assignee.trim() })
    say('ok', `${w.code} 已置为处置中`)
    formFor.value = null
    await load()
  } catch (e) {
    formError.value = e instanceof ApiError ? e.message : '操作失败'
  } finally {
    busyId.value = null
  }
}

/**
 * 完结并提交反馈。
 *
 * 反馈在客户端先拦一道空值，而不是直接让后端回 8005：
 * 后端那道**必须保留**（接口不是只有这一页在调），但在这里先说清楚
 * 能省掉一次往返，也让"为什么不能提交"紧挨着输入框。
 */
async function submitDone(w: WorkOrder) {
  if (busyId.value !== null) return
  const result = form.value.result.trim()
  if (!result) {
    formError.value = '完结前请填写处置反馈：说不出做了什么，等于没有处置记录'
    return
  }
  busyId.value = w.id
  formError.value = ''
  try {
    await updateWorkOrder(w.id, {
      status: 'DONE',
      assignee: form.value.assignee.trim(),
      result,
    })
    say('ok', `${w.code} 已完结`)
    formFor.value = null
    await load()
  } catch (e) {
    formError.value = e instanceof ApiError ? e.message : '操作失败'
  } finally {
    busyId.value = null
  }
}

/* ============================================================
   分流公告（M5 续）
   ============================================================ */

/**
 * 这条风险的分流方案。
 *
 * 后端只给处置动作为 `DIVERSION` 的规则算候选（其余规则没有"换一个去处"
 * 的语义，返回空数组）。所以**空数组就是"这条不涉及分流"**，
 * 界面据此决定要不要显示方案块 —— 不需要前端再判一次规则类型。
 */
function planOf(r: RiskEvent) {
  return r.candidates ?? []
}

/** 这条风险已经生成过的公告（一条事件最多一条，所以直接 find） */
function noticeOf(r: RiskEvent) {
  return notices.value.find((n) => n.risk_event_id === r.id)
}

/**
 * 生成公告草稿。
 *
 * 不传文案，让后端按候选自动生成 —— 后端那句默认文案里带"演示用仿真数据"，
 * 前端自己拼一份容易漏掉这句。运营想改文案，生成之后在「分流公告」页里改。
 *
 * 生成完**不自动发布**：公告是发给游客看的，发出去之前得有人看一眼。
 */
async function makeNotice(r: RiskEvent) {
  if (busyId.value !== null) return
  busyId.value = r.id
  try {
    const n = await createNoticeDraft(r.id)
    say('ok', `已生成公告草稿 ${n.code}，可在「分流公告」里改文案并发布`)
    await load()
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '生成公告失败')
  } finally {
    busyId.value = null
  }
}

/** 正在编辑文案的公告 id */
const noticeFormFor = ref<number | null>(null)
const noticeForm = ref({ title: '', message: '' })

function openNoticeForm(n: DiversionNotice) {
  noticeFormFor.value = noticeFormFor.value === n.id ? null : n.id
  noticeForm.value = { title: n.title, message: n.message }
}

/** 保存文案。只改文案不动状态 —— 改完还是草稿，得再点发布 */
async function saveNotice(n: DiversionNotice) {
  if (busyId.value !== null) return
  const title = noticeForm.value.title.trim()
  if (!title) {
    say('err', '公告标题不能为空')
    return
  }
  busyId.value = n.id
  try {
    await updateDiversionNotice(n.id, { title, message: noticeForm.value.message.trim() })
    say('ok', `${n.code} 文案已保存`)
    noticeFormFor.value = null
    await load()
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '保存失败')
  } finally {
    busyId.value = null
  }
}

/**
 * 发布 / 撤下。
 *
 * 发布后**必须让游客端那份缓存失效**（`reloadDiversionNotices`）：
 * 那个缓存是模块级的，会在路由切换间活下来。不清的话，运营发布完切到首页
 * 看到的还是发布前那一版 —— 看起来像"发布没生效"，而实际上首页刷新一下就有了。
 */
async function setNoticeStatus(n: DiversionNotice, status: 'PUBLISHED' | 'WITHDRAWN') {
  if (busyId.value !== null) return
  busyId.value = n.id
  try {
    await updateDiversionNotice(n.id, {
      status,
      published_by: status === 'PUBLISHED' ? session.displayName : undefined,
    })
    say('ok', status === 'PUBLISHED' ? `${n.code} 已发布到游客端` : `${n.code} 已撤下`)
    await load()
    void reloadDiversionNotices()
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '操作失败')
  } finally {
    busyId.value = null
  }
}

/** 公告状态 → 徽标配色 */
function noticeStatusTone(s: string) {
  if (s === 'PUBLISHED') return 'ok'
  if (s === 'DRAFT') return 'warn'
  return 'mute'
}

/** 待处理的公告数（草稿 + 已发布），给标签角标用 */
const pendingNotices = computed(
  () => notices.value.filter((n) => n.status === 'DRAFT' || n.status === 'PUBLISHED').length
)

/* ============================================================
   展示辅助
   ============================================================ */

const LEVEL_LABEL: Record<string, string> = { HIGH: '高', MID: '中' }

/** 等级 → 卡片左侧的色条与徽标配色 */
function levelTone(l: string) {
  return l === 'HIGH' ? 'danger' : 'warn'
}

/** 风险状态 → 徽标配色。已闭环的用中性色，不抢注意力 */
function riskStatusTone(s: RiskStatus) {
  return s === 'OPEN' ? 'warn' : s === 'HANDLED' ? 'info' : 'mute'
}

function orderStatusTone(s: WorkOrderStatus) {
  return s === 'DONE' ? 'ok' : s === 'PROCESSING' ? 'info' : 'warn'
}

/** 承载占用率文案。**读不到给"—"**，不要显示成 0%（"不知道"不是"很空"） */
function pct(u?: number) {
  return u == null ? '—' : `${Math.round(u * 100)}%`
}

/** 距离文案。km 保留一位小数 —— 与公告正文里的写法保持一致，两处不一样会被当成 bug */
function kmText(km: number) {
  return `${km.toFixed(1)} km`
}

/** 指标与阈值带单位地写出来。承载率这类比值显示成百分比更好懂 */
function metricText(r: RiskEvent) {
  const v = Number(r.metric_value)
  const t = Number(r.threshold)
  // 承载率 / 占比 / 倍数都可能是 >1 或 <1 的小数。只有 OVERLOAD 与
  // RURAL_IDLE 是"比率"口径（阈值 0.8 / 1.0），其余是比值或倍数，
  // 直接写数字更准确 —— 把 2.1 倍客流写成 "210%" 会让人以为是占比。
  if (r.rule_id === 'OVERLOAD' || r.rule_id === 'RURAL_IDLE') {
    return `承载 ${Math.round(v * 100)}% / 阈值 ${Math.round(t * 100)}%`
  }
  return `指标 ${v} / 阈值 ${t}`
}
</script>

<template>
  <div class="arisk">
    <header class="arisk__head">
      <div>
        <span class="eyebrow">M5 · 承载力与乡村分流</span>
        <h1 class="h1">风险与工单</h1>
        <p class="muted arisk__sub">
          规则引擎按 <code>risk_rule</code> 的阈值扫描客流与经营统计，命中后落成风险事件；
          建单、指派、处置、闭环都在这一页。
        </p>
      </div>
      <div class="arisk__meta">
        <span class="badge-sim">仿真数据</span>
        <button class="btn btn-ghost btn-sm" :disabled="scanning" @click="scan">
          {{ scanning ? '扫描中…' : '重新扫描' }}
        </button>
      </div>
    </header>

    <Transition name="notice">
      <div v-if="notice" class="notice" :class="`notice--${notice.type}`">{{ notice.text }}</div>
    </Transition>

    <div class="tabs">
      <button class="tab" :class="{ 'tab--on': tab === 'risks' }" @click="tab = 'risks'">
        风险事件<span class="tab__n">{{ risks.length }}</span>
      </button>
      <button class="tab" :class="{ 'tab--on': tab === 'orders' }" @click="tab = 'orders'">
        工单<span class="tab__n">{{ orders.length }}</span>
      </button>
      <!--
        公告与风险同页，不做成独立导航项：它是"风险处置"的最后一步 ——
        运营在这里看到方案、生成公告、发出去，一条线走完。
        拆成两页会让"生成完草稿去哪了"变成一次页面跳转。
      -->
      <button class="tab" :class="{ 'tab--on': tab === 'notices' }" @click="tab = 'notices'">
        分流公告<span class="tab__n">{{ pendingNotices }}</span>
      </button>
    </div>

    <div v-if="loading" class="stack-4">
      <div v-for="i in 3" :key="i" class="skeleton" style="height: 180px; border-radius: 10px" />
    </div>

    <!-- ==================== 风险事件 ==================== -->
    <template v-else-if="tab === 'risks'">
      <div class="filters">
        <span class="filters__label">等级</span>
        <button
          v-for="l in ['ALL', 'HIGH', 'MID'] as const"
          :key="l"
          class="chip"
          :class="{ 'chip--on': levelFilter === l }"
          @click="levelFilter = l"
        >
          {{ l === 'ALL' ? '全部' : LEVEL_LABEL[l] }}
        </button>
        <span class="filters__label filters__label--gap">状态</span>
        <button
          v-for="s in ['ALL', 'OPEN', 'HANDLED', 'CLOSED'] as const"
          :key="s"
          class="chip"
          :class="{ 'chip--on': statusFilter === s }"
          @click="statusFilter = s"
        >
          {{ s === 'ALL' ? '全部' : RISK_STATUS_LABEL[s] }}
          <span class="chip__n">{{ countByStatus[s] ?? 0 }}</span>
        </button>
      </div>

      <div v-if="!filteredRisks.length" class="empty">
        <div class="empty__title">当前筛选下没有风险事件</div>
        <div class="empty__desc">
          换一组筛选条件，或点右上角「重新扫描」按当前阈值重跑一次规则引擎。
        </div>
      </div>

      <div v-else class="rlist">
        <article
          v-for="r in filteredRisks"
          :key="r.id"
          class="rcard"
          :class="`rcard--${levelTone(r.level)}`"
        >
          <header class="rcard__head">
            <span class="lv" :class="`lv--${levelTone(r.level)}`">{{ LEVEL_LABEL[r.level] }}</span>
            <span class="rcard__title">{{ r.title }}</span>
            <span class="rcard__poi">{{ r.poi_name }}</span>
            <span class="tag tag-gold">{{ RISK_TYPE_LABEL[r.type] ?? r.type }}</span>
            <span class="st" :class="`st--${riskStatusTone(r.status)}`">
              {{ RISK_STATUS_LABEL[r.status] }}
            </span>
          </header>

          <p class="rcard__detail">{{ r.detail }}</p>
          <p class="rcard__sug"><b>建议</b>{{ r.suggestion }}</p>

          <!--
            分流方案（M5 续）。**这是本轮补的那个缺口** ——
            在此之前 `r.suggestion` 只有一句规则模板文案
            （"同步向入园游客推送周边乡村替代方案"），一个具体点位名都没有，
            运营打开这条事件得自己想"到底往哪分流"。
            现在方案由后端的 `DiversionAdvisor` 算好带过来，
            运营的工作从"自己想"变成"审一个方案"，这是质变。

            排序口径：**承载未过半的在前，然后按距离升序**；
            乡村整组排在景区之前。所以列表读出来就是
            "先最近的、还接得住的乡村，再最近的、还接得住的景区"。

            只有处置动作为 DIVERSION 的规则才有方案（后端只给这两类算），
            空数组就是"这条不涉及分流"。
          -->
          <div v-if="planOf(r).length" class="plan">
            <div class="plan__head">
              <span class="plan__label">分流方案 · 规则引擎计算</span>
              <span class="plan__hint">就近乡村优先，其次就近景区</span>
            </div>
            <ul class="plan__list">
              <li v-for="(c, i) in planOf(r)" :key="c.poi_id" class="plan__item">
                <span class="plan__no">{{ i + 1 }}</span>
                <span class="plan__name">{{ c.name }}</span>
                <span
                  class="tag"
                  :class="c.business_type === 'RURAL_SPOT' ? 'tag-brand' : 'tag-tech'"
                >
                  {{ c.business_type === 'RURAL_SPOT' ? '乡村' : '景区' }}
                </span>
                <span class="num plan__num">{{ kmText(c.km) }}</span>
                <span class="num plan__num">承载 {{ pct(c.current_usage) }}</span>
                <span class="plan__reason">{{ c.reason }}</span>
              </li>
            </ul>
          </div>

          <footer class="rcard__foot">
            <span class="muted small">
              {{ r.district ? `${r.district} · ` : '' }}{{ r.stat_date }} · 规则
              <code>{{ r.rule_id }}</code> · {{ metricText(r) }}
            </span>
            <button
              v-if="r.status === 'OPEN'"
              class="btn btn-gold btn-sm"
              :disabled="busyId !== null"
              @click="makeOrder(r)"
            >
              {{ busyId === r.id ? '建单中…' : '建单' }}
            </button>
            <span v-else class="muted small">已关联工单 #{{ r.work_order_id }}</span>

            <!--
              生成公告草稿。只有有方案的事件才给按钮 ——
              一条没有候选的事件点了只会拿到 8013，把不可能成功的操作藏起来，
              比让它失败再解释更好（与"建单"按钮同一条理由）。
              已经生成过的换成状态文字：再点一次是 8011。
            -->
            <button
              v-if="planOf(r).length && !noticeOf(r)"
              class="btn btn-ghost btn-sm"
              :disabled="busyId !== null"
              @click="makeNotice(r)"
            >
              {{ busyId === r.id ? '生成中…' : '生成公告草稿' }}
            </button>
            <span v-else-if="noticeOf(r)" class="muted small">
              已生成公告 <code>{{ noticeOf(r)?.code }}</code>
              {{ NOTICE_STATUS_LABEL[noticeOf(r)?.status ?? 'DRAFT'] }}
            </span>
          </footer>
        </article>
      </div>
    </template>

    <!-- ==================== 工单 ==================== -->
    <!--
      这里**刻意不写 `v-else`**：下面还有一支「分流公告」。`v-else` 必须是最后
      一支，写成 v-else 再跟 v-else-if 会直接编译失败（整个页面白屏）。
      三支都用显式条件，谁先谁后都不影响渲染。
    -->
    <template v-else-if="tab === 'orders'">
      <div v-if="!orders.length" class="empty">
        <div class="empty__title">还没有工单</div>
        <div class="empty__desc">
          在「风险事件」里对某一条点「建单」，它就会出现在这里。
        </div>
      </div>

      <div v-else class="wlist">
        <article
          v-for="w in orders"
          :key="w.id"
          class="wcard"
          :class="`wcard--${levelTone(w.level)}`"
        >
          <header class="wcard__head">
            <span class="num wcard__code">{{ w.code }}</span>
            <span class="lv" :class="`lv--${levelTone(w.level)}`">{{ LEVEL_LABEL[w.level] }}</span>
            <span class="wcard__title">{{ w.title }} · {{ w.risk_poi_name }}</span>
            <span class="st" :class="`st--${orderStatusTone(w.status)}`">
              {{ WORK_ORDER_STATUS_LABEL[w.status] }}
            </span>
          </header>

          <p class="muted small wcard__meta">
            {{ RISK_TYPE_LABEL[w.type] ?? w.type }} · 来源事件 #{{ w.risk_event_id }} ·
            {{ w.risk_stat_date }}
          </p>
          <p class="wcard__sug"><b>建议</b>{{ w.suggestion }}</p>

          <!-- 已完结：把处置记录摆出来，不再给操作按钮 -->
          <div v-if="w.status === 'DONE'" class="wcard__done">
            <p><b>处置人</b>{{ w.assignee || '未指派' }}</p>
            <p><b>处置反馈</b>{{ w.result }}</p>
            <p class="muted small">完结于 {{ when(w.handled_at) }}</p>
          </div>

          <footer v-else class="wcard__foot">
            <span class="muted small">
              处置人：{{ w.assignee || '待认领' }} · 建于 {{ when(w.created_at) }}
            </span>
            <button class="btn btn-ghost btn-sm" @click="openForm(w)">
              {{ formFor === w.id ? '收起' : '处置' }}
            </button>
          </footer>

          <!-- 展开的处置表单 -->
          <div v-if="formFor === w.id && w.status !== 'DONE'" class="form">
            <div class="form__f">
              <label class="form__label" :for="`as-${w.id}`">处置人</label>
              <input
                :id="`as-${w.id}`"
                v-model="form.assignee"
                class="form__input"
                type="text"
                maxlength="32"
                placeholder="如：张工"
              />
            </div>
            <label class="form__label" :for="`rs-${w.id}`">
              处置反馈{{ w.status === 'PROCESSING' ? '（完结时必填，会记进工单）' : '（可空）' }}
            </label>
            <textarea
              :id="`rs-${w.id}`"
              v-model="form.result"
              class="form__ta"
              rows="2"
              maxlength="200"
              placeholder="如：已启动分时预约，向龙湾村定向导流 420 人"
            />
            <p v-if="formError" class="form__err">{{ formError }}</p>
            <div class="form__acts">
              <button
                v-if="w.status === 'PENDING'"
                class="btn btn-ghost btn-sm"
                :disabled="busyId !== null"
                @click="submitProcessing(w)"
              >
                认领并置处置中
              </button>
              <button
                class="btn btn-gold btn-sm"
                :disabled="busyId !== null"
                @click="submitDone(w)"
              >
                {{ busyId === w.id ? '提交中…' : '完结并提交反馈' }}
              </button>
              <button class="btn btn-ghost btn-sm" @click="formFor = null">取消</button>
            </div>
          </div>
        </article>
      </div>
    </template>

    <!-- ==================== 分流公告 ==================== -->
    <template v-else-if="tab === 'notices'">
      <div v-if="!notices.length" class="empty">
        <div class="empty__title">还没有分流公告</div>
        <div class="empty__desc">
          到「风险事件」里找一条带分流方案的事件，点「生成公告草稿」。
          公告**要人工发布**才会出现在游客端 —— 承载率来自仿真数据，
          让系统自动对游客喊话，出错时没人拦得住。
        </div>
      </div>

      <div v-else class="nlist">
        <article
          v-for="n in notices"
          :key="n.id"
          class="ncard"
          :class="`ncard--${noticeStatusTone(n.status)}`"
        >
          <header class="ncard__head">
            <span class="st" :class="`st--${noticeStatusTone(n.status)}`">
              {{ NOTICE_STATUS_LABEL[n.status] }}
            </span>
            <span class="ncard__title">{{ n.title }}</span>
            <code class="ncard__code">{{ n.code }}</code>
          </header>

          <p class="ncard__msg">{{ n.message }}</p>

          <!--
            候选两组数都写出来：`推荐时` 是发布那一刻的快照（永远不变），
            `当前` 是打开这一刻重算的。只写一个都是不诚实的 ——
            只写快照等于拿旧数据骗游客，只写当前就答不出"当时为什么推荐它"。
          -->
          <ul class="plan__list plan__list--tight">
            <li v-for="(c, i) in n.candidates" :key="c.poi_id" class="plan__item">
              <span class="plan__no">{{ i + 1 }}</span>
              <span class="plan__name">{{ c.name }}</span>
              <span
                class="tag"
                :class="c.business_type === 'RURAL_SPOT' ? 'tag-brand' : 'tag-tech'"
              >
                {{ c.business_type === 'RURAL_SPOT' ? '乡村' : '景区' }}
              </span>
              <span class="num plan__num">{{ kmText(c.km) }}</span>
              <span class="num plan__num" :class="{ 'is-off': c.available === false }">
                当前 {{ pct(c.current_usage) }}
              </span>
              <span class="plan__reason">
                推荐时 {{ pct(c.usage) }}
                <template v-if="c.available === false"> · 现已不宽裕</template>
              </span>
            </li>
          </ul>

          <p class="ncard__meta">
            来源 {{ n.from_poi_name }}{{ n.district ? ` · ${n.district}` : '' }} · 失效
            {{ when(n.expire_at) }}
            <template v-if="n.published_by"> · 发布人 {{ n.published_by }}</template>
            · 当前可去 <b>{{ n.available_count }}</b> 处
          </p>

          <!-- 改文案。候选不能改 —— 那是算出来的，改了就说不清依据 -->
          <div v-if="noticeFormFor === n.id" class="form">
            <div class="form__f">
              <label class="form__label" :for="`nt-${n.id}`">标题</label>
              <input
                :id="`nt-${n.id}`"
                v-model="noticeForm.title"
                class="form__input"
                type="text"
                maxlength="128"
              />
            </div>
            <label class="form__label" :for="`nm-${n.id}`">
              正文（会原样显示在首页，请保留"仿真数据"字样）
            </label>
            <textarea
              :id="`nm-${n.id}`"
              v-model="noticeForm.message"
              class="form__ta"
              rows="3"
              maxlength="512"
            />
            <div class="form__acts">
              <button
                class="btn btn-gold btn-sm"
                :disabled="busyId !== null"
                @click="saveNotice(n)"
              >
                {{ busyId === n.id ? '保存中…' : '保存文案' }}
              </button>
              <button class="btn btn-ghost btn-sm" @click="noticeFormFor = null">取消</button>
            </div>
          </div>

          <footer class="ncard__foot">
            <button
              class="btn btn-ghost btn-sm"
              :disabled="busyId !== null"
              @click="openNoticeForm(n)"
            >
              {{ noticeFormFor === n.id ? '收起编辑' : '改文案' }}
            </button>
            <button
              v-if="n.status === 'DRAFT' || n.status === 'WITHDRAWN'"
              class="btn btn-primary btn-sm"
              :disabled="busyId !== null"
              @click="setNoticeStatus(n, 'PUBLISHED')"
            >
              {{ busyId === n.id ? '提交中…' : '发布到游客端' }}
            </button>
            <button
              v-if="n.status === 'PUBLISHED'"
              class="btn btn-ghost btn-sm"
              :disabled="busyId !== null"
              @click="setNoticeStatus(n, 'WITHDRAWN')"
            >
              撤下
            </button>
            <!--
              过期的不给"重新发布"按钮，只给一句出路说明：
              它的候选快照是过期那天算的，直接发出去等于拿旧方案指导今天的行程。
              后端会回 8015，而那句提示语写的就是"请重新生成" ——
              界面上直接把这句话接上，比让人点一次再看报错好。
            -->
            <span v-if="n.status === 'EXPIRED'" class="muted small">
              已过期，不能再直接发布；请到「风险事件」里重新生成一条（会顶替这一条）
            </span>
          </footer>
        </article>
      </div>
    </template>
  </div>
</template>

<style scoped>
.arisk__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-4);
  margin-bottom: var(--sp-5);
}
.arisk__sub {
  margin-top: var(--sp-2);
  font-size: var(--fs-sm);
  max-width: 62ch;
}
.arisk__meta {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-shrink: 0;
}

/* ---------- 提示条（结构由 useNotice 约定，样式各页自己写） ---------- */
.notice {
  margin-bottom: var(--sp-4);
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-md);
  font-size: var(--fs-sm);
  border: 1px solid transparent;
}
.notice--ok {
  color: #cfe8dc;
  background: rgba(42, 111, 91, 0.24);
  border-color: rgba(113, 169, 150, 0.42);
}
.notice--err {
  color: #f3d3ca;
  background: rgba(168, 64, 43, 0.22);
  border-color: rgba(168, 64, 43, 0.5);
}
.notice-enter-active,
.notice-leave-active {
  transition: opacity var(--dur-2) var(--ease);
}
.notice-enter-from,
.notice-leave-to {
  opacity: 0;
}

/* ---------- 标签页 ---------- */
.tabs {
  display: flex;
  gap: var(--sp-2);
  border-bottom: 1px solid var(--line);
  margin-bottom: var(--sp-5);
}
.tab {
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--ink-500);
  font-size: var(--fs-sm);
  font-weight: 600;
  padding: var(--sp-3) var(--sp-3);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: color var(--dur-1) var(--ease);
}
.tab:hover {
  color: var(--ink-900);
}
.tab--on {
  color: var(--ink-900);
  border-bottom-color: var(--brand-500);
}
.tab__n {
  font-size: var(--fs-xs);
  padding: 1px 7px;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, 0.08);
}

/* ---------- 筛选 ---------- */
.filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin-bottom: var(--sp-4);
}
.filters__label {
  font-size: var(--fs-xs);
  color: var(--ink-500);
  margin-right: 2px;
}
.filters__label--gap {
  margin-left: var(--sp-4);
}
.chip {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--line);
  color: var(--ink-500);
  border-radius: var(--r-pill);
  font-size: var(--fs-xs);
  padding: 4px 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: all var(--dur-1) var(--ease);
}
.chip:hover {
  color: var(--ink-900);
  border-color: var(--line-strong);
}
.chip--on {
  color: #eaf6f1;
  background: rgba(42, 111, 91, 0.4);
  border-color: rgba(113, 169, 150, 0.6);
}
.chip__n {
  opacity: 0.7;
}

/* ---------- 列表 ---------- */
.rlist,
.wlist {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.rcard,
.wcard {
  position: relative;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  padding: var(--sp-4) var(--sp-4) var(--sp-4) calc(var(--sp-4) + 4px);
  overflow: hidden;
}
/* 左侧色条承担"一眼看出轻重"的职责，比在标题里塞一个红色小圆点更省眼力 */
.rcard::before,
.wcard::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
}
.rcard--danger::before,
.wcard--danger::before {
  background: var(--danger);
}
.rcard--warn::before,
.wcard--warn::before {
  background: var(--warn);
}

.rcard__head,
.wcard__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}
.rcard__title,
.wcard__title {
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 700;
  color: var(--ink-900);
}
.rcard__poi {
  font-size: var(--fs-sm);
  color: var(--ink-700);
}
.wcard__code {
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--ink-700);
}

.lv {
  width: 20px;
  height: 20px;
  border-radius: var(--r-sm);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: var(--fs-xs);
  font-weight: 700;
  flex-shrink: 0;
}
.lv--danger {
  color: #fdeeea;
  background: rgba(168, 64, 43, 0.75);
}
.lv--warn {
  color: #fdf5e4;
  background: rgba(168, 121, 29, 0.75);
}

.st {
  margin-left: auto;
  font-size: var(--fs-xs);
  padding: 3px 10px;
  border-radius: var(--r-pill);
  border: 1px solid transparent;
  white-space: nowrap;
}
.st--warn {
  color: #f0d7a6;
  background: rgba(168, 121, 29, 0.18);
  border-color: rgba(168, 121, 29, 0.42);
}
.st--info {
  color: #bcd6ec;
  background: rgba(46, 123, 196, 0.18);
  border-color: rgba(46, 123, 196, 0.42);
}
.st--ok {
  color: #cfe8dc;
  background: rgba(42, 111, 91, 0.22);
  border-color: rgba(113, 169, 150, 0.42);
}
.st--mute {
  color: var(--ink-500);
  background: rgba(255, 255, 255, 0.04);
  border-color: var(--line);
}

.rcard__detail {
  font-size: var(--fs-sm);
  color: var(--ink-700);
  margin-bottom: var(--sp-2);
}
.rcard__sug,
.wcard__sug {
  font-size: var(--fs-sm);
  color: var(--ink-500);
  margin-bottom: var(--sp-3);
}
.rcard__sug b,
.wcard__sug b {
  color: var(--brand-300);
  margin-right: 6px;
  font-weight: 600;
}

.rcard__foot,
.wcard__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.wcard__meta {
  margin-bottom: var(--sp-2);
}

.wcard__done {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: var(--sp-3);
  border-radius: var(--r-md);
  background: rgba(42, 111, 91, 0.12);
  border: 1px solid rgba(113, 169, 150, 0.28);
  font-size: var(--fs-sm);
}
.wcard__done b {
  color: var(--brand-300);
  margin-right: 6px;
  font-weight: 600;
}

/* ---------- 处置表单 ---------- */
.form {
  margin-top: var(--sp-4);
  padding-top: var(--sp-4);
  border-top: 1px dashed var(--line);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.form__f {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 260px;
}
.form__label {
  font-size: var(--fs-xs);
  color: var(--ink-500);
}
.form__input,
.form__ta {
  width: 100%;
  background: rgba(0, 0, 0, 0.22);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--ink-900);
  font-size: var(--fs-sm);
  font-family: inherit;
  padding: 7px 10px;
  outline: none;
}
.form__input:focus,
.form__ta:focus {
  border-color: var(--brand-500);
}
.form__ta {
  resize: vertical;
  line-height: 1.6;
}
.form__err {
  font-size: var(--fs-xs);
  color: #e8a08c;
}
.form__acts {
  display: flex;
  gap: var(--sp-2);
  flex-wrap: wrap;
}

/* ============================================================
   分流方案 / 分流公告（M5 续）
   ============================================================ */

.plan {
  margin-top: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
}

.plan__head {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  margin-bottom: var(--sp-2);
  flex-wrap: wrap;
}

.plan__label {
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--ink-700);
}

.plan__hint {
  font-size: var(--fs-cap);
  color: var(--warm-500);
}

.plan__list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.plan__list--tight {
  margin-top: var(--sp-3);
}

.plan__item {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  font-size: var(--fs-sm);
}

.plan__no {
  width: 18px;
  height: 18px;
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-pill);
  background: var(--gold-500);
  color: #1a211d;
  font-size: 11px;
  font-weight: 700;
}

.plan__name {
  color: var(--ink-900);
  font-weight: 600;
}

.plan__num {
  color: var(--brand-300);
  font-size: var(--fs-xs);
}

.plan__num.is-off {
  color: var(--warm-500);
}

.plan__reason {
  color: var(--ink-500);
  font-size: var(--fs-xs);
}

.nlist {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

.ncard {
  padding: var(--sp-4) var(--sp-5);
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--line);
  border-left: 3px solid var(--warm-400);
  border-radius: var(--r-md);
}

.ncard--ok {
  border-left-color: var(--ok);
}

.ncard--warn {
  border-left-color: var(--warn);
}

.ncard__head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
}

.ncard__title {
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--ink-900);
}

.ncard__code {
  margin-left: auto;
  font-size: var(--fs-cap);
  color: var(--warm-500);
}

.ncard__msg {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--ink-600);
}

.ncard__meta {
  margin: var(--sp-3) 0 0;
  font-size: var(--fs-xs);
  color: var(--warm-500);
}

.ncard__foot {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-top: var(--sp-3);
  padding-top: var(--sp-3);
  border-top: 1px dashed var(--line);
}
</style>
