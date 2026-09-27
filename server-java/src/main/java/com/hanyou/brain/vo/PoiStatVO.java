package com.hanyou.brain.vo;

import java.util.List;

import lombok.Data;

/**
 * 单个资源点的实时统计（游客端用）。
 *
 * <p>这个接口是给「探索汉中」「AI 助手 · 行程规划」「资源详情页」三处替换
 * 前端 mock（{@code web/src/mock/stats.ts}）用的：那三个页面此前都在浏览器里
 * 按 POI id 现算承载率，于是"详情页说承载 88%、管理端大屏说 91%"这种
 * 自相矛盾迟早会出现。现在两边读同一份 {@code poi_visit_stats}。
 *
 * <p>{@code weekVisitors} 给的是**近 7 日**（不含今天）的到访序列，
 * 详情页的迷你柱图用它。为什么不含今天：今天的数据在 {@code todayVisitors}
 * 里已经单独给了，重复计入会让柱图最后两根一样高，看着像数据错了。
 */
@Data
public class PoiStatVO {

    private String poiId;

    /** 当日承载占用率，0–1（>1 表示超载） */
    private Double capacityUsage;

    /** 当日到访人次 */
    private Integer todayVisitors;

    /** 当日是否处于高位（承载 >= 80%）。前端用它决定要不要提示分流 */
    private Boolean high;

    /** 近 7 日（不含今天）的到访人次，按日期升序 */
    private List<Integer> weekVisitors;

    /** 当日有没有统计数据。没有时前端应显示"暂无数据"而不是 0 */
    private Boolean hasData;
}
