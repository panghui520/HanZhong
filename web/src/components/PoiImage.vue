<script setup lang="ts">
/**
 * PoiImage —— 景点图片（M9）
 *
 * 一条原则：**永远不开天窗**。
 * 有实拍图就显示实拍图；没传图、图挂了、媒体服务不可达，就回落到
 * SceneArt 手写 SVG（按 scene 字段选变体）。三种情况在页面上都是
 * 一幅完整画面，不会出现空白块、破图图标或"图片加载失败"的浏览器默认样式。
 *
 * 这对本项目的意义不只是好看：比赛现场断网演示时，运营上传的图片
 * 可能根本没跟着走，那时整站仍然要是完整的。
 *
 * 地址来源有两个：
 *   - 默认：从 useCovers() 的全局封面映射里按 poiId 查（一次请求供全页使用）
 *   - 覆盖：传 src（管理端预览刚上传、还没进缓存的那张图）
 */
import { computed, ref, watch } from 'vue'
import SceneArt from './SceneArt.vue'
import { useCovers } from '@/composables/useCovers'

type Variant =
  | 'qinling'
  | 'terrace'
  | 'rapeseed'
  | 'ancient'
  | 'river'
  | 'hanjiang'
  | 'hantai'

const props = withDefaults(
  defineProps<{
    /** 景点 id。给了它才会去查实拍图 */
    poiId?: string
    /** 回落画面的变体（来自数据包的 scene 字段） */
    scene?: string
    /** 外层比例。'auto' 表示高度由父容器决定（Hero、大图位用） */
    ratio?: string
    tone?: 'light' | 'deep'
    alt?: string
    /** 直接指定地址，优先于封面映射。管理端预览用 */
    src?: string
    /** 首屏大图用 eager，避免懒加载把它推迟到可视区之后 */
    eager?: boolean
  }>(),
  { ratio: '16 / 9', tone: 'light', eager: false }
)

const { covers } = useCovers()

/** 加载失败过一次就不再重试，直接回落 —— 否则坏地址会反复触发请求 */
const broken = ref(false)

const url = computed(() => {
  if (props.src) return props.src
  if (!props.poiId) return ''
  return covers.value[props.poiId] ?? ''
})

// 地址变了要重置失败标记：运营换了一张新图，不该继续显示插画
watch(url, () => {
  broken.value = false
})

const showPhoto = computed(() => !!url.value && !broken.value)

/** SceneArt 的 variant 只认那 7 个值，数据包里没写 scene 时给个默认 */
const variant = computed<Variant>(() => (props.scene as Variant) || 'qinling')

const boxStyle = computed(() =>
  props.ratio && props.ratio !== 'auto' ? { aspectRatio: props.ratio } : undefined
)
</script>

<template>
  <div class="pimg" :style="boxStyle">
    <img
      v-if="showPhoto"
      class="pimg__photo"
      :src="url"
      :alt="alt || ''"
      :loading="eager ? 'eager' : 'lazy'"
      decoding="async"
      @error="broken = true"
    />
    <SceneArt v-else :variant="variant" ratio="auto" :tone="tone" class="pimg__fallback" />
  </div>
</template>

<style scoped>
.pimg {
  position: relative;
  width: 100%;
  overflow: hidden;
  background: var(--paper-2);
  /* 圆角跟着外层卡片走：这些图位都嵌在卡片里，圆角由卡片决定 */
  border-radius: inherit;
}

.pimg__photo,
.pimg__fallback {
  display: block;
  width: 100%;
  height: 100%;
}

/* cover 而不是 contain：实拍照片的长宽比千奇百怪，
   contain 会在两侧留出与页面底色不一致的空白条，看起来像坏了 */
.pimg__photo {
  object-fit: cover;
  object-position: center;
}
</style>
