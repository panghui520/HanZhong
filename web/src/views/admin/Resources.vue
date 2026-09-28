<script setup lang="ts">
/**
 * 资源管理（M10）
 *
 * 三个入口：景点（含乡村旅游）、美食、农产品。每个入口支持
 * 列表 / 搜索 / 状态筛选 / 新增 / 编辑 / 上架 / 下架 / 删除。
 *
 * 几条刻意的取舍：
 *
 * 1. **景点与美食共用一套表格与表单。** 它们在后端是同一张 poi 表的两个
 *    business_type，字段完全一样。写两套的话，将来给 poi 加一个字段
 *    就要记得改两处 —— 而"忘记改第二处"这类问题不会报错，只会静默地少一个输入框。
 *
 * 2. **不新建后台。** 页面挂在现有 AdminLayout 下，沿用同一套 panel / field /
 *    btn 样式；菜单与路由都加在现有结构里。
 *
 * 3. **删除按钮对数据包资源仍然禁用。** 后端会拒绝（删了下次启动也会回来），
 *    与其让运营点一次、读一遍报错，不如一开始就灰掉并说明原因。
 *    注意这与「编辑」不同 —— 编辑现在允许了，见第 5 条。
 *
 * 4. **配图复用 M9 的能力，不另做一套上传。** 这里只做"选图 → 传 → 删 → 设封面"，
 *    接口全部是 /api/admin/media/poi-images 那一组。排序也在，因为后端要的是
 *    "期望的完整顺序"，前端把调整后的顺序整批发过去即可。
 *
 * 5. **数据包资源的「编辑」是"接管"，不是"解锁"。** 这一点最容易看错，写在这里：
 *    - 点「编辑」一条 `source='PACK'` 的景点时，先弹一次确认，说清这一步的含义：
 *      这条资源将从"数据包"转为"运营维护"，**此后数据包更新不会再同步到它**。
 *      确认之后进入表单，保存时后端把 source 改成 ADMIN
 *      （`AdminResourceServiceImpl.updatePoi`）。所以它既不是"点一下就改"
 *      （那会让人以为改的只是这一次），也不是"灰着不让点"
 *      （那运营就永远改不了数据包里的错字）。
 *    - 「删除」对 `source='PACK'` 的行**仍然禁用** —— 接管是运营有意识走出的一步，
 *      删除不是。删了下次启动也会回来，所以后端拒绝、前端也灰掉。
 *    - 「上架」「下架」**从来不禁用** —— `status` 不在数据包里（`citypack/*.json`
 *      没有这个字段），它是**运营态**，后端导入器会把它保留下来
 *      （见 `CityPackImporter` 的 `offlinePoiStatuses`）。
 *
 * 6. **评论管理是本页的第四个入口，不是新页面。** 它沿用同一套 panel / table 样式，
 *    只是数据来自 /api/admin/comments 而不是资源表。运营审一条评论时，
 *    多半刚在景点页看过它 —— 放在同一个页面里省一次跳转。
 */
import { computed, onMounted, ref } from 'vue'
import { ApiError } from '@/api/http'
import { getExperiences, getPois, getProductCategories } from '@/api/citypack'
import {
  adminCreatePoi,
  adminCreateProduct,
  adminDeletePoi,
  adminDeleteProduct,
  adminListPois,
  adminListProducts,
  adminSetPoiStatus,
  adminSetProductStatus,
  adminUpdatePoi,
  adminUpdateProduct,
  type PoiKind,
} from '@/api/resources'
import {
  adminDeletePoiImage,
  adminListPoiImages,
  adminReorderPoiImages,
  adminUpdatePoiImage,
  adminUploadPoiImage,
} from '@/api/media'
import {
  adminDeleteComment,
  adminListComments,
  adminSetCommentStatus,
} from '@/api/comments'
import { reloadCovers } from '@/composables/useCovers'
import { SCENE_LABELS, SCENE_VARIANTS } from '@/utils/scene'
import {
  BUSINESS_LABEL,
  RESOURCE_KIND_LABEL,
  type AdminComment,
  type AdminPoi,
  type AdminProduct,
  type CommentStatus,
  type Experience,
  type Poi,
  type PoiImage as PoiImageRow,
  type ProductCategory,
  type ResourceKind,
} from '@/types'

/**
 * 本页的四个入口。
 *
 * 前三个是资源类型（对应后端两组端点），第四个是评论 —— 评论不是"资源类型"，
 * 所以 `TabKey` 写成 `ResourceKind | 'comment'`，而不是往 `ResourceKind` 里
 * 塞一个 `'comment'`：那个类型同时被 `adminListPois` 之类的接口签名用着，
 * 掺进一个接口根本不认的值，将来在别处传错就没人拦得住。
 */
type TabKey = ResourceKind | 'comment'

const TABS: { key: TabKey; label: string }[] = [
  { key: 'scenic', label: RESOURCE_KIND_LABEL.scenic },
  { key: 'food', label: RESOURCE_KIND_LABEL.food },
  { key: 'product', label: RESOURCE_KIND_LABEL.product },
  { key: 'comment', label: '评论管理' },
]

const SCENES = SCENE_VARIANTS.map((value) => ({ value, label: SCENE_LABELS[value] }))

// ============================================================
// 列表
// ============================================================

const kind = ref<TabKey>('scenic')
const keyword = ref('')
/** '' = 全部 / '1' = 已上架 / '0' = 已下架。用字符串是因为 select 的 v-model 只能给字符串 */
const statusFilter = ref<'' | '0' | '1'>('')
const loading = ref(false)
const busy = ref(false)
const notice = ref<{ type: 'ok' | 'err'; text: string } | null>(null)

