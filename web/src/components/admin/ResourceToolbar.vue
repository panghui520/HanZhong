<script setup lang="ts">
/**
 * 资源列表工具条（搜索 + 状态筛选 + 新增）
 *
 * 四个业务页共用。`chips` 插槽留给页面放**自己的深链条件**
 * （农产品页的"产地 / 分类"、景点页的"业态"）——
 * 做成可点掉的 chip 而不是隐藏筛选：进来时看到的是子集，
 * 点 × 就是看这一类资源的全部。藏一个用户关不掉的筛选，
 * 他会以为库里只有这么多条。
 */
withDefaults(
  defineProps<{
    noun: string
    busy?: boolean
    loading?: boolean
    /** 搜索框占位里的补充说明，默认用 noun 拼 */
    searchHint?: string
  }>(),
  { busy: false, loading: false, searchHint: '' }
)

const emit = defineEmits<{ (e: 'create'): void; (e: 'search'): void }>()

const keyword = defineModel<string>('keyword', { default: '' })
const status = defineModel<'' | '0' | '1'>('status', { default: '' })
</script>

<template>
  <div class="panel toolbar">
    <input
      v-model="keyword"
      class="field"
      :placeholder="searchHint || `搜索${noun}名称 / 简介 / 标签 / 区县 / 编码`"
      @keyup.enter="emit('search')"
    />
    <select v-model="status" class="field field--sm">
      <option value="">全部状态</option>
      <option value="1">已上架</option>
      <option value="0">已下架</option>
    </select>
    <slot name="chips" />
    <button class="btn btn-ghost btn-sm" :disabled="loading" @click="emit('search')">查询</button>
    <button class="btn btn-primary btn-sm" :disabled="busy" @click="emit('create')">
      新增{{ noun }}
    </button>
  </div>
</template>
