<script setup lang="ts">
/**
 * KPI 条（业务页顶部的一排指标卡）
 *
 * 几条刻意的设计：
 *
 * 1. **数字由后端给，前端不加工。** `value` 是后端格式化好的字符串
 *    （`OpsBusinessVO.num()` / `money()` / `percent()`）。前端再拼一次千分位，
 *    两边就会漂移 —— 而"某张卡的数少了一个逗号"不会有人发现。
 *
 * 2. **周期前缀只出现在卡头一次。** 后端给的 label 已经带前缀（"近 7 日到访"），
 *    这里不再逐条挂一遍 —— 挂四遍会把标签挤成两行。
 *
 * 3. **能下钻的卡是可点的**，鼠标移上去有金色描边；不可点的卡不假装可点
 *    （没有 hover 态、没有指针），否则用户会一直点一个没反应的方块。
 *
 * 4. **口径脚注**：一屏上的数字如果口径不同（比如"销售"是产地级、
 *    "复购"是笔数比），必须在脚注里说清。说不清的指标宁可不显示。
 */
import { RouterLink } from 'vue-router'
import type { KpiItem } from './kpi'

withDefaults(
  defineProps<{
    items: KpiItem[]
    /** 底部口径脚注 */
    note?: string
    loading?: boolean
  }>(),
  { note: '', loading: false }
)
</script>

<template>
  <div class="kb">
    <div class="kb__row" :class="{ 'kb__row--loading': loading }">
      <template v-for="k in items" :key="k.key">
        <RouterLink v-if="k.to" :to="k.to" class="kb__card kb__card--link">
          <span class="kb__label">
            {{ k.label }}
            <span v-if="k.hint" class="kb__dot" :title="k.hint">?</span>
          </span>
          <span class="kb__value num">
            {{ k.value }}<small v-if="k.unit">{{ k.unit }}</small>
          </span>
          <span class="kb__go">查看 →</span>
        </RouterLink>

        <div v-else class="kb__card">
          <span class="kb__label">
            {{ k.label }}
            <span v-if="k.hint" class="kb__dot" :title="k.hint">?</span>
          </span>
          <span class="kb__value num">
            {{ k.value }}<small v-if="k.unit">{{ k.unit }}</small>
          </span>
        </div>
      </template>
    </div>
    <p v-if="note" class="kb__note">{{ note }}</p>
  </div>
</template>

<style scoped>
.kb__row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--sp-3);
  transition: opacity var(--dur-1) var(--ease);
}
.kb__row--loading {
  opacity: 0.55;
}

.kb__card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--sp-4);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: rgba(18, 48, 38, 0.55);
  text-align: left;
}

.kb__card--link {
  cursor: pointer;
  transition: all var(--dur-1) var(--ease);
}
.kb__card--link:hover {
  border-color: rgba(192, 154, 78, 0.55);
  background: rgba(24, 59, 48, 0.75);
  transform: translateY(-1px);
}

.kb__label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-cap);
  color: var(--text-3);
  line-height: 1.4;
}
.kb__dot {
  display: inline-grid;
  place-items: center;
  width: 13px;
  height: 13px;
  flex: none;
  font-size: 9px;
  color: var(--gold-300);
  border: 1px solid rgba(192, 154, 78, 0.5);
  border-radius: 50%;
  cursor: help;
}

.kb__value {
  font-size: 26px;
  line-height: 1.1;
  font-weight: 700;
  color: #fff;
  letter-spacing: -0.01em;
}
.kb__value small {
  margin-left: 4px;
  font-size: var(--fs-cap);
  font-weight: 400;
  color: var(--text-3);
}

.kb__go {
  font-size: 11px;
  color: var(--gold-300);
  opacity: 0;
  transition: opacity var(--dur-1) var(--ease);
}
.kb__card--link:hover .kb__go {
  opacity: 1;
}

.kb__note {
  margin-top: var(--sp-3);
  font-size: var(--fs-cap);
  line-height: 1.7;
  color: var(--text-3);
}
</style>