const poiRows = ref<AdminPoi[]>([])
const productRows = ref<AdminProduct[]>([])

// ---- 评论管理（第四个 tab）----
const commentRows = ref<AdminComment[]>([])
/** 按景点筛，'' = 全部。值是 poi_id */
const commentPoiFilter = ref('')
/** 按状态筛，'' = 全部 */
const commentStatus = ref<CommentStatus | ''>('')

/** 景点与美食共用 poi 那套表格与表单；农产品与评论各自一套 */
const isPoi = computed(() => kind.value === 'scenic' || kind.value === 'food')
const isComment = computed(() => kind.value === 'comment')

const rows = computed<(AdminPoi | AdminProduct)[]>(() =>
  isPoi.value ? poiRows.value : productRows.value
)
const noun = computed(
  () => ({ scenic: '景点', food: '美食', product: '农产品', comment: '评论' })[kind.value]
)

let noticeTimer: number | undefined
function say(type: 'ok' | 'err', text: string) {
  notice.value = { type, text }
  if (noticeTimer !== undefined) window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => (notice.value = null), 5000)
}

function errText(e: unknown, fallback: string) {
  return e instanceof ApiError ? e.message : fallback
}

/** 统一的写操作包装：串行化 + 错误提示。与 Media.vue 同一做法 */
async function write(okText: string, fn: () => Promise<unknown>) {
  if (busy.value) return
  busy.value = true
  try {
    await fn()
    say('ok', okText)
  } catch (e) {
    say('err', errText(e, '操作失败，请重试'))
  } finally {
    busy.value = false
  }
}

async function reload() {
  loading.value = true
  try {
    // 评论走另一组端点，筛选参数也不同（没有 keyword / 上下架），
    // 所以在入口处就分开，而不是把评论硬塞进 poi/product 那套判断里
    if (isComment.value) {
      commentRows.value = await adminListComments({
        poiId: commentPoiFilter.value,
        status: commentStatus.value,
      })
      return
    }

    const status = statusFilter.value === '' ? undefined : Number(statusFilter.value)
    const kw = keyword.value.trim()
    if (isPoi.value) {
      poiRows.value = await adminListPois(kind.value as PoiKind, { keyword: kw, status })
    } else {
      productRows.value = await adminListProducts({ keyword: kw, status })
    }
  } catch (e) {
    say('err', errText(e, '列表加载失败'))
  } finally {
    loading.value = false
  }
}

function switchKind(k: TabKey) {
  if (kind.value === k) return
  kind.value = k
  keyword.value = ''
  statusFilter.value = ''
  // 评论 tab 的筛选框与资源的不是一回事，切换时一并清掉 ——
  // 否则"只看已隐藏"这个状态会在切走再切回来之后仍然生效，
  // 而运营以为自己看到的是全部
  commentPoiFilter.value = ''
  commentStatus.value = ''
  void reload()
}

function toggleStatus(row: AdminPoi | AdminProduct) {
  const next = row.status === 1 ? 0 : 1
  const label = next === 1 ? '上架' : '下架'
  void write(`已${label}「${row.name}」`, async () => {
    const saved = isPoi.value
      ? await adminSetPoiStatus(kind.value as PoiKind, row.id, next)
      : await adminSetProductStatus(row.id, next)
    // 用服务端返回的对象覆盖本地，避免"本地看着改了、服务端没接受"的偏差
    Object.assign(row, saved)
  })
}

function remove(row: AdminPoi | AdminProduct) {
  const hint =
    row.source === 'PACK'
      ? '\n\n该资源来自城市数据包，后端会拒绝删除 —— 请改用下架。'
      : '\n\n若它已被订单、足迹或体验引用，后端也会拒绝并说明原因。'
  if (!window.confirm(`删除「${row.name}」？该操作不可撤销。${hint}`)) return
  void write(`已删除「${row.name}」`, async () => {
    if (isPoi.value) {
      await adminDeletePoi(kind.value as PoiKind, row.id)
    } else {
      await adminDeleteProduct(row.id)
    }
    await reload()
  })
}

// ============================================================
// 新增 / 编辑
// ============================================================

interface PoiForm {
  name: string
  business_type: string
  district: string
  /** 详细地址（街道门牌）。M10 续新增 */
  address: string
  level: string
  /** 对外联系电话。M10 续新增 */
  phone: string
  lng: string
  lat: string
  ticket_price: string
  open_hours: string
  duration_min: string
  capacity: string
  /** 承载率预警线（0.01~1.00），空 = 用 risk_rule 的全局阈值。M10 续新增 */
  warning_threshold: string
  tags: string
  summary: string
  /** 详细介绍正文。M10 续新增 */
  detail: string
  scene: string
}

interface ProductForm {
  name: string
  category_code: string
  spec: string
  price: string
  origin_village: string
  stock: string
  poi_id: string
  experience_id: string
  tags: string
  story: string
  scene: string
}

function emptyPoiForm(): PoiForm {
  return {
    name: '',
    // 景点入口默认「景区」，美食入口固定「餐饮」。不写死 'SCENIC'：
    // 在美食 tab 下新建时默认值必须是 FOOD，否则会建出一条跑到景点 tab 的资源。
    business_type: kind.value === 'food' ? 'FOOD' : 'SCENIC',
    district: '',
    address: '',
    level: '',
    phone: '',
    lng: '',
    lat: '',
    ticket_price: '',
    open_hours: '',
    duration_min: '',
    capacity: '',
    warning_threshold: '',
    tags: '',
    summary: '',
    detail: '',
    scene: 'qinling',
  }
}

