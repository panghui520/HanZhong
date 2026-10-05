<script setup lang="ts">
/**
 * InkMotif —— 宣纸淡墨（页面背景装饰单元）
 *
 * ============================================================
 * 它是什么
 * ============================================================
 * 一个**单个**水墨图案：群峰 / 长山脊 / 侧峰 / 水纹 / 云雾 / 远渚。
 * 它**不是**一整幅山水画 —— 参考稿的做法是"把淡墨拆成若干小块，
 * 分别落在页面的边角与留白处"，所以这里刻意只做一个图案，
 * 由调用方（Home.vue）决定放在哪、多大、多淡、朝哪边。
 *
 * 一句话验收标准：**远看是干净的暖白网页，近看才发现有山。**
 *
 * ============================================================
 * 三条硬约束（都踩过坑）
 * ============================================================
 * 1. **浓度只允许一层乘子。** 绝对浓度由本组件根元素的 CSS `opacity`
 *    唯一控制；SVG 里各层之间的 `opacity`（0.5/0.75/1）只是**图案内部
 *    的远近层次比**，是相对值。
 *    ★ 踩过的坑：曾把绝对浓度同时写进 `<linearGradient>` 的 stop-opacity
 *    和 path 的 opacity，净效果 0.07×0.049≈**0.003** —— 肉眼完全看不见，
 *    而几何探测（元素尺寸）一切正常。只有查 DOM 属性才看得出来。
 * 2. **边缘必须虚化，不能有硬切口。** 用 CSS `mask-image` 的径向渐变
 *    做"从浓到无"的过渡，方向由 `--fx/--fy` 控制（见样式）。
 *    少了它，一块墨会像贴纸一样贴在白纸上、边缘一条直线。
 * 3. **`pointer-events: none`。** 它是背景，绝不能挡住点击 ——
 *    一个盖在卡片上的装饰层会让命中测试整体失真。
 *
 * ============================================================
 * 配色：只用低饱和墨色
 * ============================================================
 * 淡墨青 / 灰绿 / 浅灰。**刻意避开品牌深绿**（#164234 太饱和、太"实"，
 * 落在白底上像脏了一块，不是水墨）。
 *
 * @param motif   图案：peaks 群峰 / ridge 长山脊 / cliff 侧峰 / water 水纹 / mist 云雾 / islet 远渚
 * @param tone    墨色：ink 淡墨青 / moss 灰绿 / ash 浅灰
 * @param opacity **绝对浓度**。按要求落在 0.05 ~ 0.12
 * @param blur    虚化像素。山 1~2px、云雾 6~10px
 * @param flipX   水平翻转（避免同一图案在页面上重复出现）
 * @param flipY   垂直翻转（放在页面上边缘时用）
 * @param width   宽度（CSS 值，如 '38%' / '520px'）
 * @param fadeX / fadeY  水平收边中心。默认 50%/50%；
 *                       放在左侧的图案传 fadeX='0%'（左实右虚）。
 *                       纵向不再靠它 —— 溶入纸面由 SVG 渐变负责。
 */
import { computed } from 'vue'

type Motif = 'peaks' | 'ridge' | 'cliff' | 'water' | 'mist' | 'islet'
type Tone = 'ink' | 'moss' | 'ash'

const props = withDefaults(
  defineProps<{
    motif?: Motif
    tone?: Tone
    opacity?: number
    blur?: number
    flipX?: boolean
    flipY?: boolean
    width?: string
    fadeX?: string
    fadeY?: string
  }>(),
  {
    motif: 'peaks',
    tone: 'ink',
    opacity: 0.08,
    blur: 0,
    flipX: false,
    flipY: false,
    width: 'auto',
    fadeX: '50%',
    fadeY: '50%',
  }
)

/** 三支低饱和墨色。饱和度都压在 10% 上下，避免"彩色块" */
const INK: Record<Tone, string> = {
  ink: '#5f7a80', // 淡墨青
  moss: '#6b7d72', // 灰绿
  ash: '#8b8f8c', // 浅灰
}
const color = computed(() => INK[props.tone])

interface Layer {
  /** 路径。填充类图案要求闭合（回到 L… Z） */
  d: string
  /** 图案**内部**的远近层次比，不是绝对浓度 */
  op: number
}

