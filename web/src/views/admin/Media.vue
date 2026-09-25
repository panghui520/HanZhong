<script setup lang="ts">
/**
 * 图片管理（M9）
 *
 * 两块：首页轮播（site_banner）与景点配图（poi_image）。
 * 四项操作齐全：上传 / 替换 / 删除 / 排序。
 *
 * 几条刻意的设计取舍：
 *
 * 1. **排序用上移/下移，不做拖拽。** 后端要的是"期望的完整顺序"，
 *    拖拽库（vuedraggable 之类）引入一个新依赖、一套新交互，收益只是少点两下；
 *    而上移/下移对单步调整更快，也不会出现"拖到一半松开"的中间态。
 *
 * 2. **改文案与换图分成两个动作。** 改标题不会误把图覆盖掉 —— 这是后端
 *    接口刻意分开的（PATCH 改文案 / PUT 换图），前端也照着分开呈现。
 *
 * 3. **每次写操作后刷新封面缓存**（reloadCovers），否则游客端页面上的
 *    缩略图要等下一次整页刷新才会变，运营会以为"传了没生效"。
 *
 * 4. **所有写操作都串行化**（busy 标记）。同时点两个按钮会产生两个并发写，
 *    对同一景点的排序尤其危险：两个请求各自基于不同的"当前顺序"算新顺序，
 *    后到的会把先到的结果覆盖掉。
 */
import { computed, onMounted, ref } from 'vue'
import PoiImage from '@/components/PoiImage.vue'
import { getPois } from '@/api/citypack'
import {
  adminClearBannerImage,
  adminCreateBanner,
  adminDeleteBanner,
  adminDeletePoiImage,
  adminListBanners,
  adminListPoiImages,
  adminReorderBanners,
  adminReorderPoiImages,
  adminReplaceBannerImage,
  adminReplacePoiImage,
  adminUpdateBanner,
  adminUpdatePoiImage,
  adminUploadPoiImage,
} from '@/api/media'
import { ApiError } from '@/api/http'
import { reloadCovers } from '@/composables/useCovers'
import { SCENE_LABELS, SCENE_VARIANTS } from '@/utils/scene'
import { BUSINESS_LABEL, type Poi, type PoiImage as PoiImageRow, type SiteBanner } from '@/types'

/**
 * 手写 SVG 的变体清单，供"新建帧时选一个兜底画面"用。
 * 直接从 `@/utils/scene` 生成而不是在这里再抄一份 ——
 * 抄一份的话，将来加了新变体、这里忘了加，下拉框里就会缺一项。
 */
const SCENES = SCENE_VARIANTS.map((value) => ({ value, label: SCENE_LABELS[value] }))

const tab = ref<'banner' | 'poi'>('banner')
const busy = ref(false)
const notice = ref<{ type: 'ok' | 'err'; text: string } | null>(null)

/** 提示条。成功用绿色、失败用红色，3.5 秒后自动消失 */
let noticeTimer: number | undefined
function say(type: 'ok' | 'err', text: string) {
  notice.value = { type, text }
  if (noticeTimer !== undefined) window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => (notice.value = null), 3500)
}

/**
 * 统一的写操作包装。
 * 把"串行化 + 错误提示 + 刷新封面缓存"三件事收在一处 ——
 * 每个动作各写一遍的话，迟早有一个忘了刷新缓存或忘了 catch。
 */
async function write(okText: string, fn: () => Promise<unknown>) {
  if (busy.value) return
  busy.value = true
  try {
    await fn()
    await reloadCovers()
    say('ok', okText)
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : '操作失败，请重试'
    say('err', msg)
  } finally {
    busy.value = false
  }
}

// ============================================================
// 轮播图
// ============================================================

const banners = ref<SiteBanner[]>([])
const bannerLoading = ref(true)
/** 哪些帧的文案被改过但还没保存 */
const dirty = ref<Record<number, boolean>>({})

