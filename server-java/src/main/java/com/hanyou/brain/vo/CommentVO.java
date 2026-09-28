package com.hanyou.brain.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 景点评论（游客端视图，M10 续）。
 *
 * <p><b>为什么没有 userId</b>：游客看评论只需要知道"谁写的（昵称）、几星、
 * 说了什么、什么时候"。把 userId 暴露出去，前端就多了一个可以拿去做
 * "按人聚合"的字段，而这件事当前没有任何页面要做。
 *
 * <p><b>为什么没有 status</b>：这个 VO 只由"已通过"的评论构造出来
 * （过滤在 CommentService，不在前端）。响应里带一个恒为 APPROVED 的字段，
 * 只会让人以为"前端也能拿到未审核的评论"。
 *
 * <p>昵称由服务端查 {@code app_user} 现取，与 M6 的订单评价同一做法。
 */
@Data
public class CommentVO {

    private Long id;
    private String poiId;

    /** 昵称。注册时未填则由服务层用邮箱前缀兜底（见 AuthService.normalizeNickname） */
    private String nickname;

    /** 1..5 */
    private Integer rating;

    private String content;

    private LocalDateTime createdAt;
}