const MOTIFS: Record<Motif, { box: string; stroke?: boolean; layers: Layer[] }> = {
  /* 群峰：三个峰头错落，最适合放在页面的上/下边缘与角上 */
  peaks: {
    box: '0 0 640 300',
    layers: [
      {
        d: 'M0 206 C 58 188 96 158 142 148 C 190 138 218 172 256 178 C 302 186 324 146 368 134 C 412 122 442 158 488 166 C 540 176 582 150 640 138 L640 300 L0 300 Z',
        op: 0.55,
      },
      {
        d: 'M0 244 C 70 234 112 204 162 200 C 212 196 248 224 294 226 C 346 228 378 194 426 190 C 476 186 514 214 562 218 C 598 221 620 212 640 206 L640 300 L0 300 Z',
        op: 0.78,
      },
      {
        d: 'M0 278 C 90 272 152 256 232 258 C 312 260 372 280 452 278 C 532 276 592 262 640 266 L640 300 L0 300 Z',
        op: 1,
      },
    ],
  },

  /* 长山脊：平缓、跨度大，用来"压"一条页面的横边 */
  ridge: {
    box: '0 0 1200 220',
    layers: [
      {
        d: 'M0 128 C 122 110 212 94 322 98 C 432 102 522 130 642 134 C 762 138 852 106 962 102 C 1072 98 1142 116 1200 124 L1200 220 L0 220 Z',
        op: 0.5,
      },
      {
        d: 'M0 166 C 140 156 250 140 380 144 C 500 148 600 170 720 172 C 840 174 940 150 1060 150 C 1130 150 1172 158 1200 162 L1200 220 L0 220 Z',
        op: 0.8,
      },
      {
        d: 'M0 198 C 160 192 300 182 450 184 C 600 186 720 198 880 198 C 1020 198 1132 188 1200 190 L1200 220 L0 220 Z',
        op: 1,
      },
    ],
  },

  /* 侧峰：体量偏竖，靠边放。内缘自然收细，不会切出直边 */
  cliff: {
    box: '0 0 340 420',
    layers: [
      {
        d: 'M0 92 C 44 80 74 54 110 46 C 150 37 180 72 206 114 C 234 160 266 226 294 294 C 310 332 326 368 340 396 L340 420 L0 420 Z',
        op: 0.55,
      },
      {
        d: 'M0 214 C 42 202 72 178 108 174 C 146 170 178 200 204 236 C 232 276 262 322 290 362 C 306 384 324 402 340 414 L340 420 L0 420 Z',
        op: 0.82,
      },
      {
        d: 'M0 330 C 58 322 116 308 172 314 C 228 320 278 344 340 362 L340 420 L0 420 Z',
        op: 1,
      },
    ],
  },

  /* 水纹：只画线不填充 —— 横着的细墨线，比色块更像"水" */
  water: {
    box: '0 0 900 150',
    stroke: true,
    layers: [
      {
        d: 'M0 44 C 82 32 162 56 242 44 C 322 32 402 56 482 44 C 562 32 642 56 722 44 C 802 32 862 52 900 44',
        op: 0.45,
      },
      {
        d: 'M0 80 C 92 70 172 92 252 80 C 332 68 412 92 492 80 C 572 68 652 92 732 80 C 812 68 872 86 900 80',
        op: 0.7,
      },
      {
        d: 'M0 116 C 102 108 182 126 262 116 C 342 106 422 126 502 116 C 582 106 662 126 742 116 C 822 106 882 120 900 116',
        op: 1,
      },
    ],
  },

  /* 云雾：三团横向的软块，靠 blur 化成雾。用来填"局部留白" */
  mist: {
    box: '0 0 900 220',
    layers: [
      { d: 'M60 108 C 60 78 150 58 258 58 C 366 58 456 78 456 108 C 456 138 366 158 258 158 C 150 158 60 138 60 108 Z', op: 0.42 },
      { d: 'M330 78 C 330 52 428 34 536 34 C 644 34 742 52 742 78 C 742 104 644 122 536 122 C 428 122 330 104 330 78 Z', op: 0.32 },
      { d: 'M420 156 C 420 134 516 118 620 118 C 724 118 820 134 820 156 C 820 178 724 194 620 194 C 516 194 420 178 420 156 Z', op: 0.5 },
    ],
  },

  /* 远渚：两座小丘 + 一道水线。小块留白处点一下，不做主体 */
  islet: {
    box: '0 0 460 200',
    layers: [
      {
        d: 'M0 128 C 60 114 98 88 142 84 C 188 80 216 106 256 114 C 296 122 326 108 366 106 C 406 104 434 116 460 120 L460 200 L0 200 Z',
        op: 0.8,
      },
      { d: 'M0 166 C 72 160 142 172 212 166 C 282 160 352 172 422 168 L460 166 L460 176 L0 176 Z', op: 0.55 },
    ],
  },
}

/**
 * 图案**内部层次**的整体增益。
 *
 * 为什么要有这个旋钮：绝对浓度被 5%~12% 卡死了（改外层 opacity 就会越界），
 * 但把每条脊线的相对不透明度写死时，远山 0.5 那一档在低浓度下几乎消失，
 * 整块墨只剩最上面一条边、读不出"层峦"。调这一个值就能整体加深山体，
 * 而**不触碰**外层那个受约束的绝对值。
 */
const LAYER_GAIN = 1.4

const spec = computed(() => {
  const s = MOTIFS[props.motif]
  return {
    ...s,
    layers: s.layers.map((l) => ({ ...l, op: Math.min(1, l.op * LAYER_GAIN) })),
  }
})

