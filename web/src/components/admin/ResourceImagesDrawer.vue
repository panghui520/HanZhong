<script setup lang="ts">
/**
 * 配图抽屉（复用 M9 的 /api/admin/media/poi-images 那一组接口）
 *
 * 只做四件事：选图 → 传 → 排序 → 设封面 / 删。
 *
 * ★ **错误提示显示在抽屉内部**，而不是走页面顶部的提示条 ——
 *   抽屉盖住了大半屏，页面顶部的提示条会被遮罩压暗，用户看不见，
 *   表现就是"点了上传，什么都没发生"。
 *
 * ★ 上传/删除后**同步刷新封面缓存**（`reloadCovers`）。游客端的卡片封面
 *   走的是另一份缓存，不刷新的话会出现"后台删了图、前台还显示着"。
 */
import { ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  adminDeletePoiImage,
  adminListPoiImages,
  adminReorderPoiImages,
  adminUpdatePoiImage,
  adminUploadPoiImage,
} from '@/api/media'
import { reloadCovers } from '@/composables/useCovers'
import type { AdminPoi, AdminProduct, PoiImage as PoiImageRow } from '@/types'

const open = ref(false)
const target = ref<AdminPoi | null>(null)
const rows = ref<PoiImageRow[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const fileInput = ref<HTMLInputElement | null>(null)

function errText(e: unknown, fallback: string) {
  return e instanceof ApiError ? e.message : fallback
}

async function openFor(row: AdminPoi | AdminProduct) {
  // 农产品的"配图"实际管理的是**产地资源点**的图 —— 见 ResourceTable 的按钮注释
  target.value = row as AdminPoi
  open.value = true
  error.value = ''
  await load()
}

async function load() {
  const p = target.value
  if (!p) return
  loading.value = true
  error.value = ''
  try {
    rows.value = await adminListPoiImages(p.id)
  } catch (e) {
    error.value = errText(e, '配图加载失败')
  } finally {
    loading.value = false
  }
}

async function run(fn: () => Promise<unknown>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await fn()
  } catch (e) {
    error.value = errText(e, '操作失败，请重试')
  } finally {
    busy.value = false
  }
}

function pick() {
  fileInput.value?.click()
}

function onPicked(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  const p = target.value
  input.value = ''
  if (!file || !p) return
  void run(async () => {
    await adminUploadPoiImage(p.id, file)
    await load()
    await reloadCovers()
  })
}

function remove(img: PoiImageRow) {
  if (!window.confirm('删除这张配图？文件会一并从磁盘删除。')) return
  void run(async () => {
    await adminDeletePoiImage(img.id)
    await load()
    await reloadCovers()
  })
}

function setCover(img: PoiImageRow) {
  void run(async () => {
    await adminUpdatePoiImage(img.id, { is_cover: 1 })
    await load()
    await reloadCovers()
  })
}

/** 上移 / 下移。后端要的是"调整后的完整顺序"，不是两两交换 */
function move(i: number, dir: -1 | 1) {
  const p = target.value
  const j = i + dir
  if (!p || j < 0 || j >= rows.value.length) return
  const ids = rows.value.map((r) => r.id)
  ;[ids[i], ids[j]] = [ids[j], ids[i]]
  void run(async () => {
    rows.value = await adminReorderPoiImages(p.id, ids)
    await reloadCovers()
  })
}

defineExpose({ openFor })
</script>

<template>
  <Transition name="adm-fade">
    <div v-if="open" class="drawer" @click.self="open = false">
      <div class="drawer__box">
        <div class="drawer__head">
          <h3 class="h3">
            配图
            <span class="muted small">{{ target?.name }}</span>
          </h3>
          <button class="btn btn-ghost btn-sm" @click="open = false">关闭</button>
        </div>

        <div class="drawer__body stack-3">
          <input
            ref="fileInput"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            class="hidden-file"
            @change="onPicked"
          />

          <div v-if="error" class="imgs__err">{{ error }}</div>

          <div class="row-between">
            <span class="muted small"> 第一张自动成为封面；没有配图时游客端回落到手绘插画。 </span>
            <button class="btn btn-primary btn-sm" :disabled="busy" @click="pick">上传配图</button>
          </div>

          <div v-if="loading" class="skeleton" style="height: 120px; border-radius: 10px" />
          <div v-else-if="!rows.length" class="empty">
            <div class="empty__title">还没有配图</div>
            <div class="empty__desc">游客端会用手绘插画兜底，不会开天窗</div>
          </div>
          <div v-else class="imgs">
            <figure v-for="(img, i) in rows" :key="img.id" class="imgs__item">
              <img :src="img.url" :alt="img.alt_text || ''" />
              <figcaption class="imgs__cap">
                <span v-if="img.is_cover === 1" class="tag tag-gold">封面</span>
                <span class="imgs__idx num">#{{ i + 1 }}</span>
              </figcaption>
              <div class="imgs__ops">
                <button class="btn btn-ghost btn-sm" :disabled="busy || i === 0" @click="move(i, -1)">
                  ↑
                </button>
                <button
                  class="btn btn-ghost btn-sm"
                  :disabled="busy || i === rows.length - 1"
                  @click="move(i, 1)"
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
                <button class="btn btn-ghost btn-sm rt__danger" :disabled="busy" @click="remove(img)">
                  删除
                </button>
              </div>
            </figure>
          </div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.imgs__err {
  padding: var(--sp-2) var(--sp-3);
  font-size: var(--fs-cap);
  color: #f3d3ca;
  background: rgba(168, 64, 43, 0.22);
  border: 1px solid rgba(168, 64, 43, 0.5);
  border-radius: var(--r-md);
}
</style>
