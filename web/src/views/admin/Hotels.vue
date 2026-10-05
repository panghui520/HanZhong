<script setup lang="ts">
/**
 * 住宿管理
 *
 * ★ **住宿只有 4 个点**，排行标题写"全部 4 家"。
 *
 * ★ **本页必须真正支持 `lodging` 业态。** 重构前 `AdminResourceService` 只认
 *   `scenic` / `food` 两个 type，打 `?type=lodging` 会返回 `RESOURCE_TYPE_INVALID` ——
 *   数据一直在（`poi` 表里有 4 行 `LODGING`），缺的只是入口。
 *   本次给后端补了 `TYPE_LODGING` 与 `BIZ_LODGING` 分支（`§五`），
 *   所以这一页从列表到编辑、上下架、删除都是通的，不会出现 1002/1106。
 *
 * 住宿没有 `poi_image` 记录（0 张），所以列表的「配图」一列会显示 0 张、
 * 点开是空态并提示用手绘插画兜底 —— 这是**如实展示**，不是缺功能。
 */
import PoiBizPanel from '@/components/admin/PoiBizPanel.vue'
</script>

<template>
  <PoiBizPanel
    eyebrow="住在汉中"
    title="住宿管理"
    sub="全市 4 家住宿资源点。住宿是「留一晚」的生意 —— 它把一次到访变成过夜，是本项目「把到访变成消费链」里最直接的一环。"
    noun="住宿"
    kind="lodging"
    hero="rank"
    hero-title="住宿热度排行（全部 4 家）"
    hero-sub="全部 4 家 · 窗口内到访人次"
    hero-tip="点位只有 4 家，这里是全部排行而不是 Top N。数据来自 poi_visit_stats 的合成统计。"
    :biz-options="['LODGING']"
    default-biz-type="LODGING"
    biz-type="lodging"
    high-hint="窗口内平均承载率 ≥ 80% 的住宿点数量。住宿承载率反映的是床位占用，不是房间数。"
  />
</template>