function emptyProductForm(): ProductForm {
  return {
    name: '',
    category_code: '',
    spec: '',
    price: '',
    origin_village: '',
    stock: '',
    poi_id: '',
    experience_id: '',
    tags: '',
    story: '',
    scene: 'terrace',
  }
}

const formOpen = ref(false)
const editingId = ref<string | null>(null)
const poiForm = ref<PoiForm>(emptyPoiForm())
const productForm = ref<ProductForm>(emptyProductForm())

/** 乡村点与体验，给农产品的"产地 / 体验锚点"下拉用。进页面时取一次 */
const allPois = ref<Poi[]>([])
const experiences = ref<Experience[]>([])
const categories = ref<ProductCategory[]>([])

const ruralPois = computed(() => allPois.value.filter((p) => p.business_type === 'RURAL_SPOT'))

/** 农产品表单里，选中产地后只显示该产地下的体验 —— 挂一个不相干的体验是常见误操作 */
const experienceOptions = computed(() => {
  const pid = productForm.value.poi_id
  return pid ? experiences.value.filter((e) => e.poi_id === pid) : experiences.value
})

function openCreate() {
  editingId.value = null
  poiForm.value = emptyPoiForm()
  productForm.value = emptyProductForm()
  formOpen.value = true
}

function openEdit(row: AdminPoi | AdminProduct) {
  // ★ 数据包资源要改，先接管（M10 续）。
  //
  // 这一步不是"确认要不要编辑"，而是**告知一个不可逆的后果**：保存之后
  // 这条资源就归运营维护了，数据包再更新它的名字/简介也不会同步过来。
  //
  // 用 window.confirm 而不是自定义弹窗，是因为它是本页唯一一处"需要用户
  // 明确点头"的操作 —— 其余按钮要么可撤销（下架），要么后端会拒（删除）。
  if (row.source === 'PACK') {
    const ok = window.confirm(
      `「${row.name}」来自城市数据包。\n\n` +
        `继续编辑会把它接管为运营维护的资源：\n` +
        `· 修改会保存到数据库，重启后端也不会被覆盖\n` +
        `· 此后数据包更新这条景点的信息，不会再同步过来\n` +
        `· 资源编码不变（仍是 ${row.id}），订单与足迹的引用不受影响\n\n` +
        `确定要接管并编辑吗？`
    )
    if (!ok) return
  }

  editingId.value = row.id
  if (isPoi.value) {
    const p = row as AdminPoi
    poiForm.value = {
      name: p.name,
      business_type: p.business_type,
      district: p.district ?? '',
      address: p.address ?? '',
      level: p.level ?? '',
      phone: p.phone ?? '',
      lng: p.lng == null ? '' : String(p.lng),
      lat: p.lat == null ? '' : String(p.lat),
      ticket_price: p.ticket_price == null ? '' : String(p.ticket_price),
      open_hours: p.open_hours ?? '',
      duration_min: p.duration_min == null ? '' : String(p.duration_min),
      capacity: p.capacity == null ? '' : String(p.capacity),
      warning_threshold: p.warning_threshold == null ? '' : String(p.warning_threshold),
      tags: (p.tags ?? []).join(', '),
      summary: p.summary ?? '',
      detail: p.detail ?? '',
      scene: p.scene ?? 'qinling',
    }
  } else {
    const d = row as AdminProduct
    productForm.value = {
      name: d.name,
      category_code: d.category_code ?? '',
      spec: d.spec ?? '',
      price: d.price == null ? '' : String(d.price),
      origin_village: d.origin_village ?? '',
      stock: d.stock == null ? '' : String(d.stock),
      poi_id: d.poi_id ?? '',
      experience_id: d.experience_id ?? '',
      tags: (d.tags ?? []).join(', '),
      story: d.story ?? '',
      scene: d.scene ?? 'terrace',
    }
  }
  formOpen.value = true
}

function closeForm() {
  formOpen.value = false
  editingId.value = null
}

