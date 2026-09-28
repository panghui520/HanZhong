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

/* xl：首页的大区块标题。
 *
 * ★ 这一档**只被 Home.vue 使用**（Explore/Goods 用 md、Itinerary 用 lg），
 *   所以可以按首页的节奏单独收紧，不会波及别的页面。
 *
 * 三处改动及依据：
 *   1. margin-bottom 96px → 24px。用户的原话是"标题 → 巨大空白 → 内容"：
 *      96px 的空档在 1440 视口里已经接近一条"空带"，标题和内容读起来像两件事。
 *      收成 24px 后内容紧跟标题，同时仍与"模块与模块之间"的 144px 拉开差距 ——
 *      **"标题到内容"必须明显小于"模块到模块"**，层次才立得住。
 *   2. 主标题 46px(display) → 38px。46px 是 Hero 的量级，section 标题用到那个尺寸
 *      就把下面的内容压成了配角。38px 明显高于 h1(34px)、又低于 Hero，
 *      层级清楚但不喧宾夺主。
 *   3. 标题容器 760px → 940px、正文 640px → 780px。
 *      原来在 1240px 的容器里，标题最多只占 760px、正文只占 640px，
 *      右侧空出近 500px 纯留白 —— 正是用户说的"标题很大、正文很少、
 *      旁边大量空白"。加宽后内容才真正占据主要视觉区域。
 */
.shead--xl {
  margin-bottom: var(--sp-5);
}
.shead--xl .shead__main {
  max-width: 940px;
}
.shead--xl .shead__title {
  margin-top: var(--sp-3);
  font-size: 38px;
}
.shead--xl .shead__desc {
  margin-top: var(--sp-3);
  font-size: var(--fs-lead);
  max-width: 780px;
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
  /* 桌面端把 xl 主标题定在 38px，但移动端 --fs-display 只有 30px ——
     不覆盖的话手机上会顶到 38px，比桌面端的 h1 还大。26px 与移动端
     --fs-h1(24px) 同级略高，保持"section 标题 > 正文"的层级。 */
  .shead--xl .shead__title {
    font-size: 26px;
  }
  .shead--xl .shead__main,
  .shead--xl .shead__desc {
    max-width: 100%;
  }
}
</style>
