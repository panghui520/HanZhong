<script setup lang="ts">
/**
 * SectionHead —— 统一区块标题
 *
 * 首页各区块靠它拉开"节奏"：hero 大区块用 size="xl"，
 * 次级区块用默认尺寸，形成字号层级而不是靠卡片变大。
 */
withDefaults(
  defineProps<{
    eyebrow?: string
    title: string
    desc?: string
    /** xl 用于整幅强调带；md 用于紧凑区块 */
    size?: 'md' | 'lg' | 'xl'
    /** light 用于深色底 */
    tone?: 'dark' | 'light'
    /** 右侧链接文案，留空则不显示 */
    moreText?: string
    moreTo?: string
    align?: 'left' | 'center'
  }>(),
  { size: 'lg', tone: 'dark', align: 'left' }
)
</script>

<template>
  <header
    class="shead"
    :class="[`shead--${size}`, `shead--${tone}`, `shead--${align}`]"
  >
    <div class="shead__main">
      <span v-if="eyebrow" class="eyebrow" :class="{ 'eyebrow--light': tone === 'light' }">
        {{ eyebrow }}
      </span>
      <h2 class="shead__title" :class="size === 'xl' ? 'display' : 'h2'">{{ title }}</h2>
      <p v-if="desc" class="shead__desc">{{ desc }}</p>
    </div>
    <router-link v-if="moreText && moreTo" :to="moreTo" class="shead__more btn-text">
      {{ moreText }}
    </router-link>
  </header>
</template>

<style scoped>
.shead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-6);
  margin-bottom: var(--sp-7);
}
.shead--center {
  flex-direction: column;
  align-items: center;
  text-align: center;
}
.shead--center .shead__main {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.shead__main {
  max-width: 760px;
}
.shead__title {
  margin-top: var(--sp-4);
  color: var(--ink-900);
}
.shead__desc {
  margin-top: var(--sp-4);
  font-size: var(--fs-body);
  line-height: 1.85;
  color: var(--ink-500);
  max-width: 640px;
}
.shead__more {
  flex: none;
  padding-bottom: 6px;
}

/* 尺寸档：只调标题与下边距，保持克制 */
.shead--md {
  margin-bottom: var(--sp-6);
}
.shead--md .shead__title {
  margin-top: var(--sp-3);
}
.shead--xl {
  margin-bottom: var(--sp-9);
}
.shead--xl .shead__title {
  margin-top: var(--sp-5);
}
.shead--xl .shead__desc {
  margin-top: var(--sp-5);
  font-size: var(--fs-lead);
}

/* 深色底 */
.shead--light .shead__title {
  color: #fff;
}
.shead--light .shead__desc {
  color: rgba(255, 255, 255, 0.74);
}
.shead--light .shead__more {
  color: var(--gold-300);
}

@media (max-width: 720px) {
  .shead {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--sp-4);
    margin-bottom: var(--sp-6);
  }
  .shead__more {
    padding-bottom: 0;
  }
}
</style>
