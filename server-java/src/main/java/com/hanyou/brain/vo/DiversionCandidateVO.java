package com.hanyou.brain.vo;

import lombok.Data;

/**
 * 一个分流候选点（对外视图）。
 *
 * <p><b>★ 这里同时有两组数：快照与当前。</b>这不是冗余，是这张 VO 存在的理由。
 *
 * <ul>
 *   <li><b>快照组</b>（{@code km / usage / similarity / reason}）——
 *       公告**发布那一刻**的指标。它的作用只有一个：回答"当时为什么推荐它"。
 *       这几个数**永远不变**，否则历史公告会被后来的数据改写。</li>
 *   <li><b>当前组</b>（{@code currentUsage / available}）——
 *       游客打开公告那一刻重算的。承载率是日粒度、每天变的数，
 *       发布时说 B 村 23%，现在可能已经 90% 了。</li>
 * </ul>
 *
 * <p>只给快照，等于拿旧数据骗游客；只给当前，就答不出"为什么是它"。
 * 两者都给，前端才能诚实地显示"推荐时 23%（当前 91%，已不建议前往）"。
 *
 * <p><b>两组数在什么情况下相同：</b>运营打开**风险事件**时，候选是此刻现算的，
 * 两组数必然相同 —— 没有"更早的版本"可言。两组数分开是在**公告**上：
 * 公告发布之后承载还会变，那时才真正是"发布时 vs 当前"。
 */
@Data
public class DiversionCandidateVO {

    private String poiId;
    private String name;
    private String businessType;
    private String district;

    // ---- 快照：发布那一刻，不变 ----

    /** 距 A 点的距离（km）。地理距离不会变，所以这一项快照与当前是同一个数 */
    private Double km;

    /** 发布时的承载占用率，0–1 */
    private Double usage;

    /**
     * 相似度 0–1（标签 Jaccard + 同画面 + 同区县）。
     * **不参与排序** —— 排序是"承载档位 + 距离升序"，见 {@code DiversionAdvisor}。
     * 它在这里的作用是让"同「三国」主题"这句理由可核对。
     */
    private Double similarity;

    /** 一句可核对的理由：距离 + 承载 + 共同主题 */
    private String reason;

    // ---- 当前：展示时重算 ----

    /**
     * 当前承载占用率。**为 null 表示读不到**，不是 0 ——
     * "不知道"和"很空"是两个结论，前端必须分开显示。
     */
    private Double currentUsage;

    /**
     * 当前还能不能去：{@code currentUsage} 非空且低于上限时为 true。
     * 承载读不到时也是 false —— 无法确认"现在宽裕"，就不该让游客跑一趟。
     */
    private Boolean available;
}