async function loadBanners() {
  bannerLoading.value = true
  try {
    banners.value = await adminListBanners()
    dirty.value = {}
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '轮播列表加载失败')
  } finally {
    bannerLoading.value = false
  }
}

/** 新建帧的临时表单 */
const newBanner = ref({ scene: 'qinling', title: '', eyebrow: '' })
const newBannerFile = ref<File | null>(null)

function saveBanner(b: SiteBanner) {
  void write('文案已保存', async () => {
    const saved = await adminUpdateBanner(b.id, {
      scene: b.scene,
      eyebrow: b.eyebrow,
      title: b.title,
      subtitle: b.subtitle,
      description: b.description,
      link_url: b.link_url,
      cta: b.cta,
    })
    // 用服务端返回的对象覆盖本地，避免"本地看着改了、服务端没接受"的偏差
    Object.assign(b, saved)
    dirty.value[b.id] = false
  })
}

function toggleBanner(b: SiteBanner) {
  const next = b.enabled === 1 ? 0 : 1
  void write(next === 1 ? '该帧已启用' : '该帧已停用', async () => {
    const saved = await adminUpdateBanner(b.id, { enabled: next })
    Object.assign(b, saved)
  })
}

function createBanner() {
  void write('已新建一帧，可继续编辑文案', async () => {
    await adminCreateBanner(newBannerFile.value, {
      scene: newBanner.value.scene,
      title: newBanner.value.title || '未命名',
      eyebrow: newBanner.value.eyebrow,
      enabled: 1,
    })
    newBanner.value = { scene: 'qinling', title: '', eyebrow: '' }
    newBannerFile.value = null
    await loadBanners()
  })
}

function deleteBanner(b: SiteBanner) {
  if (!window.confirm(`删除轮播帧「${b.title}」？该操作不可撤销。`)) return
  void write('已删除该帧', async () => {
    await adminDeleteBanner(b.id)
    await loadBanners()
  })
}

function clearBannerImage(b: SiteBanner) {
  if (!window.confirm('清除这一帧的图片？文案与链接会保留，画面回到手绘插画。')) return
  void write('已清除图片，回到手绘插画', async () => {
    Object.assign(b, await adminClearBannerImage(b.id))
  })
}

/** 上移 / 下移一帧。传"调整后的完整顺序"，后端据此重编号 */
function moveBanner(i: number, dir: -1 | 1) {
  const j = i + dir
  if (j < 0 || j >= banners.value.length) return
  const ids = banners.value.map((b) => b.id)
  ;[ids[i], ids[j]] = [ids[j], ids[i]]
  void write('顺序已更新', async () => {
    banners.value = await adminReorderBanners(ids)
  })
}

// ============================================================
// 景点配图
// ============================================================

const pois = ref<Poi[]>([])
const poiLoading = ref(true)
const keyword = ref('')
const selectedPoiId = ref('')
const poiImages = ref<PoiImageRow[]>([])
const imageLoading = ref(false)
/** poi_id -> 该景点的图片张数。列表上直接标出来，不用逐个点进去看 */
const counts = ref<Record<string, number>>({})

const filteredPois = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return pois.value
  return pois.value.filter(
    (p) =>
      p.name.toLowerCase().includes(k) ||
      p.district.toLowerCase().includes(k) ||
      p.id.toLowerCase().includes(k)
  )
})

const selectedPoi = computed(() => pois.value.find((p) => p.id === selectedPoiId.value))

const coveredCount = computed(() => Object.keys(counts.value).length)
const totalImages = computed(() => Object.values(counts.value).reduce((a, b) => a + b, 0))

async function loadPois() {
  poiLoading.value = true
  try {
    const [list, all] = await Promise.all([getPois(), adminListPoiImages()])
    pois.value = list
    // 一次拿到全部配图再自己分组，比"每个景点发一次请求"省 41 个请求
    const c: Record<string, number> = {}
    for (const im of all) c[im.poi_id] = (c[im.poi_id] ?? 0) + 1
    counts.value = c
    if (!selectedPoiId.value && list.length) selectPoi(list[0].id)
  } catch (e) {
    say('err', e instanceof ApiError ? e.message : '景点列表加载失败')
  } finally {
    poiLoading.value = false
  }
}

