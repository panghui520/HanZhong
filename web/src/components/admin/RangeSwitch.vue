<script setup lang="ts">
/**
 * 统计区间切换（今日 / 近 7 日 / 近 30 日 / 全部）
 *
 * 与驾驶舱顶部的日期控件**是同一个控件、同一套文案**。抽成组件的原因很实际：
 * 这个控件出现在 5 个页面上，文案一旦有一处写成"近 7 天"而不是"近 7 日"，
 * 看屏的人会以为是两个不同的窗口 —— 而它俩其实是同一个。
 *
 * 纯受控：自己不持有状态，只 emit。切换后的重拉由 `useOpsBusiness` 负责。
 */
import type { OpsRange } from '@/types'
import { RANGE_TABS } from '@/composables/useOpsBusiness'

withDefaults(
  defineProps<{
    modelValue: OpsRange
    /** 正在静默重拉时把控件压暗，但不禁用 —— 禁用会让人以为点不动 */
    loading?: boolean
    /** 右侧的口径说明（如"基准日 2026-10-04"） */
    note?: string
  }>(),
  { loading: false, note: '' }
)

const emit = defineEmits<{ (e: 'update:modelValue', v: OpsRange): void }>()
</script>

<template>
  <div class="rs" :class="{ 'rs--busy': loading }">
    <div class="rs__group" role="tablist" aria-label="统计区间">
      <button
        v-for="r in RANGE_TABS"
        :key="r.key"
        type="button"
        role="tab"
        class="rs__btn"
        :class="{ 'rs__btn--on': modelValue === r.key }"
        :aria-selected="modelValue === r.key"
        @click="emit('update:modelValue', r.key)"
      >
        {{ r.label }}
      </button>
    </div>
    <span v-if="note" class="rs__note">{{ note }}</span>
  </div>
</template>

<style scoped>
.rs {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
  transition: opacity var(--dur-1) var(--ease);
}
.rs--busy {
  opacity: 0.6;
}

.rs__group {
  display: inline-flex;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: rgba(11, 33, 25, 0.5);
}
.rs__btn {
  padding: 6px 14px;
  font-size: var(--fs-xs);
  color: var(--text-2);
  border-radius: var(--r-sm);
  transition: all var(--dur-1) var(--ease);
  white-space: nowrap;
}
.rs__btn:hover {
  color: #fff;
  background: rgba(146, 178, 165, 0.1);
}
/* 选中态用金色实底：这是全页唯一的"当前窗口"指示，必须一眼看到 */
.rs__btn--on {
  color: #2b1e07;
  background: var(--gold-500);
  font-weight: 600;
}
.rs__btn--on:hover {
  color: #2b1e07;
  background: var(--gold-500);
}

.rs__note {
  font-size: var(--fs-cap);
  color: var(--text-3);
}
</style>
