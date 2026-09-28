package com.hanyou.brain.vo;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 管理端资源视图（M10）：景点与美食共用。
 *
 * <p>景点（SCENIC / RURAL_SPOT）与美食（FOOD）在库里是同一张 {@code poi} 表
 * 的不同 {@code business_type}，所以共用一个 VO —— 建两个字段完全一样的类，
 * 只会让"改一处忘一处"多一个机会。
 *
 * <p><b>为什么不直接复用游客端的 {@code PoiVO}</b>：两者要回答的问题不同。
 * 游客端只关心"这个地方是什么样"，管理端还要关心"它上架没有、是数据包带来的
 * 还是运营自己加的"。把这些字段塞进 PoiVO，游客端的响应里就会多出两个
 * 它永远用不到、却可能被误用的字段。
 *
 * <p>{@code sourceLabel} 与 M6 的 {@code TripCheckinVO.sourceLabel} 同一约定：
 * 中文由**服务端**给出，前端不维护映射表 —— 漂移的后果是运营界面上出现
 * {@code ADMIN} 这种给机器看的字符串。
 */
@Data
public class AdminPoiVO {

    private String id;
    private String name;

    /** SCENIC 景区 / RURAL_SPOT 乡村旅游 / FOOD 餐饮 */
    private String businessType;

    private String district;

    /** 详细地址（街道门牌）。db/V12 新增，数据包暂无此字段 */
    private String address;

    private String level;

    /** 对外联系电话。db/V12 新增 */
    private String phone;

    private Double lng;
    private Double lat;
    private Double ticketPrice;
    private String openHours;
    private Integer durationMin;
    private Integer capacity;

    /**
     * 承载率预警线（0.01~1.00），null = 用 risk_rule 的全局阈值。
     *
     * <p>与 {@link PoiVO} 刻意不同：游客端拿不到这一列。它是运营口径，
     * 而且只在管理端的编辑表单里被设置。
     */
    private Double warningThreshold;

    private List<String> tags;
    private String summary;

    /** 详细介绍正文（db/V12 新增） */
    private String detail;

    private String scene;
    private String dataOrigin;
    private String sourceUrl;

    /** 1 上架 / 0 下架 */
    private Integer status;

    /** PACK 数据包 / ADMIN 运营新建 */
    private String source;

    /** 中文名：城市数据包 / 运营新建。**服务端算**，前端不映射 */
    private String sourceLabel;

    /**
     * 配图张数（M9 的 poi_image）。
     *
     * <p>列表上直接标出来，运营一眼能看出"哪些资源还没有图"，
     * 不用逐个点进配图面板去确认。图片本身的增删改仍走 M9 的接口，
     * 这里只做计数。
     */
    private Integer imageCount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