async function selectPoi(id: string) {
  selectedPoiId.value = id
  imageLoading.value = true
  try {
    poiImages.value = await adminListPoiImages(id)
  } catch (e) {
    poiImages.value = []
    say('err', e instanceof ApiError ? e.message : '配图加载失败')
  } finally {
    imageLoading.value = false
  }
}

/** 重新读当前景点的图 + 更新列表上的张数标记 */
async function refreshPoiImages() {
  await selectPoi(selectedPoiId.value)
  const all = await adminListPoiImages()
  const c: Record<string, number> = {}
  for (const im of all) c[im.poi_id] = (c[im.poi_id] ?? 0) + 1
  counts.value = c
}

function uploadPoiImage(file: File) {
  void write('配图已上传', async () => {
    await adminUploadPoiImage(selectedPoiId.value, file)
    await refreshPoiImages()
  })
}

function replacePoiImage(im: PoiImageRow, file: File) {
  void write('图片已替换（排序位置与封面身份保留）', async () => {
    await adminReplacePoiImage(im.id, file)
    await refreshPoiImages()
  })
}

function setCover(im: PoiImageRow) {
  void write('已设为该景点的封面', async () => {
    await adminUpdatePoiImage(im.id, { is_cover: 1 })
    await refreshPoiImages()
  })
}

function saveAlt(im: PoiImageRow) {
  void write('备注已保存', async () => {
    await adminUpdatePoiImage(im.id, { alt_text: im.alt_text ?? '' })
    await refreshPoiImages()
  })
}

function deletePoiImage(im: PoiImageRow) {
  if (!window.confirm('删除这张配图？文件会一并从磁盘删除，不可撤销。')) return
  void write('已删除该配图', async () => {
    await adminDeletePoiImage(im.id)
    await refreshPoiImages()
  })
}

function movePoiImage(i: number, dir: -1 | 1) {
  const j = i + dir
  if (j < 0 || j >= poiImages.value.length) return
  const ids = poiImages.value.map((x) => x.id)
  ;[ids[i], ids[j]] = [ids[j], ids[i]]
  void write('顺序已更新', async () => {
    poiImages.value = await adminReorderPoiImages(selectedPoiId.value, ids)
  })
}

// ============================================================
// 文件选择：一个隐藏 input 服务四种场景
// ============================================================
//
// 每个按钮各配一个 input 也行，但页面上会有 40+ 个文件框，既占 DOM
// 又要为"哪一行触发的"各写一套逻辑。改成记下"这次选文件是要干什么"，
// 选完统一分发。

type Pending =
  | { kind: 'banner-create' }
  | { kind: 'banner-replace'; banner: SiteBanner }
  | { kind: 'poi-upload' }
  | { kind: 'poi-replace'; image: PoiImageRow }

let pending: Pending | null = null
const fileInput = ref<HTMLInputElement | null>(null)

function pickFile(p: Pending) {
  pending = p
  fileInput.value?.click()
}

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  // 立刻清空：不清的话连续选同一个文件不会触发 change
  input.value = ''
  const p = pending
  pending = null
  if (!file || !p) return

  if (p.kind === 'banner-create') {
    newBannerFile.value = file
    say('ok', `已选择图片「${file.name}」，点"新建帧"提交`)
    return
  }
  if (p.kind === 'banner-replace') {
    void write('轮播图片已替换', async () => {
      Object.assign(p.banner, await adminReplaceBannerImage(p.banner.id, file))
    })
    return
  }
  if (p.kind === 'poi-upload') {
    uploadPoiImage(file)
    return
  }
  replacePoiImage(p.image, file)
}

onMounted(() => {
  void loadBanners()
  void loadPois()
})
</script>

