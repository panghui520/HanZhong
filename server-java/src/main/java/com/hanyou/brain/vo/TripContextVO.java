package com.hanyou.brain.vo;

import lombok.Data;

/**
 * 当前行程与它的上下文（M4 智能行程）。
 *
 * <p>一次返回行程身份 + 已选酒店，而不是拆成两个接口：前端拿它做两件事 ——
 * 顶部显示"当前行程：汉中 · 2026-hanzhong-001"，以及高亮已选中的酒店卡片。
 * 这两件事总是同时发生，拆开只会多一次请求和一次"两个接口返回的行程
 * 不是同一次"的时序问题。
 *
 * <p>{@link #hotel} 为 null 表示还没选酒店（Jackson 配置了 non_null，
 * 这个字段会整个消失，前端按可选字段处理）。
 */
@Data
public class TripContextVO {

    private Long tripId;

    /** 业务编码，形如 2026-hanzhong-001 */
    private String tripCode;

    /** 目的地显示名（汉中） */
    private String destination;

    /** 目的地城市编码（hanzhong） */
    private String destinationCode;

    /** ACTIVE / ARCHIVED */
    private String status;

    /** 已选酒店。null = 未选择 */
    private SelectedHotelVO hotel;

    /**
     * not_booked / external_pending / booked / cancelled。
     * 与 hotel 独立：没有酒店时它是默认值 not_booked。
     */
    private String hotelBookingStatus;
}
