package com.hanyou.brain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 到访足迹（M6 到访消费链）。
 *
 * <p>一次到访一行，是"体验锚点消费链"的起点：到访 → 足迹 → 复购推荐的输入。
 *
 * <p><b>刻意不带 {@code city_code}</b>：足迹是用户行为产生的运行期事实，
 * 重启不能丢。带了就会被 {@code CityPackImporter} 按城市全量重灌删掉。
 *
 * <p>{@code poi_name} / {@code experience_name} 是**快照**，不是引用：
 * {@code poi} 与 {@code experience} 表带 {@code city_code}、每次启动会被重灌，
 * 只存 id 的话，换一次数据包足迹就会指向另一个同名的点。与
 * {@code order_item} 冗余 experience_id / poi_id 是同一个理由。
 *
 * <p>{@code source} 三值的分工见 {@code db/V10__m6_trip_chain.sql} 文件头。
 * 其中 {@code DIVERSION} 是唯一能证明"这次到访是被分流引导来的"的值 ——
 * 创新点一的效果证据就落在这里。
 */
@Data
@TableName("trip_checkin")
public class TripCheckin {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 打卡人，-> app_user.id。设计稿写 visitor_id，实际用 user_id（无独立 visitor 表，见 V10 文件头） */
    private Long userId;

    /** 所属旅行，-> trip.id。允许为空：SIM 种子与分流回流的足迹不挂在某次用户行程上 */
    private Long tripId;

    /** 到访资源点，-> poi.poi_id。与 experienceId 至少一项非空（DB 层有 CHECK 兜底） */
    private String poiId;

    /** 资源点名称快照 */
    private String poiName;

    /** 到访时参与的体验项目，-> experience.experience_id */
    private String experienceId;

    /** 体验项目名称快照 */
    private String experienceName;

    /** 到访时刻。到访消费链判定 channel 的基准 */
    private LocalDateTime checkinAt;

    /** REAL 用户主动打卡 / SIM 演示种子 / DIVERSION 分流引导产生的到访 */
    private String source;

    /** 游客随手记一句。当前只存不用，不参与任何判定 */
    private String note;

    private LocalDateTime createdAt;
}
