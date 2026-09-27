package com.hanyou.brain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 分流公告。运营把"往哪分流"下发到游客端的那一条。
 *
 * <p><b>它补的是 M5 缺的最后一段用户可见闭环。</b>在此之前
 * "规则命中 → 风险事件 → 工单"全程只在管理端内部发生：运营知道该分流了，
 * 但**要去这个景区的游客一无所知**。本表把分流建议变成一条游客能看到的公告。
 *
 * <p><b>★ 为什么 {@code expireAt} 不可为空：</b>承载率是日粒度、每天变的数。
 * 今天的公告说"去 B 村"，明天 B 村可能自己就满了，而首页那条公告不会自己消失。
 * 所以：
 * <ul>
 *   <li>过期时间**写死在行上**，不靠"读的时候按 stat_date 推算" ——
 *       推算出来的寿命会随服务重启的日期漂移；</li>
 *   <li>查询侧一律带 {@code status='PUBLISHED' AND expire_at > NOW()}，
 *       **不依赖任何定时任务**。服务停了一夜、定时任务没跑，
 *       也不会出现过期公告挂在首页 —— 这是 fail-safe 与 fail-open 的区别。</li>
 * </ul>
 *
 * <p><b>{@code candidatesJson} 存快照</b>：公告一旦发布，就要能回答
 * "当时为什么推荐 B"。存的是发布那一刻的距离 / 承载 / 相似度 / 得分。
 * 游客端展示时**再校验一次当前承载**（满了标灰），所以既不用旧数据骗人，
 * 也说得清当时是怎么算的。
 *
 * <p><b>不带 city_code</b>：运营动作产生的运行期事实，重启不能丢。
 * 带了就会随 CityPackImporter 的重灌一起被删掉，运营发的公告凭空消失。
 */
@Data
@TableName("diversion_notice")
public class DiversionNotice {

    /** 草稿：候选已算好、文案可改，只有运营看得到 */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 已发布：游客端可见（前提是 expireAt 还没到） */
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 运营主动撤下（例如 A 点客流回落，不需要分流了） */
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    /** 到期自动置位。保留这一态而不是删行：公告发过就要留痕 */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 公告默认寿命（小时）。取 24 是因为它正好覆盖一个统计日 —— 数据日粒度就是一天 */
    public static final int DEFAULT_TTL_HOURS = 24;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编码，形如 DN-20260927-001 */
    private String code;

    /** 来源风险事件。允许为空：以后可能有非风险来源的公告 */
    private Long riskEventId;

    /** 溢出的资源点（A 点） */
    private String fromPoiId;

    /** A 点名称快照。资源改名或下架后，历史公告仍要说得清当时说的是谁 */
    private String fromPoiName;

    private String district;

    private String title;
    private String message;

    /** 候选快照 JSON 数组。列类型是 MySQL 的 JSON，写入时由数据库校验格式 */
    private String candidatesJson;

    private String status;

    /** 失效时间。游客端查询一律带 expire_at > NOW() */
    private LocalDateTime expireAt;

    /** 发布人（自由文本，同 work_order.assignee：当前无人员管理） */
    private String publishedBy;
    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
