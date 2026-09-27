package com.hanyou.brain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 处置工单。风险事件 → 一键建单 → 处置 → 反馈，是"工单闭环"的落点。
 *
 * <p>{@code code} 形如 {@code WO-20260927-001}（日期 + 当日序号）。用业务
 * 编码而不是自增 id 对外，理由同 M4 的 {@code trip.code}：它会写进界面与
 * 演示截图，1 / 2 / 3 看起来像测试数据，而 WO-20260927-001 一眼读得出
 * 哪天建的。
 *
 * <p>{@code suggestion} 与 {@link RiskEvent#getSuggestion()} 的区别：
 * 事件上那份是"为什么报这个警"（规则内置、不会被改），工单上这份是
 * "这张单要怎么办"（建单时运营可以改写）。一开始内容相同，但工单会
 * 被编辑，所以必须分开存。
 *
 * <p><b>不带 city_code</b>：运行期数据，重启不能丢，而且工单上还挂着处置人。
 */
@Data
@TableName("work_order")
public class WorkOrder {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";

    /** 分流：把热点景区的溢出客流导向乡村。本项目最核心的一类工单 */
    public static final String TYPE_DIVERSION = "DIVERSION";
    /** 曝光：调整产品推荐权重 */
    public static final String TYPE_EXPOSURE = "EXPOSURE";
    /** 服务：服务质量整改 */
    public static final String TYPE_SERVICE = "SERVICE";
    /** 关注：只观察，不派活 */
    public static final String TYPE_MONITOR = "MONITOR";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    /** 来源风险事件。允许为空：以后可能有非风险来源的工单 */
    private Long riskEventId;

    private String title;
    private String type;
    private String level;
    private String status;

    /** 处置人（自由文本，当前没有人员管理界面） */
    private String assignee;

    private String suggestion;

    /** 处置反馈，完结时填写 */
    private String result;

    private LocalDateTime handledAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
