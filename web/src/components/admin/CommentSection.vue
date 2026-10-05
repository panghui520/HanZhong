<script setup lang="ts">
/**
 * 景点评论管理（原 `Resources.vue` 的第四个 tab）
 *
 * 重构时它没有变成一个一级页面，也没有被删掉 —— 它是**景点这一条业务线**的
 * 一部分（评论只挂在 poi 上，而且当前 2 条全在景区），所以落在景点页底部。
 *
 * 保留下来的两个细节，都是有原因的：
 *
 * 1. **景点下拉用全量景点**，不是从已有评论里推。运营想查的往往是
 *    "某个景点有没有评论"，而那恰恰可能是**一条评论都没有**的那个。
 * 2. **改完状态且当前正在按状态筛选时，要重拉列表。** 只就地覆盖会让这条
 *    带着新状态继续显示在"只看已隐藏"的结果里，看着像没生效。
 *
 * ★ `status` 的默认值是空（全部）—— 游客端只显示 APPROVED，
 *   但运营端默认看全部，否则"待审"的评论永远没人看见。
 */
import { computed, onMounted, ref } from 'vue'
import { ApiError } from '@/api/http'
import { getPois } from '@/api/citypack'
import { adminDeleteComment, adminListComments, adminSetCommentStatus } from '@/api/comments'
import { useNotice } from '@/composables/useNotice'
import type { AdminComment, CommentStatus, Poi } from '@/types'

const { notice, say } = useNotice()

const rows = ref<AdminComment[]>([])
const allPois = ref<Poi[]>([])
const loading = ref(false)
const busy = ref(false)
const poiFilter = ref('')
const status = ref<CommentStatus | ''>('')

const poiOptions = computed(() =>
  [...allPois.value].sort((a, b) => a.name.localeCompare(b.name, 'zh'))
)

function errText(e: unknown, fallback: string) {
  return e instanceof ApiError ? e.message : fallback
}

async function reload() {
  loading.value = true
  try {
    rows.value = await adminListComments({ poiId: poiFilter.value, status: status.value })
  } catch (e) {
    say('err', errText(e, '评论加载失败'))
  } finally {
    loading.value = false
  }
}

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

function setStatus(row: AdminComment, next: CommentStatus) {
  const verb: Record<CommentStatus, string> = { PENDING: '打回待审', APPROVED: '通过', HIDDEN: '隐藏' }
  void write(`已${verb[next]}这条评论`, async () => {
    const saved = await adminSetCommentStatus(row.id, next)
    // 用服务端返回的对象覆盖本地，避免"本地看着改了、服务端没接受"的偏差
    Object.assign(row, saved)
    if (status.value) await reload()
  })
}

function remove(row: AdminComment) {
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

/**
 * 时间显示到分钟。后端给的是 `2026-09-28T11:03:14`，
 * 秒对"审一条评论"没有意义，完整串还会把表格撑宽、被迫换行。
 */
function fmtTime(raw?: string) {
  return raw ? raw.replace('T', ' ').slice(0, 16) : '—'
}

onMounted(async () => {
  void reload()
  try {
    allPois.value = await getPois()
  } catch {
    /* 下拉取不到不影响列表本身 */
  }
})
</script>

<template>
  <section class="cs">
    <header class="cs__head">
      <div>
        <h2 class="h3">景点评论</h2>
        <p class="cs__sub">
          游客在景点详情页发表的评论。游客端只显示「已通过」的那些 ——
          默认列出全部状态，否则「待审核」的评论永远没人看见。
        </p>
      </div>
      <span class="badge-sim">真实用户提交</span>
    </header>

    <Transition name="notice">
      <div v-if="notice" class="notice" :class="`notice--${notice.type}`">{{ notice.text }}</div>
    </Transition>

    <div class="panel toolbar">
      <select v-model="poiFilter" class="field">
        <option value="">全部景点</option>
        <option v-for="p in poiOptions" :key="p.id" :value="p.id">{{ p.name }}</option>
      </select>
      <select v-model="status" class="field field--sm">
        <option value="">全部状态</option>
        <option value="APPROVED">已通过</option>
        <option value="HIDDEN">已隐藏</option>
        <option value="PENDING">待审核</option>
      </select>
      <button class="btn btn-ghost btn-sm" :disabled="loading" @click="reload">查询</button>
    </div>

    <div v-if="loading" class="skeleton" style="height: 120px; border-radius: 10px" />
    <div v-else-if="!rows.length" class="empty">
      <div class="empty__title">没有符合条件的评论</div>
      <div class="empty__desc">
        {{
          poiFilter || status
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
          <tr v-for="c in rows" :key="c.id" :class="{ 'rt__tr--off': c.status !== 'APPROVED' }">
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
                @click="setStatus(c, 'APPROVED')"
              >
                通过
              </button>
              <button
                v-if="c.status !== 'HIDDEN'"
                class="btn btn-ghost btn-sm"
                :disabled="busy"
                @click="setStatus(c, 'HIDDEN')"
              >
                隐藏
              </button>
              <button
                class="btn btn-ghost btn-sm rt__danger"
                :disabled="busy"
                @click="remove(c)"
              >
                删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.cs {
  margin-top: var(--sp-6);
}
.cs__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
  margin-bottom: var(--sp-4);
}
.cs__sub {
  margin-top: 6px;
  max-width: 860px;
  font-size: var(--fs-cap);
  line-height: 1.8;
  color: var(--text-3);
}
</style>