/** 逗号分隔（中英文都认）→ 数组。空数组会让后端把 tags 存成 NULL，这是期望的 */
function splitTags(raw: string): string[] {
  return raw
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

function numOrNull(raw: string): number | null {
  return raw.trim() === '' ? null : Number(raw)
}

function numOrZero(raw: string): number {
  return raw.trim() === '' ? 0 : Number(raw)
}

/**
 * 时间显示到分钟。
 *
 * 后端给的是 `2026-09-28T11:03:14`（LocalDateTime 的 ISO 形式）。秒对
 * "审一条评论"没有意义，而完整串会把表格撑宽、被迫换行。
 * 不引日期库：这里只需要把 T 换成空格再截断。
 */
function fmtTime(raw?: string) {
  return raw ? raw.replace('T', ' ').slice(0, 16) : '—'
}

function save() {
  const editing = editingId.value !== null
  void write(editing ? '已保存' : `已新增${noun.value}`, async () => {
    if (isPoi.value) {
      const f = poiForm.value
      const body = {
        name: f.name,
        business_type: f.business_type,
        district: f.district,
        address: f.address,
        level: f.level,
        phone: f.phone,
        lng: numOrNull(f.lng),
        lat: numOrNull(f.lat),
        ticket_price: numOrZero(f.ticket_price),
        open_hours: f.open_hours,
        duration_min: numOrZero(f.duration_min),
        capacity: numOrZero(f.capacity),
        // 空串 → null。后端把 null 当"清空这一列"（回到全局阈值），
        // 而不是"没传"—— 所以这里必须显式带上这个键，不能靠省略
        warning_threshold: numOrNull(f.warning_threshold),
        tags: splitTags(f.tags),
        summary: f.summary,
        detail: f.detail,
        scene: f.scene,
      }
      if (editing) {
        await adminUpdatePoi(kind.value as PoiKind, editingId.value as string, body)
      } else {
        await adminCreatePoi(kind.value as PoiKind, body)
      }
    } else {
      const f = productForm.value
      const body = {
        name: f.name,
        category_code: f.category_code,
        spec: f.spec,
        price: numOrZero(f.price),
        origin_village: f.origin_village,
        stock: numOrZero(f.stock),
        poi_id: f.poi_id,
        experience_id: f.experience_id,
        tags: splitTags(f.tags),
        story: f.story,
        scene: f.scene,
      }
      if (editing) {
        await adminUpdateProduct(editingId.value as string, body)
      } else {
        await adminCreateProduct(body)
      }
    }
    closeForm()
    await reload()
  })
}

// ============================================================
// 配图（复用 M9）
// ============================================================

const imgOpen = ref(false)
const imgPoi = ref<AdminPoi | null>(null)
const imgRows = ref<PoiImageRow[]>([])
const imgLoading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

async function openImages(row: AdminPoi | AdminProduct) {
  imgPoi.value = row as AdminPoi
  imgOpen.value = true
  await loadImages()
}

async function loadImages() {
  const p = imgPoi.value
  if (!p) return
  imgLoading.value = true
  try {
    imgRows.value = await adminListPoiImages(p.id)
  } catch (e) {
    say('err', errText(e, '配图加载失败'))
  } finally {
    imgLoading.value = false
  }
}

function pickImage() {
  fileInput.value?.click()
}

function onImagePicked(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  const p = imgPoi.value
  input.value = ''
  if (!file || !p) return
  void write('已上传配图', async () => {
    await adminUploadPoiImage(p.id, file)
    await loadImages()
    await reloadCovers()
  })
}

function removeImage(img: PoiImageRow) {
  if (!window.confirm('删除这张配图？文件会一并从磁盘删除。')) return
  void write('已删除该配图', async () => {
    await adminDeletePoiImage(img.id)
    await loadImages()
    await reloadCovers()
  })
}

function setCover(img: PoiImageRow) {
  void write('已设为封面', async () => {
    await adminUpdatePoiImage(img.id, { is_cover: 1 })
    await loadImages()
    await reloadCovers()
  })
}

/** 上移 / 下移。后端要的是"调整后的完整顺序"，不是两两交换 */
function moveImage(i: number, dir: -1 | 1) {
  const p = imgPoi.value
  const j = i + dir
  if (!p || j < 0 || j >= imgRows.value.length) return
  const ids = imgRows.value.map((r) => r.id)
  ;[ids[i], ids[j]] = [ids[j], ids[i]]
  void write('顺序已更新', async () => {
    imgRows.value = await adminReorderPoiImages(p.id, ids)
    await reloadCovers()
  })
}

// ============================================================
// 评论管理（M10 续）
// ============================================================

/**
 * 景点下拉的候选。
 *
 * 用 allPois（进页面时取一次的全量景点）而不是从 commentRows 里推 ——
 * 后者只能筛出"已经有评论的景点"，而运营想查的往往是"某个景点有没有评论"，
 * 那恰恰可能是**一条评论都没有**的那个。
 */
const commentPoiOptions = computed(() =>
  [...allPois.value].sort((a, b) => a.name.localeCompare(b.name, 'zh'))
)

function setCommentStatus(row: AdminComment, status: CommentStatus) {
  const verb: Record<CommentStatus, string> = {
    PENDING: '打回待审',
    APPROVED: '通过',
    HIDDEN: '隐藏',
  }
  void write(`已${verb[status]}这条评论`, async () => {
    const saved = await adminSetCommentStatus(row.id, status)
    // 用服务端返回的对象覆盖本地，避免"本地看着改了、服务端没接受"的偏差
    Object.assign(row, saved)
    // 当前正在按状态筛选时，改完状态这条就不该留在列表里了 ——
    // 只就地覆盖会让它带着新状态继续显示，看着像没生效
    if (commentStatus.value) await reload()
  })
}

function removeComment(row: AdminComment) {
  if (
    !window.confirm(
      `删除「${row.nickname}」的这条评论？删除后不可恢复。\n\n` +
        `如果只是想让它从游客端消失，请用「隐藏」—— 隐藏可以再放出来。`
    )
  ) {
    return
  }
  void write('已删除该评论', async () => {
    await adminDeleteComment(row.id)
    await reload()
  })
}

// ============================================================

onMounted(async () => {
  await reload()
  // 下拉数据取不到不影响主流程（表单里那几个 select 会空着），所以单独 catch
  try {
    const [pois, exps, cats] = await Promise.all([
      getPois(),
      getExperiences(),
      getProductCategories(),
    ])
    allPois.value = pois
    experiences.value = exps
    categories.value = cats
  } catch {
    /* 忽略：表单下拉留空，不影响列表与上下架 */
  }
})
</script>

<template>
  <div class="res">
    <header class="res__head">
      <div>
        <span class="eyebrow">运营中枢</span>
        <h1 class="h1 res__title">资源管理</h1>
        <p class="res__sub">
          景点、美食与农产品的上架维护。下架优先于删除 ——
          被订单、足迹或体验引用的资源不会允许物理删除，以免留下悬空引用。
        </p>
      </div>
      <div class="res__meta">
        <span class="badge-sim">仅运营可操作</span>
      </div>
    </header>

    <Transition name="notice">
      <div v-if="notice" class="notice" :class="`notice--${notice.type}`">{{ notice.text }}</div>
    </Transition>

    <div class="tabs">
      <button
        v-for="t in TABS"
        :key="t.key"
        class="tab"
        :class="{ 'tab--on': kind === t.key }"
        @click="switchKind(t.key)"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- 评论页的筛选条件与资源页完全不同，分成两块 -->
    <div v-if="isComment" class="panel toolbar">
      <select v-model="commentPoiFilter" class="field">
        <option value="">全部景点</option>
        <option v-for="p in commentPoiOptions" :key="p.id" :value="p.id">{{ p.name }}</option>
      </select>
      <select v-model="commentStatus" class="field field--sm">
        <option value="">全部状态</option>
        <option value="APPROVED">已通过</option>
        <option value="HIDDEN">已隐藏</option>
        <option value="PENDING">待审核</option>
      </select>
      <button class="btn btn-ghost btn-sm" :disabled="loading" @click="reload">查询</button>
      <span class="muted small">游客端只显示「已通过」的评论</span>
    </div>

    <div v-else class="panel toolbar">
      <input
        v-model="keyword"
        class="field"
        :placeholder="`搜索${noun}名称 / 简介 / 标签 / 区县 / 编码`"
        @keyup.enter="reload"
      />
      <select v-model="statusFilter" class="field field--sm">
        <option value="">全部状态</option>
        <option value="1">已上架</option>
        <option value="0">已下架</option>
      </select>
      <button class="btn btn-ghost btn-sm" :disabled="loading" @click="reload">查询</button>
      <button class="btn btn-primary btn-sm" :disabled="busy" @click="openCreate">
        新增{{ noun }}
      </button>
    </div>

    <div v-if="loading" class="stack-3">
      <div v-for="i in 5" :key="i" class="skeleton" style="height: 56px; border-radius: 10px" />
    </div>

    <!-- ==================== 评论管理 ==================== -->
    <template v-else-if="isComment">
      <div v-if="!commentRows.length" class="empty">
        <div class="empty__title">没有符合条件的评论</div>
        <div class="empty__desc">
          {{
            commentPoiFilter || commentStatus
              ? '换个景点或状态试试'
              : '还没有游客写过评论 —— 游客端景点详情页可以发表'
          }}
        </div>
      </div>

      <div v-else class="tablewrap">
        <table class="rt">
          <thead>
            <tr>
              <th class="rt__name">评论内容</th>
              <th>所属景点</th>
              <th>评分</th>
              <th>状态</th>
              <th class="rt__ops">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="c in commentRows"
              :key="c.id"
              :class="{ 'rt__tr--off': c.status !== 'APPROVED' }"
            >
              <td>
                <div class="rt__title">{{ c.content }}</div>
                <div class="rt__meta">
                  <span>{{ c.nickname }}</span>
                  <span class="muted small">{{ fmtTime(c.created_at) }}</span>
                </div>
              </td>
              <td>
                <div>{{ c.poi_name || c.poi_id }}</div>
                <div class="muted small"><code>{{ c.poi_id }}</code></div>
              </td>
              <td><span class="num">{{ c.rating }}</span> 星</td>
              <td>
                <span class="tag" :class="c.status === 'APPROVED' ? 'tag-brand' : 'tag-warn'">
                  {{ c.status_label }}
                </span>
              </td>
              <td class="rt__ops">
                <button
                  v-if="c.status !== 'APPROVED'"
                  class="btn btn-ghost btn-sm"
                  :disabled="busy"
                  @click="setCommentStatus(c, 'APPROVED')"
                >
                  通过
                </button>
                <button
                  v-if="c.status !== 'HIDDEN'"
                  class="btn btn-ghost btn-sm"
                  :disabled="busy"
                  @click="setCommentStatus(c, 'HIDDEN')"
                >
                  隐藏
                </button>
                <button
                  class="btn btn-ghost btn-sm rt__danger"
                  :disabled="busy"
                  @click="removeComment(c)"
                >
                  删除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- ==================== 资源（景点 / 美食 / 农产品） ==================== -->
    <template v-else>
      <div v-if="!rows.length" class="empty">
        <div class="empty__title">没有符合条件的{{ noun }}</div>
        <div class="empty__desc">
          {{ keyword || statusFilter ? '换个关键词或状态试试' : '点右上角「新增」建一条' }}
        </div>
      </div>

      <div v-else class="tablewrap">
      <table class="rt">
        <thead>
          <tr>
            <th class="rt__name">名称</th>
            <th>{{ isPoi ? '业态 / 区县' : '分类 / 产地' }}</th>
            <th>{{ isPoi ? '价格 / 时长' : '价格 / 库存' }}</th>
            <th v-if="isPoi">配图</th>
            <th>状态</th>
            <th class="rt__ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id" :class="{ 'rt__tr--off': row.status !== 1 }">
            <td>
              <div class="rt__title">{{ row.name }}</div>
              <div class="rt__meta">
                <code>{{ row.id }}</code>
                <span class="tag" :class="row.source === 'ADMIN' ? 'tag-tech' : ''">
                  {{ row.source_label }}
                </span>
              </div>
            </td>

            <td v-if="isPoi">
              <div>{{ BUSINESS_LABEL[(row as AdminPoi).business_type] }}</div>
              <div class="muted small">{{ (row as AdminPoi).district || '—' }}</div>
            </td>
            <td v-else>
              <div>{{ (row as AdminProduct).category }}</div>
              <div class="muted small">
                {{ (row as AdminProduct).poi_name || (row as AdminProduct).experience_name || '—' }}
              </div>
            </td>

            <td v-if="isPoi">
              <div class="num">¥{{ (row as AdminPoi).ticket_price }}</div>
              <div class="muted small">{{ (row as AdminPoi).duration_min }} 分钟</div>
            </td>
            <td v-else>
              <div class="num">¥{{ (row as AdminProduct).price }}</div>
              <div class="muted small">库存 {{ (row as AdminProduct).stock }}</div>
            </td>

            <td v-if="isPoi">
              <span :class="(row as AdminPoi).image_count ? '' : 'muted'">
                {{ (row as AdminPoi).image_count }} 张
              </span>
            </td>

            <td>
              <span class="tag" :class="row.status === 1 ? 'tag-brand' : 'tag-warn'">
                {{ row.status === 1 ? '已上架' : '已下架' }}
              </span>
            </td>

            <td class="rt__ops">
              <!--
                编辑**不因 source=PACK 而禁用**（M10 续）：点它会先弹一次
                「接管」确认，确认后保存即把 source 改成 ADMIN，这条资源
                从此不再被数据包覆盖。见 openEdit 的注释。
              -->
              <button
                class="btn btn-ghost btn-sm"
                :disabled="busy"
                :title="
                  row.source === 'PACK'
                    ? '编辑（会把这条资源从城市数据包接管为运营维护）'
                    : '编辑'
                "
                @click="openEdit(row)"
              >
                编辑
              </button>
              <button class="btn btn-ghost btn-sm" :disabled="busy" @click="toggleStatus(row)">
                {{ row.status === 1 ? '下架' : '上架' }}
              </button>
              <button v-if="isPoi" class="btn btn-ghost btn-sm" :disabled="busy" @click="openImages(row)">
                配图
              </button>
              <button
                class="btn btn-ghost btn-sm rt__danger"
                :disabled="busy || row.source !== 'ADMIN'"
                :title="
                  row.source === 'ADMIN'
                    ? '删除'
                    : '数据包资源删除后下次启动会重新导入。想改内容请点「编辑」接管，想藏起来请「下架」'
                "
                @click="remove(row)"
              >
                删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
    </template>

    <!-- ==================== 新增 / 编辑 ==================== -->
    <div v-if="formOpen" class="modal" @click.self="closeForm">
      <div class="modal__box">
        <div class="modal__head">
          <h3 class="h3">
            {{ editingId ? '编辑' : '新增' }}{{ noun }}
            <span v-if="editingId" class="muted small">{{ editingId }}</span>
          </h3>
          <button class="btn btn-ghost btn-sm" @click="closeForm">关闭</button>
        </div>

        <div class="modal__body stack-3">
          <template v-if="isPoi">
            <label class="fld">
              <span class="fld__label">名称 *</span>
              <input v-model="poiForm.name" class="field" placeholder="如：石门栈道风景区" />
            </label>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">业态</span>
                <select v-model="poiForm.business_type" class="field">
                  <template v-if="kind === 'food'">
                    <option value="FOOD">{{ BUSINESS_LABEL.FOOD }}</option>
                  </template>
                  <template v-else>
                    <option value="SCENIC">{{ BUSINESS_LABEL.SCENIC }}</option>
                    <option value="RURAL_SPOT">{{ BUSINESS_LABEL.RURAL_SPOT }}</option>
                  </template>
                </select>
              </label>
              <label class="fld">
                <span class="fld__label">区县</span>
                <input v-model="poiForm.district" class="field" placeholder="如：留坝县" />
              </label>
            </div>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">详细地址</span>
                <input
                  v-model="poiForm.address"
                  class="field"
                  placeholder="如：汉台区河东店镇石门栈道风景区"
                />
              </label>
              <label class="fld">
                <span class="fld__label">联系电话</span>
                <input v-model="poiForm.phone" class="field" placeholder="如：0916-1234567" />
              </label>
            </div>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">等级 / 称号</span>
                <input v-model="poiForm.level" class="field" placeholder="如：4A 级景区" />
              </label>
              <label class="fld">
                <span class="fld__label">开放时间</span>
                <input v-model="poiForm.open_hours" class="field" placeholder="如：08:30-17:30" />
              </label>
            </div>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">经度</span>
                <input v-model="poiForm.lng" class="field" placeholder="107.03" />
              </label>
              <label class="fld">
                <span class="fld__label">纬度</span>
                <input v-model="poiForm.lat" class="field" placeholder="33.07" />
              </label>
            </div>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">门票 / 人均（元）</span>
                <input v-model="poiForm.ticket_price" class="field" placeholder="0" />
              </label>
              <label class="fld">
                <span class="fld__label">建议时长（分钟）</span>
                <input v-model="poiForm.duration_min" class="field" placeholder="90" />
              </label>
            </div>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">承载上限（人）</span>
                <input v-model="poiForm.capacity" class="field" placeholder="2000" />
              </label>
              <label class="fld">
                <span class="fld__label">预警阈值（承载率）</span>
                <input
                  v-model="poiForm.warning_threshold"
                  class="field"
                  placeholder="留空 = 用全局阈值"
                />
              </label>
            </div>
            <p class="fld__hint">
              预警阈值填 0.01~1.00 之间的小数（0.8 表示到八成触发预警）。留空则沿用
              运营分析里配置的全局阈值。它只影响「承载接近上限」这一档告警；
              「已超载」那一档永远按 100% 判，不受这里影响。
            </p>
            <label class="fld">
              <span class="fld__label">标签（逗号分隔）</span>
              <input v-model="poiForm.tags" class="field" placeholder="栈道, 三国, 亲子" />
            </label>
            <label class="fld">
              <span class="fld__label">简介（一句话，列表与卡片用）</span>
              <textarea v-model="poiForm.summary" class="field field--area" rows="3" />
            </label>
            <label class="fld">
              <span class="fld__label">详细介绍（详情页正文）</span>
              <textarea
                v-model="poiForm.detail"
                class="field field--area"
                rows="6"
                placeholder="历史沿革、看点、游览建议等。留空则详情页只显示上面的简介。"
              />
            </label>
            <label class="fld">
              <span class="fld__label">封面插画（未上传配图时的兜底画面）</span>
              <select v-model="poiForm.scene" class="field">
                <option v-for="s in SCENES" :key="s.value" :value="s.value">{{ s.label }}</option>
              </select>
            </label>
          </template>

          <template v-else>
            <label class="fld">
              <span class="fld__label">名称 *</span>
              <input v-model="productForm.name" class="field" placeholder="如：留坝西洋参片" />
            </label>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">分类 *</span>
                <select v-model="productForm.category_code" class="field">
                  <option value="">请选择</option>
                  <option v-for="c in categories" :key="c.code" :value="c.code">
                    {{ c.name }}
                  </option>
                </select>
              </label>
              <label class="fld">
                <span class="fld__label">规格</span>
                <input v-model="productForm.spec" class="field" placeholder="如：100g / 罐" />
              </label>
            </div>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">产地资源点</span>
                <select v-model="productForm.poi_id" class="field">
                  <option value="">（不指定）</option>
                  <option v-for="p in ruralPois" :key="p.id" :value="p.id">{{ p.name }}</option>
                </select>
              </label>
              <label class="fld">
                <span class="fld__label">体验锚点</span>
                <select v-model="productForm.experience_id" class="field">
                  <option value="">（不指定）</option>
                  <option v-for="e in experienceOptions" :key="e.id" :value="e.id">
                    {{ e.name }}
                  </option>
                </select>
              </label>
            </div>
            <p class="fld__hint">
              产地与体验锚点至少填一项 —— 农产品必须能追溯到一次乡村体验或一个产地，
              这是本项目的设计红线，数据库也有同样的约束。
            </p>
            <div class="fld__triple">
              <label class="fld">
                <span class="fld__label">售价（元）</span>
                <input v-model="productForm.price" class="field" placeholder="0" />
              </label>
              <label class="fld">
                <span class="fld__label">库存</span>
                <input v-model="productForm.stock" class="field" placeholder="0" />
              </label>
              <label class="fld">
                <span class="fld__label">产地村</span>
                <input v-model="productForm.origin_village" class="field" placeholder="如：火烧店镇" />
              </label>
            </div>
            <label class="fld">
              <span class="fld__label">标签（逗号分隔）</span>
              <input v-model="productForm.tags" class="field" placeholder="地理标志, 礼盒" />
            </label>
            <label class="fld">
              <span class="fld__label">溯源文案</span>
              <textarea
                v-model="productForm.story"
                class="field field--area"
                rows="3"
                placeholder="这一款和那次体验的关系"
              />
            </label>
            <label class="fld">
              <span class="fld__label">封面插画</span>
              <select v-model="productForm.scene" class="field">
                <option v-for="s in SCENES" :key="s.value" :value="s.value">{{ s.label }}</option>
              </select>
            </label>
          </template>
        </div>

        <div class="modal__foot">
          <button class="btn btn-ghost btn-sm" :disabled="busy" @click="closeForm">取消</button>
          <button class="btn btn-primary btn-sm" :disabled="busy" @click="save">
            {{ editingId ? '保存' : '创建' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ==================== 配图（复用 M9） ==================== -->
    <div v-if="imgOpen" class="modal" @click.self="imgOpen = false">
      <div class="modal__box">
        <div class="modal__head">
          <h3 class="h3">
            配图
            <span class="muted small">{{ imgPoi?.name }}</span>
          </h3>
          <button class="btn btn-ghost btn-sm" @click="imgOpen = false">关闭</button>
        </div>

        <div class="modal__body stack-3">
          <input
            ref="fileInput"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            class="hidden-file"
            @change="onImagePicked"
          />
          <div class="row-between">
            <span class="muted small">
              第一张自动成为封面；没有配图时游客端回落到上面的手绘插画。
            </span>
            <button class="btn btn-primary btn-sm" :disabled="busy" @click="pickImage">
              上传配图
            </button>
          </div>

          <div v-if="imgLoading" class="skeleton" style="height: 120px; border-radius: 10px" />
          <div v-else-if="!imgRows.length" class="empty">
            <div class="empty__title">还没有配图</div>
            <div class="empty__desc">游客端会用手绘插画兜底，不会开天窗</div>
          </div>
          <div v-else class="imgs">
            <figure v-for="(img, i) in imgRows" :key="img.id" class="imgs__item">
              <img :src="img.url" :alt="img.alt_text || ''" />
              <figcaption class="imgs__cap">
                <span v-if="img.is_cover === 1" class="tag tag-gold">封面</span>
                <span class="imgs__idx num">#{{ i + 1 }}</span>
              </figcaption>
              <div class="imgs__ops">
                <button class="btn btn-ghost btn-sm" :disabled="busy || i === 0" @click="moveImage(i, -1)">
                  ↑
                </button>
                <button
                  class="btn btn-ghost btn-sm"
                  :disabled="busy || i === imgRows.length - 1"
                  @click="moveImage(i, 1)"
                >
                  ↓
                </button>
                <button
                  class="btn btn-ghost btn-sm"
                  :disabled="busy || img.is_cover === 1"
                  @click="setCover(img)"
                >
                  设为封面
                </button>
                <button class="btn btn-ghost btn-sm rt__danger" :disabled="busy" @click="removeImage(img)">
                  删除
                </button>
              </div>
            </figure>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.res {
  max-width: 1240px;
}

.res__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-5);
  margin-bottom: var(--sp-5);
}
.res__title {
  margin-top: var(--sp-2);
}
.res__sub {
  margin-top: var(--sp-2);
  max-width: 720px;
  font-size: var(--fs-sm);
  line-height: 1.85;
  color: var(--text-2);
}

/* ---------- 提示条 ---------- */
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
  margin-bottom: var(--sp-5);
  border-bottom: 1px solid var(--line);
}
.tab {
  position: relative;
  padding: var(--sp-3) var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--text-2);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: all var(--dur-1) var(--ease);
}
.tab:hover {
  color: #fff;
}
.tab--on {
  color: #fff;
  font-weight: 600;
  border-bottom-color: var(--gold-500);
}

