<script setup lang="ts">
/**
 * 乡村景点管理
 *
 * 这一页在**同一个页面里**管两类资源：
 *   · 核心景区（`SCENIC`，18 个点）—— 客流主力，承载率普遍偏高；
 *   · 乡村景点（`RURAL_SPOT`，12 个点）—— 分流承接方，承载率低。
 *
 * 两者在数据上是**两个业态**（`business` 接口传的 type 不同），所以用分段切换，
 * 而不是"一个列表加个筛选框"：KPI、图表、排行全都跟着业态变，
 * 只切列表不切图，就会出现"图上画的是景区、表里列的是乡村"。
 *
 * ★ 后端的 `type=scenic` 端点**同时返回景区 + 乡村旅游**两类（同一个 poi 表，
 *   业态是行上的字段）。所以这里不管切到哪一段，列表都走 scenic 端点，
 *   再用 `types` 在前端切一刀 —— 后端一个参数没加。
 *
 * 主图是**冷热散点**（`§八` 指定的视觉重点）：X 是客流、Y 是平均承载率。
 * 它一眼回答"哪些点人不多但已经很挤" —— 单看排行或单看承载率表都答不出。
 *
 * 底部的「景点评论」是原来 `Resources.vue` 第四个 tab 的落点：评论只挂在 poi 上，
 * 属于这条业务线，所以跟着景点页走，而不是被重构删掉。
 */
import { useRoute } from 'vue-router'
import PoiBizPanel from '@/components/admin/PoiBizPanel.vue'
import CommentSection from '@/components/admin/CommentSection.vue'

const route = useRoute()

const SEGMENTS = [
  { key: 'core', label: '核心景区', bizType: 'scenic' as const, types: ['SCENIC'] },
  { key: 'rural', label: '乡村景点', bizType: 'rural' as const, types: ['RURAL_SPOT'] },
]
</script>

<template>
  <PoiBizPanel
    eyebrow="景区与乡村"
    title="乡村景点管理"
    sub="景区是客流主力，乡村点是分流的承接方 —— 两者看的是同一组指标（客流、承载、风险），但结论完全不同。上面的分段切换会同时切换 KPI、图表与资源列表。"
    noun="景点"
    kind="scenic"
    hero="scatter"
    hero-title="资源冷热分布"
    :segments="SEGMENTS"
    :initial-seg="String(route.query.seg ?? '')"
    :biz-options="['SCENIC', 'RURAL_SPOT']"
    default-biz-type="SCENIC"
    biz-type="scenic"
    high-hint="窗口内平均承载率 ≥ 80% 的资源数。景区的预警阈值可在编辑抽屉里按资源单独覆盖，全局阈值由运营分析配置。"
  >
    <template #after>
      <CommentSection />
    </template>
  </PoiBizPanel>
</template>
