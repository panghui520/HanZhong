package com.hanyou.brain.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 一条到访足迹（M6 到访消费链）。
 *
 * <p>与 {@code OrderVO} 一样，{@code sourceLabel} 由**服务端**给出中文，
 * 前端不维护"source → 中文名"的映射表：漂移的后果是界面上出现
 * {@code DIVERSION} 这种给机器看的字符串，而这一列恰恰是
 * 创新点一（分流引导）唯一的展示位。
 *
 * <p>{@code poiName} / {@code experienceName} 取自足迹行上的**快照列**，
 * 不是 join 出来的。理由见 {@code TripCheckin} 的注释：数据包会被重灌，
 * 足迹必须自带来源。
 */
@Data
public class TripCheckinVO {

    private Long id;

    /** 到访资源点。只打卡了体验项目时可能为 null（Jackson 配了 non_null，字段会消失） */
    private String poiId;

    private String poiName;

    /** 到访时参与的体验项目 */
    private String experienceId;

    private String experienceName;

    private LocalDateTime checkinAt;

    /** REAL / SIM / DIVERSION。原始值，用于前端做样式区分 */
    private String source;

    /** 中文名：到访打卡 / 演示数据 / 分流到访。**服务端算**，前端不映射 */
    private String sourceLabel;

    private String note;
}
