<script setup lang="ts">
/**
 * 资源列表表格（四个业务页共用）
 *
 * **表格只是管理工具，不是页面主体。** 这句话体现在结构上：每个业务页里
 * 图表在上、表格在下（`§二` 的要求），表格本身不做任何"美化"——
 * 它就是一张能搜索、能上下架、能编辑的清单。
 *
 * 类名沿用旧 `Resources.vue` 的 `.rt` / `.rt__ops` / `.rt__danger` ——
 * 已有的 CDP 探针按这些选择器断言，改名等于让一批验证无谓失效。
 *
 * ★ `highlightId`：点图表某一项之后，这里把对应的行**高亮并滚进视野**。
 *   这是 `§十八` 要的"点击图表某项 → 当前资源高亮"。没有它的话，
 *   图表点一下只有图上变色，用户还得自己在下方的几十行里找那一条。
 */
import { watch } from 'vue'
import { BUSINESS_LABEL, type AdminPoi, type AdminProduct } from '@/types'

const props = withDefaults(
  defineProps<{
    rows: (AdminPoi | AdminProduct)[]
    isPoi: boolean
    noun: string
    busy?: boolean
    /** 图表选中的资源 id，用来高亮对应行 */
    highlightId?: string | null
    /** 农产品页：有产地资源点的行才给「产地配图」 */
    showImages?: boolean
    /** 空态提示语（取决于当前有没有筛选条件） */
    emptyHint?: string
    canEdit: (row: AdminPoi | AdminProduct) => boolean
    editHint: (row: AdminPoi | AdminProduct) => string
    removeHint: (row: AdminPoi | AdminProduct) => string
  }>(),
  { busy: false, highlightId: null, showImages: true, emptyHint: '' }
)

const emit = defineEmits<{
  (e: 'edit', row: AdminPoi | AdminProduct): void
  (e: 'toggle', row: AdminPoi | AdminProduct): void
  (e: 'images', row: AdminPoi | AdminProduct): void
  (e: 'remove', row: AdminPoi | AdminProduct): void
}>()

/** 图表选中后把这一行滚进视野。用 `scrollIntoView` 的 `block:'center'`，
 *  比 `'nearest'` 好：行在视口边缘时 `'nearest'` 只挪一点点，用户看不出发生了事 */
watch(
  () => props.highlightId,
  (id) => {
    if (!id) return
    requestAnimationFrame(() => {
      const el = document.querySelector(`[data-res-id="${id}"]`)
      el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    })
  }
)
</script>

<template>
  <div v-if="!rows.length" class="empty">
    <div class="empty__title">没有符合条件的{{ noun }}</div>
    <div class="empty__desc">{{ emptyHint || '换个关键词或状态试试' }}</div>
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
        <tr
          v-for="row in rows"
          :key="row.id"
          :data-res-id="row.id"
          :class="{
            'rt__tr--off': row.status !== 1,
            'rt__tr--hi': highlightId === row.id,
          }"
        >
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
            <button
              class="btn btn-ghost btn-sm"
              :disabled="busy || !canEdit(row)"
              :title="editHint(row)"
              @click="emit('edit', row)"
            >
              编辑
            </button>
            <button class="btn btn-ghost btn-sm" :disabled="busy" @click="emit('toggle', row)">
              {{ row.status === 1 ? '下架' : '上架' }}
            </button>
            <button
              v-if="isPoi"
              class="btn btn-ghost btn-sm"
              :disabled="busy"
              @click="emit('images', row)"
            >
              配图
            </button>
            <!--
              农产品没有自己的配图表（`poi_image` 只服务 poi）。这里给的是
              **产地资源点的配图** —— 农产品挂在产地/体验上，产地有配图。
              按钮文案写明"产地"，不写成"配图"，否则会让人以为在传产品图。
            -->
            <button
              v-else-if="showImages && (row as AdminProduct).poi_id"
              class="btn btn-ghost btn-sm"
              :disabled="busy"
              title="管理该农产品产地资源点的配图（农产品本身没有独立图库）"
              @click="emit('images', row)"
            >
              产地配图
            </button>
            <button
              class="btn btn-ghost btn-sm rt__danger"
              :disabled="busy || row.source !== 'ADMIN'"
              :title="removeHint(row)"
              @click="emit('remove', row)"
            >
              删除
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
/* 图表选中后高亮的那一行。用左侧金色竖线而不是整行底色 ——
   整行底色会和 hover 态打架，且下架行的 0.6 透明度会把底色也压暗 */
.rt__tr--hi td {
  background: rgba(192, 154, 78, 0.1);
  box-shadow: inset 0 0 0 1px rgba(192, 154, 78, 0.28);
}
.rt__tr--hi td:first-child {
  box-shadow:
    inset 2px 0 0 var(--gold-500),
    inset 0 0 0 1px rgba(192, 154, 78, 0.28);
}
.rt__tr--hi .rt__title {
  color: var(--gold-300);
}
</style>
