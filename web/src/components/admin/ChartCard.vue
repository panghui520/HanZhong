<script setup lang="ts">
/**
 * 图表卡（业务页里每一张图的容器）
 *
 * 抽出来的目的不是省几行 HTML，而是**保证"层次"是显式的**：
 *
 *   · `tone="hero"`  —— 主图。占两列，标题字号更大，图上留白更多。
 *   · `tone="main"`  —— 次图。占一列。
 *   · `tone="trend"` —— 趋势图。整行宽（趋势要横向空间才读得出形状）。
 *
 * 一个页面里必须同时出现不止一种 tone —— 五张图长得一模一样、
 * 各占一格，就是"Vue 表格后台"最典型的观感。
 *
 * `detail` 插槽是给"点击图表某一项 → 显示这一项的明细"用的。
 * 它固定在卡底部而不是弹窗：弹窗会盖住图，而用户正需要一边看高亮一边读明细。
 */
withDefaults(
  defineProps<{
    title: string
    /** 副标题：说明口径或数据范围，比正文小一档 */
    sub?: string
    /** 右上角的口径提示（悬停出现） */
    tip?: string
    tone?: 'hero' | 'main' | 'trend'
    /** 空数据时显示的文案。不传则没有数据时不显示任何占位（由调用方决定） */
    empty?: string
    isEmpty?: boolean
  }>(),
  { sub: '', tip: '', tone: 'main', empty: '', isEmpty: false }
)
</script>

<template>
  <section class="cc" :class="`cc--${tone}`">
    <header class="cc__head">
      <div class="cc__titles">
        <h3 class="cc__title">
          {{ title }}
          <span v-if="tip" class="cc__tip" :title="tip">?</span>
        </h3>
        <p v-if="sub" class="cc__sub">{{ sub }}</p>
      </div>
      <slot name="head" />
    </header>

    <div class="cc__body">
      <div v-if="isEmpty" class="cc__empty">{{ empty || '暂无数据' }}</div>
      <slot v-else />
    </div>

    <div v-if="$slots.detail" class="cc__detail">
      <slot name="detail" />
    </div>
    <div v-if="$slots.foot" class="cc__foot">
      <slot name="foot" />
    </div>
  </section>
</template>

<style scoped>
.cc {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: var(--sp-4) var(--sp-4) var(--sp-3);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: rgba(18, 48, 38, 0.5);
}

/* 主图：金色顶边 + 更松的内边距。一个页面里只应有一张 */
.cc--hero {
  padding: var(--sp-5);
  border-color: rgba(192, 154, 78, 0.32);
  background: linear-gradient(180deg, rgba(24, 59, 48, 0.7), rgba(18, 48, 38, 0.5));
  box-shadow: inset 0 2px 0 rgba(192, 154, 78, 0.5);
}

.cc__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-3);
}
.cc__titles {
  min-width: 0;
}
.cc__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: #fff;
  line-height: 1.4;
}
.cc--hero .cc__title {
  font-size: var(--fs-h3);
}
.cc__tip {
  display: inline-grid;
  place-items: center;
  width: 14px;
  height: 14px;
  flex: none;
  font-size: 9px;
  font-weight: 400;
  color: var(--gold-300);
  border: 1px solid rgba(192, 154, 78, 0.5);
  border-radius: 50%;
  cursor: help;
}
.cc__sub {
  margin-top: 4px;
  font-size: var(--fs-cap);
  line-height: 1.6;
  color: var(--text-3);
}

.cc__body {
  flex: 1;
  min-height: 0;
  min-width: 0;
}

.cc__empty {
  display: grid;
  place-items: center;
  min-height: 140px;
  font-size: var(--fs-cap);
  color: var(--text-3);
  border: 1px dashed var(--line);
  border-radius: var(--r-md);
}

/* 点击选中后的明细。左侧竖线而不是整块底色 —— 它属于图，不是独立的一块 */
.cc__detail {
  margin-top: var(--sp-3);
  padding-left: var(--sp-3);
  border-left: 2px solid rgba(192, 154, 78, 0.55);
  font-size: var(--fs-cap);
  line-height: 1.75;
  color: var(--text-2);
}

.cc__foot {
  margin-top: var(--sp-3);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line);
  font-size: var(--fs-cap);
  color: var(--text-3);
}
</style>
