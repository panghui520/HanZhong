<script setup lang="ts">
/**
 * 餐饮管理
 *
 * ★ **餐饮只有 6 个点。** 这一点决定了本页的两个写法：
 *
 *   1. 排行标题写"全部 6 家"，**不写 Top10** —— 6 个点的 Top10 是个假标题，
 *      而且数字还是 6，答辩时一眼就被看穿。
 *   2. 不做"评价构成"图。`poi_visit_stats` 里餐饮确实有 `review_count`（396 条），
 *      但那是**聚合计数**，没有 `poi_comment` 明细（明细只有 2 条、全在景区）。
 *      拿一个数字画饼图，饼上只有一块 —— 那不是在展示数据，是在凑图。
 *
 * 数据全部来自 `poi` 表里 `business_type='FOOD'` 的行（一直就有，缺的只是入口），
 * 没有新增任何表或仿真数据。
 */
import PoiBizPanel from '@/components/admin/PoiBizPanel.vue'
</script>

<template>
  <PoiBizPanel
    eyebrow="吃在汉中"
    title="餐饮管理"
    sub="全市 6 家餐饮资源点。餐饮的客流不靠门票，靠「顺路吃一顿」—— 所以这一页看的是热度与承载，不是销售额。"
    noun="餐饮"
    kind="food"
    hero="rank"
    hero-title="餐饮热度排行（全部 6 家）"
    hero-sub="全部 6 家 · 窗口内到访人次"
    hero-tip="点位只有 6 家，这里是全部排行而不是 Top N。数据来自 poi_visit_stats 的合成统计。"
    :biz-options="['FOOD']"
    default-biz-type="FOOD"
    biz-type="food"
    high-hint="窗口内平均承载率 ≥ 80% 的餐饮点数量。餐饮承载率偏高通常出现在午市集中时段。"
  />
</template>
