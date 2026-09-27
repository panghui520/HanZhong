package com.hanyou.brain.service;

import java.util.List;

import com.hanyou.brain.vo.DiversionNoticeVO;

/**
 * M5 续：分流公告。
 *
 * <p><b>它补的是 M5 缺的最后一段用户可见闭环。</b>M5 原本的链路
 * "规则命中 → 风险事件 → 工单"全程只在管理端内部发生 —— 运营知道该分流了，
 * 但**要去这个景区的游客一无所知**。本服务把分流建议变成一条游客能看到的公告：
 * 运营审一眼、可改文案、发布，游客端首页与详情页随即出现。
 *
 * <p>与 {@link OpsService} 的分工：
 * <ul>
 *   <li>{@code OpsService} 管"判定与数据"（承载、风险事件、工单）；</li>
 *   <li>本服务管"**对外发声**"—— 什么话可以发到游客端、发多久、怎么撤。</li>
 * </ul>
 * 候选点的计算**不在这里重复一遍**，而是调
 * {@link OpsService#candidatesOf(Long)}：公告里推荐的村子必须与运营在事件上
 * 看到的是同一份，否则工单写 A、公告推 B。
 *
 * <p>安全边界（见 SecurityConfig）：运营端四个动作全在 {@code /api/admin/**} 下，
 * 自动继承 {@code hasRole("OPERATOR")}；游客端只放行
 * {@code GET /api/diversion-notices}，且**服务端强制过滤**
 * （只返回 PUBLISHED 且未过期），不靠前端自觉。
 */
public interface DiversionService {

    /**
     * 从风险事件生成公告草稿。
     *
     * <p>生成时就把候选算好写进快照 —— 之后承载怎么变，
     * 都还能回答"当时为什么推荐它"。
     *
     * @param title   公告标题，为空时用默认文案
     * @param message 公告正文，为空时由候选自动生成
     */
    DiversionNoticeVO createDraft(Long riskEventId, String title, String message);

    /**
     * 编辑文案 / 发布 / 撤下。
     *
     * <p>允许的流转：DRAFT→PUBLISHED、DRAFT→WITHDRAWN、
     * PUBLISHED→WITHDRAWN、WITHDRAWN→PUBLISHED（重新发布会**刷新失效时间**，
     * 否则重新发布出来的公告可能只剩几分钟寿命）。
     *
     * <p><b>已过失效时间的不允许再发布</b>（{@code NOTICE_EXPIRED}）：它的候选快照
     * 是过期那天算的，直接发出去等于拿旧方案指导今天的行程。判定看的是
     * {@code expire_at} 而不是 {@code status}，理由见实现类。
     * 出路是"重新生成"—— {@link #createDraft} 会顶替掉已过期的那条，
     * 所以运营不会卡在"既发不出去、又建不了新的"。
     *
     * @param status      目标状态，null 表示不改
     * @param publishedBy 发布人，null 表示不改
     */
    DiversionNoticeVO updateNotice(Long id, String title, String message, String status, String publishedBy);

    /**
     * 运营端列表（四态全给）。
     *
     * @param status 状态过滤，null 表示全部
     */
    List<DiversionNoticeVO> listNotices(String status);

    /**
     * 游客端列表：**只返回已发布且未过期的**。
     *
     * <p>过滤条件写在 SQL 里（{@code status='PUBLISHED' AND expire_at > NOW()}），
     * 不是查出来再在 Java 里筛 —— 少一次"有人改了查询忘了加条件"的机会。
     * 也不依赖定时任务：服务停了一夜、过期清理没跑，首页也不会出现过期公告。
     */
    List<DiversionNoticeVO> listPublished();

    /**
     * 把到期的公告置为 EXPIRED，返回条数。
     *
     * <p>由 M5 的规则扫描顺手调用（启动时一次 + 运营手动一次），
     * 与 {@code OpsScanRunner} 同一个节拍。**它不是游客端正确性的前提** ——
     * 查询侧已经带了时间条件，这里只是让运营列表看得准。
     */
    int expireOverdue();
}