/**
 * viewBox 的高度。
 * ★ 给 dissolve 渐变用 `gradientUnits="userSpaceOnUse"` 时必须显式给 y2 ——
 *   默认的 `objectBoundingBox` 是**相对每条 path 自己的 bbox** 算的，
 *   于是三条脊线各按各的高度衰减：最下面那条"近山"只有 44 单位高，
 *   它的顶边就落在渐变 offset 0% 处 = **满浓度**，渲染出一条实心横带，
 *   看起来就是"山被平直地切了一刀"。实测截图里一眼可见。
 */
const boxH = computed(() => {
  const parts = spec.value.box.split(/\s+/)
  return parts[3] ?? '300'
})

/** 同一页有十几个实例，渐变 id 必须唯一，否则会互相串色 */
const uid = `im-${Math.random().toString(36).slice(2, 9)}`

const rootStyle = computed(() => ({
  opacity: String(props.opacity),
  width: props.width,
  '--fx': props.fadeX,
  '--fy': props.fadeY,
  filter: props.blur ? `blur(${props.blur}px)` : undefined,
}))
</script>

<template>
  <div
    class="im"
    :class="{ 'im--fx': flipX, 'im--fy': flipY }"
    :style="rootStyle"
    aria-hidden="true"
  >
    <svg
      class="im__svg"
      :viewBox="spec.box"
      preserveAspectRatio="xMidYMax meet"
      xmlns="http://www.w3.org/2000/svg"
    >
      <defs>
        <!--
          山体从山脊线往下**溶进纸里**。
          这是水墨的关键：山脚不能是一条平直的填色边，要化开。
          ★ 这里只做**相对**衰减（1 → 0）；绝对浓度仍由外层 opacity 唯一控制。
            把绝对浓度也写进 stop-opacity 就会两层相乘、直接看不见（踩过）。
        -->
        <linearGradient
          :id="`${uid}-dissolve`"
          gradientUnits="userSpaceOnUse"
          x1="0"
          y1="0"
          x2="0"
          :y2="boxH"
        >
          <!--
            溶入纸面的位置要**靠下**：实测第一版 46% 处就掉到 0.72、
            76% 掉到 0.26，结果整幅墨的等效浓度只有 2%~5%（用户要的是
            5%~12%）—— 山还没看清就化没了。现在 58% 仍是 0.88。
          -->
          <stop offset="0%" :stop-color="color" stop-opacity="1" />
          <stop offset="58%" :stop-color="color" stop-opacity="0.88" />
          <stop offset="82%" :stop-color="color" stop-opacity="0.42" />
          <stop offset="100%" :stop-color="color" stop-opacity="0" />
        </linearGradient>
      </defs>

      <!-- 描线类（水纹）：细墨线，无填充 -->
      <template v-if="spec.stroke">
        <path
          v-for="(l, i) in spec.layers"
          :key="i"
          :d="l.d"
          fill="none"
          :stroke="color"
          stroke-width="1.6"
          stroke-linecap="round"
          :opacity="l.op"
        />
      </template>

      <!-- 填充类（山、雾、渚）：用"溶入纸面"的渐变填充 -->
      <template v-else>
        <path
          v-for="(l, i) in spec.layers"
          :key="i"
          :d="l.d"
          :fill="`url(#${uid}-dissolve)`"
          :opacity="l.op"
        />
      </template>
    </svg>
  </div>
</template>

<style scoped>
.im {
  position: absolute;
  /* ★ 背景装饰绝不能拦住点击 */
  pointer-events: none;
  z-index: 0;
}
.im__svg {
  display: block;
  width: 100%;
  height: auto;
  /*
   * 边缘虚化：以 (--fx, --fy) 为中心由浓到无。
   * 少了这一层，墨块会像贴纸一样贴在白纸上、四周一条硬直线。
   * 中心可调 → 放在左边的图案传 --fx: 0%（左侧实、往右淡）。
   *
   * ★ 分工：**纵向的"溶入纸面"由 SVG 里的 dissolve 渐变负责**（见模板），
   *   这里只管**水平方向收边**，所以椭圆给得"宽而扁"（横向大、纵向小），
   *   让它主要沿水平方向衰减。
   * ★ 过渡要晚要缓：最初写 `#000 26% → 透明 84%`，等于把图案 3/4 吃掉，
   *   实测只剩中间一团糊影、读不出山形。
   */
  -webkit-mask-image: radial-gradient(
    132% 88% at var(--fx, 50%) var(--fy, 50%),
    #000 40%,
    rgba(0, 0, 0, 0.58) 70%,
    rgba(0, 0, 0, 0) 96%
  );
  mask-image: radial-gradient(
    132% 88% at var(--fx, 50%) var(--fy, 50%),
    #000 40%,
    rgba(0, 0, 0, 0.58) 70%,
    rgba(0, 0, 0, 0) 96%
  );
}
.im--fx .im__svg {
  transform: scaleX(-1);
}
.im--fy .im__svg {
  transform: scaleY(-1);
}
.im--fx.im--fy .im__svg {
  transform: scale(-1, -1);
}
</style>
