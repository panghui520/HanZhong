package com.hanyou.brain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 旅行的可变上下文（M4 智能行程）。一次旅行一行（uk_trip）。
 *
 * <p><b>它存在的理由只有一条：让助手记得住。</b>没有这张表时，用户上一轮
 * 在卡片上点了"选择酒店"，下一轮问"这附近有什么好吃的"，助手只能反问
 * "您住哪家酒店" —— 每次对话都从零开始，那就只是个搜索框。
 *
 * <p>酒店为什么拆成 6 列而不是一个 JSON：核心用法是"读出经纬度直接去
 * 高德附近搜索"。经纬度必须能被直接取出来当查询参数，塞进 JSON 的话每次
 * 都要在应用层解一遍、还要自己处理字段缺失与类型不符，而这两列恰恰最不能错
 * （错了就是搜到别的城市去）。
 *
 * <p>经纬度用 {@link BigDecimal} 而不是 double：它们是要**回读并原样交给高德**
 * 的查询参数。double 存进去再读出来可能变成 107.01999999999999，
 * 拼进请求就是非法坐标。库里的列是 DECIMAL(10,6)，写什么读出来就是什么。
 *
 * <p>{@link #hotelBookingStatus} 是为以后接预订能力预留的开关，当前只写
 * {@code not_booked}。本阶段不接任何真实预订：不做库存、不做支付、不做订单，
 * 也不调美团/携程的内部接口；卡片上的"去第三方预订"是跳转链接，
 * 跳走之后的事情不在本系统内。
 */
@Data
@TableName("trip_context")
public class TripContext {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属旅行，-> trip.id。(trip_id) 唯一 */
    private Long tripId;

    // ---- 已选酒店：连续上下文的载体 ----

    /** 高德 POI id。前端靠它判断"这张卡片已选中" */
    private String selectedHotelPoiId;

    /** 酒店名，原样存高德返回值 */
    private String selectedHotelName;

    /** 地址，原样存高德返回值 */
    private String selectedHotelAddress;

    /** 经度。高德坐标是"经度,纬度"，经度在前 */
    private BigDecimal selectedHotelLng;

    /** 纬度 */
    private BigDecimal selectedHotelLat;

    /** 数据来源，当前恒为 amap。留这个字段是为了以后接自有酒店数据时能区分 */
    private String selectedHotelSource;

    /**
     * not_booked / external_pending / booked / cancelled。
     * 本阶段不产生真实订单，所以只会出现 not_booked。
     */
    private String hotelBookingStatus;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