<template>
  <div class="media">
    <!-- 唯一的文件选择器，四种场景共用 -->
    <input
      ref="fileInput"
      type="file"
      accept="image/jpeg,image/png,image/webp"
      class="media__file"
      @change="onFileChange"
    />

    <header class="media__head">
      <div>
        <span class="eyebrow">媒体与配图</span>
        <h1 class="h1 media__title">图片管理</h1>
        <p class="media__sub">
          首页轮播与 {{ pois.length || 42 }} 处文旅资源的配图。支持上传、替换、删除与排序；
          未配图的资源会自动回落到手绘插画，页面不会开天窗。
        </p>
      </div>
      <div class="media__meta">
        <span class="badge-sim">仅运营可操作</span>
      </div>
    </header>

    <Transition name="notice">
      <div v-if="notice" class="notice" :class="`notice--${notice.type}`">{{ notice.text }}</div>
    </Transition>

    <div class="tabs">
      <button class="tab" :class="{ 'tab--on': tab === 'banner' }" @click="tab = 'banner'">
        首页轮播
        <span class="tab__n">{{ banners.length }}</span>
      </button>
      <button class="tab" :class="{ 'tab--on': tab === 'poi' }" @click="tab = 'poi'">
        景点配图
        <span class="tab__n">{{ coveredCount }} / {{ pois.length || 42 }}</span>
      </button>
    </div>

    <!-- ==================== 首页轮播 ==================== -->
    <section v-if="tab === 'banner'" class="stack-5">
      <div class="panel">
        <div class="panel__head">
          <h2 class="h3">新建一帧</h2>
          <span class="muted small">图片可留空，先用插画占位，之后随时替换</span>
        </div>
        <div class="newrow">
          <select v-model="newBanner.scene" class="field field--sm">
            <option v-for="s in SCENES" :key="s.value" :value="s.value">
              兜底画面：{{ s.label }}
            </option>
          </select>
          <input
            v-model="newBanner.eyebrow"
            class="field field--sm"
            placeholder="眉标（如：智慧文旅 · 乡村振兴）"
          />
          <input v-model="newBanner.title" class="field field--sm" placeholder="主标题" />
          <button class="btn btn-ghost btn-sm" :disabled="busy" @click="pickFile({ kind: 'banner-create' })">
            {{ newBannerFile ? `已选：${newBannerFile.name}` : '选择图片' }}
          </button>
          <button class="btn btn-primary btn-sm" :disabled="busy" @click="createBanner">新建帧</button>
        </div>
      </div>

      <div v-if="bannerLoading" class="stack-3">
        <div v-for="i in 4" :key="i" class="skeleton" style="height: 168px; border-radius: 10px" />
      </div>

      <div v-else-if="!banners.length" class="empty">
        <div class="empty__title">还没有轮播帧</div>
        <div class="empty__desc">首页会自动使用内置的 4 帧插画兜底，不会空白</div>
      </div>

      <div v-else class="stack-3">
        <article v-for="(b, i) in banners" :key="b.id" class="brow" :class="{ 'brow--off': b.enabled !== 1 }">
          <div class="brow__preview">
            <PoiImage :src="b.url" :scene="b.scene" ratio="16 / 9" />
            <span class="brow__idx num">{{ i + 1 }}</span>
            <span v-if="!b.url" class="brow__noimg">未传图 · 插画兜底</span>
          </div>

          <div class="brow__body">
            <div class="brow__fields">
              <input v-model="b.eyebrow" class="field" placeholder="眉标" @input="dirty[b.id] = true" />
              <input v-model="b.title" class="field field--strong" placeholder="主标题" @input="dirty[b.id] = true" />
              <input v-model="b.subtitle" class="field" placeholder="副标题" @input="dirty[b.id] = true" />
              <textarea
                v-model="b.description"
                class="field field--area"
                rows="2"
                placeholder="描述"
                @input="dirty[b.id] = true"
              />
              <div class="brow__pair">
                <input v-model="b.link_url" class="field" placeholder="跳转地址（如 /explore）" @input="dirty[b.id] = true" />
                <input v-model="b.cta" class="field" placeholder="按钮文案" @input="dirty[b.id] = true" />
                <select v-model="b.scene" class="field" @change="dirty[b.id] = true">
                  <option v-for="s in SCENES" :key="s.value" :value="s.value">{{ s.label }}</option>
                </select>
              </div>
            </div>

            <div class="brow__ops">
              <button
                class="btn btn-primary btn-sm"
                :disabled="busy || !dirty[b.id]"
                @click="saveBanner(b)"
              >
                {{ dirty[b.id] ? '保存文案' : '已保存' }}
              </button>
              <button class="btn btn-ghost btn-sm" :disabled="busy" @click="pickFile({ kind: 'banner-replace', banner: b })">
                {{ b.url ? '替换图片' : '上传图片' }}
              </button>
              <button v-if="b.url" class="btn btn-ghost btn-sm" :disabled="busy" @click="clearBannerImage(b)">
                清除图片
              </button>
              <button class="btn btn-ghost btn-sm" :disabled="busy || i === 0" @click="moveBanner(i, -1)">
                ↑ 上移
              </button>
              <button
                class="btn btn-ghost btn-sm"
                :disabled="busy || i === banners.length - 1"
                @click="moveBanner(i, 1)"
              >
                ↓ 下移
              </button>
              <button class="btn btn-ghost btn-sm" :disabled="busy" @click="toggleBanner(b)">
                {{ b.enabled === 1 ? '停用' : '启用' }}
              </button>
              <button class="btn btn-ghost btn-sm brow__del" :disabled="busy" @click="deleteBanner(b)">
                删除
              </button>
            </div>
          </div>
        </article>
      </div>
    </section>

    <!-- ==================== 景点配图 ==================== -->
    <section v-else class="poi">
      <aside class="panel poi__side">
        <div class="panel__head">
          <h2 class="h3">资源列表</h2>
          <span class="muted small">{{ coveredCount }} 处已配图 · 共 {{ totalImages }} 张</span>
        </div>
        <input v-model="keyword" class="field field--sm" placeholder="搜索名称 / 区县 / 编号" />

        <div v-if="poiLoading" class="stack-2 poi__list">
          <div v-for="i in 8" :key="i" class="skeleton" style="height: 40px" />
        </div>
        <div v-else class="poi__list">
          <button
            v-for="p in filteredPois"
            :key="p.id"
            class="prow"
            :class="{ 'prow--on': p.id === selectedPoiId }"
            @click="selectPoi(p.id)"
          >
            <span class="prow__name">{{ p.name }}</span>
            <span class="prow__meta">
              <span class="muted cap">{{ p.district }} · {{ BUSINESS_LABEL[p.business_type] }}</span>
              <span v-if="counts[p.id]" class="tag tag-gold prow__n">{{ counts[p.id] }} 张</span>
              <span v-else class="tag prow__n">未配图</span>
            </span>
          </button>
          <p v-if="!filteredPois.length" class="muted small" style="padding: 12px 0">没有匹配的资源</p>
        </div>
      </aside>

      <div class="poi__main">
        <div v-if="!selectedPoi" class="empty">
          <div class="empty__title">请先在左侧选择一个资源</div>
        </div>

        <template v-else>
          <div class="panel">
            <div class="panel__head">
              <div>
                <h2 class="h3">{{ selectedPoi.name }}</h2>
                <span class="muted small">
                  {{ selectedPoi.id }} · {{ selectedPoi.district }} ·
                  {{ BUSINESS_LABEL[selectedPoi.business_type] }}
                </span>
              </div>
              <button class="btn btn-primary btn-sm" :disabled="busy" @click="pickFile({ kind: 'poi-upload' })">
                上传配图
              </button>
            </div>
            <p class="muted small">
              列表第一张会作为列表页卡片上的图（除非另有指定封面）。支持 JPG / PNG / WebP，单张不超过 5MB。
            </p>
          </div>

          <div v-if="imageLoading" class="stack-3">
            <div v-for="i in 2" :key="i" class="skeleton" style="height: 120px; border-radius: 10px" />
          </div>

          <div v-else-if="!poiImages.length" class="panel empty">
            <div class="empty__title">这个资源还没有配图</div>
            <div class="empty__desc">
              游客端此刻显示的是手绘插画（{{ selectedPoi.scene || 'qinling' }} 变体）。上传真实照片即可替换。
            </div>
          </div>

          <div v-else class="stack-3">
            <article v-for="(im, i) in poiImages" :key="im.id" class="irow">
              <div class="irow__thumb">
                <img v-if="im.url" :src="im.url" :alt="im.alt_text || selectedPoi.name" loading="lazy" />
                <span class="irow__idx num">{{ i + 1 }}</span>
                <span v-if="im.is_cover === 1" class="irow__cover">封面</span>
              </div>

              <div class="irow__body">
                <input
                  v-model="im.alt_text"
                  class="field field--sm"
                  placeholder="图片备注（用于无障碍描述）"
                />
                <span class="muted cap irow__path">{{ im.image_path }}</span>
              </div>

              <div class="irow__ops">
                <button class="btn btn-primary btn-sm" :disabled="busy" @click="saveAlt(im)">保存备注</button>
                <button
                  class="btn btn-ghost btn-sm"
                  :disabled="busy || im.is_cover === 1"
                  @click="setCover(im)"
                >
                  {{ im.is_cover === 1 ? '已是封面' : '设为封面' }}
                </button>
                <button class="btn btn-ghost btn-sm" :disabled="busy" @click="pickFile({ kind: 'poi-replace', image: im })">
                  替换
                </button>
                <button class="btn btn-ghost btn-sm" :disabled="busy || i === 0" @click="movePoiImage(i, -1)">
                  ↑
                </button>
                <button
                  class="btn btn-ghost btn-sm"
                  :disabled="busy || i === poiImages.length - 1"
                  @click="movePoiImage(i, 1)"
                >
                  ↓
                </button>
                <button class="btn btn-ghost btn-sm irow__del" :disabled="busy" @click="deletePoiImage(im)">
                  删除
                </button>
              </div>
            </article>
          </div>
        </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.media__file {
  display: none;
}