/* ---------- 面板与表单 ---------- */
.panel {
  padding: var(--sp-4);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: rgba(18, 48, 38, 0.5);
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 160px auto auto;
  gap: var(--sp-3);
  align-items: center;
  margin-bottom: var(--sp-4);
}
@media (max-width: 860px) {
  .toolbar {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }
}

.field {
  width: 100%;
  padding: 7px 10px;
  border-radius: var(--r-md);
  border: 1px solid var(--line-strong);
  background: rgba(11, 33, 25, 0.55);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: var(--fs-sm);
  transition: border-color var(--dur-1) var(--ease);
}
.field:focus {
  outline: none;
  border-color: var(--gold-500);
}
.field::placeholder {
  color: var(--text-3);
}
.field--sm {
  font-size: var(--fs-xs);
  padding: 6px 9px;
}
.field--area {
  resize: vertical;
  line-height: 1.7;
}
select.field {
  cursor: pointer;
}
/* 下拉选项在深色底上会变成系统默认的白底黑字，这里强制跟随主题 */
select.field option {
  background: #123026;
  color: #eef3f0;
}

/* ---------- 表格 ---------- */
.tablewrap {
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: rgba(18, 48, 38, 0.42);
}
.rt {
  width: 100%;
  min-width: 900px;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}
