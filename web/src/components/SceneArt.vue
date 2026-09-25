<script setup lang="ts">
import type { SceneVariant } from '@/utils/scene'

/**
 * SceneArt —— 场景插画
 * 全部为手写 SVG，无外链图片：离线可演示、无版权问题、风格统一。
 * 视觉母题取自汉中：秦岭云海、茶园梯田、油菜花田、汉江、古建街巷、汉台春色。
 *
 * 用于首页 Hero 时用 ratio="auto" + 外层给定高度，preserveAspectRatio="slice"
 * 会自动把最远/最近的层次裁到合适位置；再把 svg 略微放大即可做视差。
 */
const props = withDefaults(
  defineProps<{
    /**
     * 场景变体。类型来自 `@/utils/scene`，与下面 `<g v-if>` 的分支一一对应。
     * 数据驱动的调用方（产品卡等）请先过一遍 `sceneVariant()` 归一化，
     * 不要直接传 `string` —— 拼错的值会静默落到最后一个 `v-else` 分支。
     */
    variant?: SceneVariant
    ratio?: string
    tone?: 'light' | 'deep'
  }>(),
  { variant: 'qinling', ratio: '16 / 9', tone: 'light' }
)
</script>

<template>
  <div class="scene" :class="`scene--${props.tone}`" :style="{ aspectRatio: props.ratio }">
    <svg
      class="scene__svg"
      viewBox="0 0 1200 675"
      preserveAspectRatio="xMidYMid slice"
      aria-hidden="true"
    >
      <defs>
        <linearGradient :id="`sky-${variant}`" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#f7f2e6" />
          <stop offset="45%" stop-color="#eef2ec" />
          <stop offset="100%" stop-color="#e6ece7" />
        </linearGradient>
        <linearGradient :id="`far-${variant}`" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#9dbcb0" />
          <stop offset="100%" stop-color="#b9cfc6" />
        </linearGradient>
        <linearGradient :id="`mid-${variant}`" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#5d8b77" />
          <stop offset="100%" stop-color="#78a48f" />
        </linearGradient>
        <linearGradient :id="`near-${variant}`" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#2a6f5b" />
          <stop offset="100%" stop-color="#3d8b74" />
        </linearGradient>
        <linearGradient :id="`front-${variant}`" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#164234" />
          <stop offset="100%" stop-color="#1d5544" />
        </linearGradient>
        <radialGradient :id="`sun-${variant}`" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stop-color="#e2ca91" stop-opacity="0.95" />
          <stop offset="55%" stop-color="#e2ca91" stop-opacity="0.35" />
          <stop offset="100%" stop-color="#e2ca91" stop-opacity="0" />
        </radialGradient>
        <linearGradient :id="`mist-${variant}`" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#ffffff" stop-opacity="0" />
          <stop offset="50%" stop-color="#ffffff" stop-opacity="0.75" />
          <stop offset="100%" stop-color="#ffffff" stop-opacity="0" />
        </linearGradient>
        <linearGradient :id="`water-${variant}`" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#cfddd8" />
          <stop offset="100%" stop-color="#eef2ec" />
        </linearGradient>
        <linearGradient :id="`field-${variant}`" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#d9b45f" />
          <stop offset="100%" stop-color="#c09a4e" />
        </linearGradient>
      </defs>

      <rect width="1200" height="675" :fill="`url(#sky-${variant})`" />

      <!-- ============ 秦岭云海 ============ -->
      <g v-if="variant === 'qinling'">
        <circle cx="955" cy="150" r="150" :fill="`url(#sun-${variant})`" />
        <circle cx="955" cy="150" r="40" fill="#e2ca91" opacity="0.55" />
        <path
          d="M0 402 C120 342 220 382 320 352 C420 322 520 372 620 347 C720 322 820 377 920 352 C1020 327 1120 372 1200 347 L1200 675 L0 675 Z"
          :fill="`url(#far-${variant})`"
        />
        <rect x="0" y="392" width="1200" height="74" :fill="`url(#mist-${variant})`" />
        <path
          d="M0 472 C100 422 200 462 300 427 C400 392 500 452 610 422 C720 392 830 452 940 422 C1050 392 1150 442 1200 427 L1200 675 L0 675 Z"
          :fill="`url(#mid-${variant})`"
        />
        <rect x="0" y="462" width="1200" height="60" :fill="`url(#mist-${variant})`" opacity="0.6" />
        <path
          d="M0 534 C120 494 240 524 360 499 C480 474 600 514 720 494 C840 474 960 509 1080 494 C1140 487 1180 499 1200 492 L1200 675 L0 675 Z"
          :fill="`url(#near-${variant})`"
        />
        <g opacity="0.9" fill="#123a2e">
          <path d="M132 534 l9 -26 l9 26 z" />
          <path d="M168 528 l7 -21 l7 21 z" />
          <path d="M905 496 l8 -24 l8 24 z" />
          <path d="M936 492 l6 -19 l6 19 z" />
        </g>
        <path
          d="M0 596 C150 566 300 591 450 576 C600 561 750 586 900 574 C1050 562 1150 581 1200 574 L1200 675 L0 675 Z"
          :fill="`url(#front-${variant})`"
        />
        <g stroke="#123a2e" stroke-width="1.4" fill="none" opacity="0.5">
          <path d="M300 250 q14 -12 28 0" />
          <path d="M336 232 q11 -10 22 0" />
          <path d="M372 262 q9 -8 18 0" />
        </g>
      </g>

      <!-- ============ 茶园梯田 ============ -->
      <g v-else-if="variant === 'terrace'">
        <circle cx="230" cy="130" r="130" :fill="`url(#sun-${variant})`" />
        <path
          d="M0 300 C150 260 300 296 450 276 C600 256 760 292 900 274 C1030 257 1130 285 1200 276 L1200 675 L0 675 Z"
          :fill="`url(#far-${variant})`"
        />
        <path
          d="M0 372 C160 342 300 372 460 356 C620 340 780 372 920 358 C1060 344 1140 366 1200 358 L1200 675 L0 675 Z"
          :fill="`url(#mid-${variant})`"
        />
        <path
          d="M0 438 C170 414 330 446 490 428 C650 410 800 442 950 428 C1080 416 1150 432 1200 426 L1200 675 L0 675 Z"
          :fill="`url(#near-${variant})`"
        />
        <g fill="none" stroke="#e9f1ec" stroke-width="2" opacity="0.42">
          <path d="M-20 470 C200 452 420 486 640 468 C860 450 1040 480 1220 464" />
          <path d="M-20 508 C200 490 420 524 640 506 C860 488 1040 518 1220 502" />
          <path d="M-20 548 C200 530 420 564 640 546 C860 528 1040 558 1220 542" />
          <path d="M-20 590 C200 572 420 606 640 588 C860 570 1040 600 1220 584" />
        </g>
        <path
          d="M0 632 C170 616 330 640 490 630 C650 620 800 640 950 632 C1080 625 1150 636 1200 632 L1200 675 L0 675 Z"
          :fill="`url(#front-${variant})`"
        />
      </g>

      <!-- ============ 油菜花田 ============ -->
      <g v-else-if="variant === 'rapeseed'">
        <circle cx="1010" cy="140" r="140" :fill="`url(#sun-${variant})`" />
        <path
          d="M0 330 C140 300 280 332 420 314 C560 296 700 328 840 312 C980 296 1100 320 1200 312 L1200 675 L0 675 Z"
          :fill="`url(#far-${variant})`"
        />
        <path
          d="M0 396 C150 372 300 400 450 386 C600 372 750 400 900 388 C1030 377 1130 394 1200 388 L1200 675 L0 675 Z"
          :fill="`url(#mid-${variant})`"
        />
        <!-- 村落剪影 -->
        <g fill="#164234" opacity="0.92">
          <path d="M470 402 h88 v-34 h-88 z" />
          <path d="M462 370 l52 -26 l52 26 z" />
          <path d="M590 402 h60 v-26 h-60 z" />
          <path d="M584 378 l36 -20 l36 20 z" />
        </g>
        <path
          d="M0 452 C160 436 320 460 480 450 C640 440 800 462 960 452 C1080 444 1150 456 1200 452 L1200 675 L0 675 Z"
          :fill="`url(#field-${variant})`"
        />
        <g fill="none" stroke="#a8813a" stroke-width="2" opacity="0.35">
          <path d="M-20 500 C200 490 420 512 640 500 C860 488 1040 508 1220 498" />
          <path d="M-20 546 C200 536 420 558 640 546 C860 534 1040 554 1220 544" />
          <path d="M-20 596 C200 586 420 608 640 596 C860 584 1040 604 1220 594" />
        </g>
        <g fill="#e2ca91" opacity="0.7">
          <circle cx="120" cy="560" r="7" />
          <circle cx="260" cy="596" r="6" />
          <circle cx="700" cy="576" r="7" />
          <circle cx="980" cy="612" r="6" />
          <circle cx="430" cy="620" r="6" />
        </g>
      </g>

      <!-- ============ 古建街巷 ============ -->
      <g v-else-if="variant === 'ancient'">
        <rect width="1200" height="675" fill="#f4efe3" />
        <circle cx="180" cy="120" r="120" :fill="`url(#sun-${variant})`" />
        <!--
          远山。资源详情页的 Hero 是超宽扁容器（约 4:1），而画布是 1.78:1，
          preserveAspectRatio="slice" 居中裁切后只露出纵向中间一段（约 y 194–481）。
          没有这层的话那一段的上半部分是空白天空，画面里只剩屋檐的横条，
          看起来像几条色带而不是一幅画。山峦叠在后面，建筑在前，层次就回来了。
          卡片（16:10）里裁的是左右两侧，这层山同样成立。

          后来 M1 列表页把 scene=ancient 的 16 条资源（餐饮/住宿/交通）放大到
          16:10 的图上，裁掉左右之后中间那段还是偏"横条"。所以又补了几层
          纵向元素：远树、檐下灯笼、垂帘、石阶与盆栽，让任何裁切比例下
          都有东西撑住画面。
        -->
        <path
          d="M0 236 C130 206 250 228 380 212 C510 196 630 224 760 210 C890 196 1010 222 1130 210 C1160 207 1185 214 1200 211 L1200 320 L0 320 Z"
          :fill="`url(#far-${variant})`"
        />
        <rect x="0" y="226" width="1200" height="58" :fill="`url(#mist-${variant})`" opacity="0.65" />
        <!-- 远树：给横向构图补纵向层次 -->
        <g opacity="0.5">
          <path d="M96 300 l-24 -46 l-10 46 z" fill="#4f7a66" />
          <path d="M140 300 l-20 -38 l-9 38 z" fill="#5d8b77" />
          <path d="M1052 300 l-22 -42 l-9 42 z" fill="#4f7a66" />
          <path d="M1100 300 l-18 -34 l-8 34 z" fill="#5d8b77" />
        </g>
        <!-- 远檐 -->
        <path
          d="M0 300 L1200 300 L1200 330 L0 330 Z"
          fill="#c9bfae"
        />
        <path d="M-20 300 L1220 300 L1160 262 L40 262 Z" fill="#8f8578" />
        <!-- 主屋檐 -->
        <path d="M60 356 L1140 356 L1080 306 L120 306 Z" fill="#164234" />
        <!-- 瓦当：沿主檐排一行瓦头，让屋檐不是纯色块 -->
        <g fill="#0b2119" opacity="0.5">
          <circle cx="140" cy="352" r="5" />
          <circle cx="260" cy="344" r="5" />
          <circle cx="380" cy="337" r="5" />
          <circle cx="500" cy="331" r="5" />
          <circle cx="620" cy="327" r="5" />
          <circle cx="740" cy="331" r="5" />
          <circle cx="860" cy="337" r="5" />
          <circle cx="980" cy="344" r="5" />
          <circle cx="1100" cy="352" r="5" />
        </g>
        <path d="M0 380 L1200 380 L1200 404 L0 404 Z" fill="#1d5544" />
        <!-- 立柱 -->
        <g fill="#123a2e">
          <rect x="150" y="404" width="22" height="180" />
          <rect x="430" y="404" width="22" height="180" />
          <rect x="710" y="404" width="22" height="180" />
          <rect x="1000" y="404" width="22" height="180" />
        </g>
        <!-- 柱础 -->
        <g fill="#c9bfae">
          <rect x="142" y="572" width="38" height="12" rx="2" />
          <rect x="422" y="572" width="38" height="12" rx="2" />
          <rect x="702" y="572" width="38" height="12" rx="2" />
          <rect x="992" y="572" width="38" height="12" rx="2" />
        </g>
        <!-- 檐下灯笼：三只，压在柱间 -->
        <g>
          <g v-for="(cx, i) in [270, 580, 890]" :key="i">
            <path :d="`M${cx} 402 v22`" stroke="#8a6a2c" stroke-width="1.6" />
            <ellipse :cx="cx" cy="440" rx="15" ry="19" fill="#a8402b" opacity="0.85" />
            <ellipse :cx="cx" cy="440" rx="6" ry="19" fill="#c25a44" opacity="0.6" />
            <path :d="`M${cx - 6} 460 h12`" stroke="#8a6a2c" stroke-width="2" />
          </g>
        </g>
        <!-- 匾额 -->
        <g>
          <rect x="470" y="330" width="260" height="52" rx="4" fill="#c09a4e" />
          <rect
            x="478"
            y="338"
            width="244"
            height="36"
            rx="2"
            fill="none"
            stroke="#8a6a2c"
            stroke-width="1.5"
          />
          <rect x="500" y="352" width="56" height="8" rx="3" fill="#8a6a2c" opacity="0.6" />
          <rect x="572" y="352" width="56" height="8" rx="3" fill="#8a6a2c" opacity="0.6" />
          <rect x="644" y="352" width="56" height="8" rx="3" fill="#8a6a2c" opacity="0.6" />
        </g>
        <!-- 垂帘（门洞暗部 + 帘条） -->
        <g>
          <rect x="508" y="404" width="184" height="150" fill="#0f2b22" opacity="0.55" />
          <g stroke="#e6ded0" stroke-width="3" opacity="0.35">
            <path d="M528 404 v150" />
            <path d="M556 404 v150" />
            <path d="M584 404 v150" />
            <path d="M612 404 v150" />
            <path d="M640 404 v150" />
            <path d="M668 404 v150" />
          </g>
        </g>
        <!-- 地面 -->
        <path d="M0 584 L1200 584 L1200 675 L0 675 Z" fill="#e6ded0" />
        <g stroke="#cdc4b4" stroke-width="2" opacity="0.8">
          <path d="M60 675 L220 584" />
          <path d="M420 675 L520 584" />
          <path d="M780 675 L700 584" />
          <path d="M1140 675 L960 584" />
        </g>
        <!-- 台阶 -->
        <g fill="#ded3bf">
          <rect x="496" y="554" width="208" height="10" />
          <rect x="482" y="564" width="236" height="10" />
          <rect x="468" y="574" width="264" height="10" />
        </g>
        <!-- 盆栽 -->
        <g>
          <path d="M84 584 l10 -22 h24 l10 22 z" fill="#8f8578" />
          <g fill="#2a6f5b" opacity="0.85">
            <ellipse cx="106" cy="548" rx="26" ry="18" />
            <ellipse cx="90" cy="532" rx="16" ry="12" />
            <ellipse cx="122" cy="534" rx="17" ry="13" />
          </g>
        </g>
        <g>
          <path d="M1084 584 l10 -22 h24 l10 22 z" fill="#8f8578" />
          <g fill="#2a6f5b" opacity="0.85">
            <ellipse cx="1106" cy="548" rx="26" ry="18" />
            <ellipse cx="1090" cy="532" rx="16" ry="12" />
            <ellipse cx="1122" cy="534" rx="17" ry="13" />
          </g>
        </g>
      </g>

      <!-- ============ 汉江暮色（暖调，用于 Hero 第二帧） ============ -->
      <g v-else-if="variant === 'hanjiang'">
        <rect width="1200" height="675" fill="#f3e6d2" />
        <circle cx="820" cy="300" r="190" :fill="`url(#sun-${variant})`" />
        <circle cx="820" cy="300" r="46" fill="#e0b46a" opacity="0.85" />
        <path
          d="M0 316 C150 286 300 316 450 300 C600 284 760 316 900 300 C1040 284 1140 306 1200 300 L1200 675 L0 675 Z"
          :fill="`url(#far-${variant})`"
        />
        <rect x="0" y="306" width="1200" height="66" :fill="`url(#mist-${variant})`" opacity="0.55" />
        <path
          d="M0 386 C160 360 320 390 480 376 C640 362 800 390 960 378 C1080 369 1150 382 1200 378 L1200 675 L0 675 Z"
          :fill="`url(#mid-${variant})`"
        />
        <!-- 江面 -->
        <rect x="0" y="430" width="1200" height="245" fill="#e8dcc6" />
        <!-- 落日倒影 -->
        <g fill="#d9ab63" opacity="0.5">
          <rect x="742" y="452" width="156" height="6" rx="3" />
          <rect x="774" y="474" width="92" height="5" rx="2.5" />
          <rect x="752" y="496" width="136" height="5" rx="2.5" />
          <rect x="782" y="518" width="76" height="4" rx="2" />
          <rect x="764" y="540" width="112" height="4" rx="2" />
        </g>
        <!-- 江上舟 -->
        <g fill="#2b3a33" opacity="0.86">
          <path d="M300 512 l74 0 l-13 15 l-48 0 z" />
          <rect x="336" y="482" width="3" height="30" />
          <path d="M339 484 l24 22 l-24 0 z" opacity="0.8" />
        </g>
        <!-- 远帆 -->
        <g fill="#3c4b43" opacity="0.45">
          <path d="M980 486 l30 0 l-6 8 l-18 0 z" />
          <rect x="994" y="470" width="2" height="16" />
        </g>
        <!-- 江岸芦影 -->
        <path
          d="M0 612 C170 596 330 622 490 610 C650 598 800 624 950 614 C1080 605 1150 618 1200 612 L1200 675 L0 675 Z"
          :fill="`url(#front-${variant})`"
          opacity="0.94"
        />
        <g stroke="#164234" stroke-width="2" fill="none" opacity="0.45">
          <path d="M120 618 q-6 -34 4 -52" />
          <path d="M140 620 q4 -30 -2 -48" />
          <path d="M980 620 q-8 -32 2 -50" />
          <path d="M1004 622 q6 -28 -1 -46" />
        </g>
        <g stroke="#164234" stroke-width="1.4" fill="none" opacity="0.42">
          <path d="M540 246 q15 -13 30 0" />
          <path d="M578 228 q12 -11 24 0" />
        </g>
      </g>

      <!-- ============ 汉台春色（古建 + 花树，用于 Hero 第三帧） ============ -->
      <g v-else-if="variant === 'hantai'">
        <rect width="1200" height="675" fill="#f6efe1" />
        <circle cx="240" cy="132" r="132" :fill="`url(#sun-${variant})`" />
        <path
          d="M0 318 C140 292 280 318 420 304 C560 290 700 316 840 304 C980 292 1100 312 1200 306 L1200 675 L0 675 Z"
          :fill="`url(#far-${variant})`"
        />
        <rect x="0" y="306" width="1200" height="62" :fill="`url(#mist-${variant})`" opacity="0.62" />
        <!-- 台基 -->
        <path d="M0 486 L1200 486 L1200 512 L0 512 Z" fill="#ded3bf" />
        <rect x="0" y="512" width="1200" height="16" fill="#cabfaa" />
        <!-- 主体檐群（三层，汉式重檐） -->
        <path d="M120 470 L1080 470 L1020 418 L180 418 Z" fill="#103026" />
        <path d="M96 424 L1104 424 L1044 376 L156 376 Z" fill="#164234" />
        <path d="M150 382 L1050 382 L996 340 L204 340 Z" fill="#1d5544" />
        <!-- 正脊与脊饰 -->
        <rect x="200" y="332" width="800" height="10" rx="3" fill="#0b2119" />
        <g fill="#c09a4e" opacity="0.9">
          <path d="M186 332 l16 -12 l16 12 z" />
          <path d="M982 332 l16 -12 l16 12 z" />
        </g>
        <!-- 立柱 -->
        <g fill="#123a2e">
          <rect x="220" y="470" width="20" height="96" />
          <rect x="420" y="470" width="20" height="96" />
          <rect x="760" y="470" width="20" height="96" />
          <rect x="960" y="470" width="20" height="96" />
        </g>
        <!-- 门窗 -->
        <g fill="#0b2119" opacity="0.72">
          <rect x="480" y="470" width="72" height="96" rx="3" />
          <rect x="648" y="470" width="72" height="96" rx="3" />
        </g>
        <!-- 匾额 -->
        <rect x="520" y="410" width="160" height="42" rx="3" fill="#c09a4e" />
        <rect
          x="527"
          y="416"
          width="146"
          height="30"
          rx="2"
          fill="none"
          stroke="#8a6a2c"
          stroke-width="1.4"
        />
        <!-- 台阶 -->
        <g fill="#e2d8c6">
          <rect x="470" y="528" width="260" height="14" />
          <rect x="452" y="542" width="296" height="14" />
          <rect x="434" y="556" width="332" height="14" />
        </g>
        <!-- 花树（左） -->
        <g>
          <path d="M112 594 q-8 -46 6 -74" stroke="#3a3226" stroke-width="5" fill="none" />
          <g fill="#e6c9c2" opacity="0.92">
            <circle cx="118" cy="500" r="34" />
            <circle cx="88" cy="524" r="24" />
            <circle cx="148" cy="522" r="26" />
            <circle cx="118" cy="470" r="20" />
          </g>
          <g fill="#f2e2dc" opacity="0.8">
            <circle cx="104" cy="486" r="14" />
            <circle cx="144" cy="502" r="12" />
          </g>
        </g>
        <!-- 花树（右） -->
        <g>
          <path d="M1088 600 q10 -50 -4 -78" stroke="#3a3226" stroke-width="5" fill="none" />
          <g fill="#e6c9c2" opacity="0.9">
            <circle cx="1082" cy="510" r="30" />
            <circle cx="1112" cy="532" r="22" />
            <circle cx="1052" cy="530" r="22" />
          </g>
        </g>
        <!-- 地面 -->
        <path d="M0 596 L1200 596 L1200 675 L0 675 Z" fill="#e6ded0" />
      </g>

      <!-- ============ 汉江水面 ============ -->
      <g v-else>
        <circle cx="880" cy="128" r="128" :fill="`url(#sun-${variant})`" />
        <path
          d="M0 366 C160 330 320 366 480 350 C640 334 800 368 960 352 C1080 339 1150 356 1200 350 L1200 675 L0 675 Z"
          :fill="`url(#mid-${variant})`"
        />
        <path
          d="M0 424 C170 404 330 428 490 420 C650 412 800 430 950 422 C1080 415 1150 426 1200 422 L1200 675 L0 675 Z"
          :fill="`url(#near-${variant})`"
        />
        <rect x="0" y="440" width="1200" height="235" :fill="`url(#water-${variant})`" />
        <g fill="none" stroke="#ffffff" stroke-width="2.5" opacity="0.55">
          <path d="M120 500 C220 494 320 506 420 500" />
          <path d="M560 528 C660 522 760 534 860 528" />
          <path d="M240 566 C340 560 440 572 540 566" />
          <path d="M700 596 C800 590 900 602 1000 596" />
          <path d="M380 628 C480 622 580 634 680 628" />
        </g>
        <path
          d="M0 640 C170 632 330 648 490 642 C650 636 800 650 950 644 C1080 639 1150 646 1200 644 L1200 675 L0 675 Z"
          :fill="`url(#front-${variant})`"
          opacity="0.9"
        />
      </g>
    </svg>
  </div>
</template>

<style scoped>
.scene {
  position: relative;
  width: 100%;
  overflow: hidden;
  background: var(--paper-2);
  border-radius: inherit;
}
.scene__svg {
  width: 100%;
  height: 100%;
  display: block;
}
/* 深色底（管理端 / 深色卡片）时压暗并降低饱和，保持克制 */
.scene--deep .scene__svg {
  filter: brightness(0.82) saturate(0.92);
}
</style>
