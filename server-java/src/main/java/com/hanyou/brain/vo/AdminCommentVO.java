package com.hanyou.brain.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 景点评论（管理端视图，M10 续）。
 *
 * <p>与 {@link CommentVO} 的差别正是管理端多出来的那几个问题：
 * "这条评论挂在哪个景点上"（{@code poiName}）、"它现在是什么状态"
 * （{@code status}）、"是谁发的"（{@code userId}）。
 *
 * <p>{@code statusLabel} 与 M6 的 {@code TripCheckinVO.sourceLabel}、
 * M10 的 {@code AdminPoiVO.sourceLabel} 同一约定：中文由**服务端**给出，
 * 前端不维护映射表 —— 漂移的后果是运营界面上出现 {@code HIDDEN}
 * 这种给机器看的字符串。
 *
 * <p>{@code poiName} 同样由服务端补：管理端列表要按景点筛选，而运营
 * 认的是名字不是编码（P-SCE-001）。让前端自己去查一份全量景点列表再匹配，
 * 就是把这个映射维护在第二处。
 */
@Data
public class AdminCommentVO {

    private Long id;
    private String poiId;

    /** 评论所属景点名。服务端补，前端不查表 */
    private String poiName;

    private Long userId;
    private String nickname;

    private Integer rating;
    private String content;

    /** PENDING / APPROVED / HIDDEN */
    private String status;

    /** 中文名：待审核 / 已通过 / 已隐藏。**服务端算**，前端不映射 */
    private String statusLabel;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