.rt th {
  text-align: left;
  padding: var(--sp-3) var(--sp-4);
  font-size: var(--fs-cap);
  font-weight: 500;
  color: var(--text-3);
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}
.rt td {
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--line);
  vertical-align: middle;
}
.rt tbody tr:last-child td {
  border-bottom: none;
}
.rt tbody tr:hover td {
  background: rgba(146, 178, 165, 0.05);
}
/* 已下架的行压暗：一眼能从列表里分出"游客看不到的那些" */
.rt__tr--off td {
  opacity: 0.6;
}
.rt__name {
  min-width: 220px;
}
.rt__title {
  color: #fff;
  font-weight: 600;
}
.rt__meta {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: 4px;
  font-size: var(--fs-cap);
  color: var(--text-3);
}
.rt__meta code {
  font-family: var(--font-mono, monospace);
}
.rt__ops {
  white-space: nowrap;
  text-align: right;
}
.rt__danger {
  color: #e8a396;
}
.rt__danger:hover:not(:disabled) {
  color: #ffd9d0;
  border-color: rgba(168, 64, 43, 0.6);
}

/* ---------- 对话框 ---------- */
.modal {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal, 60);
  display: grid;
  place-items: center;
  padding: var(--sp-5);
  background: rgba(4, 14, 10, 0.66);
  backdrop-filter: blur(3px);
}
.modal__box {
  width: min(720px, 100%);
  max-height: 86vh;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line-strong);
  border-radius: var(--r-lg);
  background: #0e241c;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.45);
}
.modal__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: var(--sp-4) var(--sp-5);
  border-bottom: 1px solid var(--line);
}
.modal__head .h3 {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
}
.modal__body {
  padding: var(--sp-5);
  overflow-y: auto;
}
.modal__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-3);
  padding: var(--sp-4) var(--sp-5);
  border-top: 1px solid var(--line);
}

/* ---------- 表单字段 ---------- */
.fld {
  display: block;
}
.fld__label {
  display: block;
  margin-bottom: 5px;
  font-size: var(--fs-cap);
  color: var(--text-2);
}
.fld__pair {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sp-3);
}
.fld__triple {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-3);
}
.fld__hint {
  margin: -4px 0 0;
  font-size: var(--fs-cap);
  line-height: 1.7;
  color: var(--text-3);
}
@media (max-width: 720px) {
  .fld__pair,
  .fld__triple {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* ---------- 配图 ---------- */
.hidden-file {
  display: none;
}
.imgs {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--sp-3);
}
.imgs__item {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
  background: rgba(11, 33, 25, 0.5);
}
.imgs__item img {
  display: block;
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
}
.imgs__cap {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sp-2) var(--sp-3) 0;
  font-size: var(--fs-cap);
  color: var(--text-3);
}
.imgs__ops {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: var(--sp-2) var(--sp-3) var(--sp-3);
}
.imgs__ops .btn {
  padding: 3px 8px;
  font-size: var(--fs-cap);
}
</style>
