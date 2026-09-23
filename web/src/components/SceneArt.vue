<script setup lang="ts">
/**
 * SceneArt —— 场景插画
 * 全部为手写 SVG，无外链图片：离线可演示、无版权问题、风格统一。
 * 视觉母题取自汉中：秦岭云海、茶园梯田、油菜花田、汉江、古建街巷。
 */
const props = withDefaults(
  defineProps<{
    variant?: 'qinling' | 'terrace' | 'rapeseed' | 'ancient' | 'river'
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
        <!-- 远檐 -->
        <path
          d="M0 300 L1200 300 L1200 330 L0 330 Z"
          fill="#c9bfae"
        />
        <path d="M-20 300 L1220 300 L1160 262 L40 262 Z" fill="#8f8578" />
        <!-- 主屋檐 -->
        <path d="M60 356 L1140 356 L1080 306 L120 306 Z" fill="#164234" />
        <path d="M0 380 L1200 380 L1200 404 L0 404 Z" fill="#1d5544" />
        <!-- 立柱 -->
        <g fill="#123a2e">
          <rect x="150" y="404" width="22" height="180" />
          <rect x="430" y="404" width="22" height="180" />
          <rect x="710" y="404" width="22" height="180" />
          <rect x="1000" y="404" width="22" height="180" />
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
        </g>
        <!-- 地面 -->
        <path d="M0 584 L1200 584 L1200 675 L0 675 Z" fill="#e6ded0" />
        <g stroke="#cdc4b4" stroke-width="2" opacity="0.8">
          <path d="M60 675 L220 584" />
          <path d="M420 675 L520 584" />
          <path d="M780 675 L700 584" />
          <path d="M1140 675 L960 584" />
        </g>
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
