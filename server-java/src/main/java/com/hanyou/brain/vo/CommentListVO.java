package com.hanyou.brain.vo;

import java.util.List;

import lombok.Data;

/**
 * 景点评论列表 + 评分汇总（游客端，M10 续）。
 *
 * <p><b>为什么评分要和列表一起返回，而不是让前端把 items 求平均</b>：
 * 列表是**有上限**的（详情页最多显示前 N 条），而口碑评分应当是**全部**
 * 评论的均值。前端求平均，在评论超过上限之后就会算出一个"最近 N 条的平均"，
 * 页面上却写着"口碑评分" —— 一个看起来对、其实口径错了的数。
 *
 * <p><b>{@code averageRating} 为 null 表示"还没有任何评论"，不是 0</b>：
 * 0 分是"所有人都打了最低分"（一个结论），没有评论是"没有数据"（另一个结论）。
 * 把它显示成 0 等于凭空给了一个结论 —— 与 PoiDetail.vue 里承载率读不到时
 * 显示 "—" 而不是 0% 是同一条取舍。
 */
@Data
public class CommentListVO {

    private List<CommentVO> items;

    /** 已通过评论的条数（不是 items.size()，后者有显示上限） */
    private Integer total;

    /** 已通过评论的平均分，保留一位小数。无评论时为 null */
    private Double averageRating;
}
