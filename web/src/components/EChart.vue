<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart, RadarChart, ScatterChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  MarkLineComponent,
  MarkPointComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

/**
 * 按需注册。**每加一种图表都要在这里注册**，否则 `setOption` 不报错、
 * 图上什么都不画 —— 这是最容易漏的一步。
 *
 * <p>2026-10-04 补了 `ScatterChart` + `MarkLine/MarkPoint`：管理端「乡村景点管理」
 * 的冷热分布图要用散点（x=客流、y=承载率），并在高位线上画一条 markLine。
 */
echarts.use([
  BarChart,
  LineChart,
  PieChart,
  RadarChart,
  ScatterChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  MarkLineComponent,
  MarkPointComponent,
  CanvasRenderer,
])

const props = withDefaults(
  defineProps<{
    option: any
    height?: string
    /**
     * 变体标记，会拼成 `echart--<variant>` 加到根元素上。
     *
     * 目前只用一个值：`spark`（驾驶舱四大业务总览卡里的 54px 迷你趋势图）。
     * 为什么要标出来：那些迷你图和页面主体的大图是**两类东西**，
     * 而页面级验收探针是按 `.echart` 的**出现顺序**取图的
     * （第 0 张是趋势、第 3 张是 Top 排行）。迷你图插在它们前面之后，
     * 顺序整体后移，探针会去读一张迷你图当趋势图 —— 报错看起来像"页面坏了"。
     * 给迷你图一个可区分的类，探针就能写成 `.echart:not(.echart--spark)`，
     * 比"记住新的下标"稳。
     */
    variant?: string
  }>(),
  { height: '260px', variant: '' }
)

/**
 * 点击事件。**这是 2026-10-04 加的、向后兼容的一层**：此前本组件只 setOption，
 * 图表点了没反应。驾驶舱要"点柱子看当日详情、点扇形看业态构成"，
 * 但那是**驾驶舱的需求**，不该写进这个通用组件里 —— 所以这里只把 ECharts
 * 原生的 click 参数透出去，谁用谁处理。没接 @click 的地方行为完全不变。
 */
const emit = defineEmits<{ (e: 'click', params: any): void }>()

const el = ref<HTMLDivElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)
let ro: ResizeObserver | null = null

onMounted(() => {
  if (!el.value) return
  chart.value = echarts.init(el.value, undefined, { renderer: 'canvas' })
  chart.value.setOption(props.option)
  // 只挂一次：图表实例在本组件生命周期内不重建（option 变化走 setOption）
  chart.value.on('click', (params: any) => emit('click', params))
  ro = new ResizeObserver(() => chart.value?.resize())
  ro.observe(el.value)
})

watch(
  () => props.option,
  (o) => chart.value?.setOption(o, true),
  { deep: true }
)

onBeforeUnmount(() => {
  ro?.disconnect()
  chart.value?.dispose()
})
</script>

<template>
  <div
    ref="el"
    class="echart"
    :class="props.variant ? `echart--${props.variant}` : ''"
    :style="{ height: props.height }"
  />
</template>

<style scoped>
.echart {
  width: 100%;
}
</style>