/* ---------- 页头 ---------- */
.media__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-5);
  margin-bottom: var(--sp-5);
}
.media__title {
  margin-top: var(--sp-2);
}
.media__sub {
  margin-top: var(--sp-3);
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
.tab__n {
  margin-left: var(--sp-2);
  padding: 1px 7px;
  border-radius: var(--r-pill);
  font-size: var(--fs-cap);
  background: rgba(146, 178, 165, 0.16);
  color: var(--text-2);
}

/* ---------- 面板与表单 ---------- */
.panel {
  padding: var(--sp-5);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: rgba(18, 48, 38, 0.5);
}
.panel__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
  margin-bottom: var(--sp-4);
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
.field--strong {
  font-weight: 600;
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

.newrow {
  display: grid;
  grid-template-columns: 1.1fr 1.2fr 1.2fr auto auto;
  gap: var(--sp-3);
  align-items: center;
}
@media (max-width: 1080px) {
  .newrow {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }
}

/* ---------- 轮播行 ---------- */
.brow {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: var(--sp-5);
  padding: var(--sp-4);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: rgba(18, 48, 38, 0.42);
  transition: opacity var(--dur-2) var(--ease);
}
.brow--off {
  opacity: 0.55;
}
.brow__preview {
  position: relative;
  border-radius: var(--r-md);
  overflow: hidden;
  border: 1px solid var(--line);
}
.brow__idx {
  position: absolute;
  left: 8px;
  top: 8px;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: var(--r-sm);
  font-size: var(--fs-cap);
  background: rgba(11, 33, 25, 0.72);
  color: #fff;
}
.brow__noimg {
  position: absolute;
  left: 8px;
  bottom: 8px;
  padding: 2px 8px;
  border-radius: var(--r-sm);
  font-size: var(--fs-cap);
  background: rgba(11, 33, 25, 0.72);
  color: var(--gold-300);
}
.brow__fields {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.brow__pair {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr;
  gap: var(--sp-2);
}
.brow__ops {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin-top: var(--sp-3);
}
.brow__del,
.irow__del {
  color: #e8a99a;
  border-color: rgba(168, 64, 43, 0.45);
}
.brow__del:hover:not(:disabled),
.irow__del:hover:not(:disabled) {
  background: rgba(168, 64, 43, 0.18);
  border-color: rgba(168, 64, 43, 0.8);
}

@media (max-width: 1080px) {
  .brow {
    grid-template-columns: minmax(0, 1fr);
  }
  .brow__pair {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* ---------- 景点配图：左列表 + 右详情 ---------- */
.poi {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: var(--sp-5);
  align-items: start;
}
.poi__side {
  position: sticky;
  top: 84px;
}
.poi__list {
  margin-top: var(--sp-3);
  max-height: 62vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  scrollbar-width: thin;
}
.prow {
  display: flex;
  flex-direction: column;
  gap: 3px;
  width: 100%;
  text-align: left;
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-md);
  border-left: 2px solid transparent;
  transition: all var(--dur-1) var(--ease);
}
.prow:hover {
  background: rgba(146, 178, 165, 0.1);
}
.prow--on {
  background: rgba(46, 123, 196, 0.16);
  border-left-color: var(--tech-500);
}
.prow__name {
  font-size: var(--fs-sm);
  color: var(--text);
}
.prow--on .prow__name {
  font-weight: 600;
  color: #fff;
}
.prow__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
}
.prow__n {
  flex: none;
  padding: 1px 7px;
  border-radius: var(--r-pill);
  font-size: var(--fs-cap);
  /* 不用全局的 .tag：它是为游客端米白底设计的，在管理端深色底上
     会变成一块刺眼的白药丸。这里按深色表面单独给一套。 */
  color: var(--text-3);
  background: rgba(146, 178, 165, 0.12);
  border: 1px solid var(--line);
}
.prow__n--on {
  color: var(--gold-300);
  background: rgba(192, 154, 78, 0.16);
  border-color: rgba(192, 154, 78, 0.4);
}
.poi__main {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  min-width: 0;
}

/* ---------- 配图行 ---------- */
.irow {
  display: grid;
  grid-template-columns: 132px minmax(0, 1fr) auto;
  gap: var(--sp-4);
  align-items: center;
  padding: var(--sp-3);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: rgba(18, 48, 38, 0.42);
}
.irow__thumb {
  position: relative;
  aspect-ratio: 4 / 3;
  border-radius: var(--r-md);
  overflow: hidden;
  border: 1px solid var(--line);
  background: var(--surface-2);
}
.irow__thumb img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.irow__idx {
  position: absolute;
  left: 6px;
  top: 6px;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: var(--r-sm);
  font-size: 11px;
  background: rgba(11, 33, 25, 0.72);
  color: #fff;
}
.irow__cover {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 1px 7px;
  border-radius: var(--r-sm);
  font-size: 11px;
  color: #2b1e07;
  background: var(--gold-500);
}
.irow__body {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  min-width: 0;
}
.irow__path {
  word-break: break-all;
  font-family: var(--font-num);
}
.irow__ops {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  justify-content: flex-end;
}

@media (max-width: 1080px) {
  .poi {
    grid-template-columns: minmax(0, 1fr);
  }
  .poi__side {
    position: static;
  }
  .irow {
    grid-template-columns: 110px minmax(0, 1fr);
  }
  .irow__ops {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }
}
</style>
