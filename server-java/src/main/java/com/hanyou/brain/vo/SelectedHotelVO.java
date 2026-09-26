package com.hanyou.brain.vo;

import lombok.Data;

/**
 * 本次行程已选的酒店（M4 智能行程）。
 *
 * <p>字段与高德 POI 卡片一一对应，但**只留"下一轮对话真的会用到"的那几个**：
 * 名字与地址用于展示，坐标用于"这附近有什么"的附近搜索，
 * poi_id 用于前端判断卡片是否已选中。评分、电话、图片不存 ——
 * 那些是"看一次就够"的展示信息，卡片上已经给过了。
 *
 * <p><b>坐标为什么是 String 而不是 double：</b>
 * 这两个值的唯一用途是被回读并原样拼进高德的查询参数。用 double 表达
 * 就要在"序列化成 JSON → 前端 → 反序列化回来 → 拼字符串"这条链上
 * 反复经过二进制浮点，每一跳都可能出现 107.01999999999999 这种结果，
 * 而它拼进请求里就是非法坐标。字符串则全程原样传递。
 * 高德自己的接口也用字符串表达坐标，这里跟它对齐。
 *
 * <p>{@link #location} 是拼好的 {@code "经度,纬度"}，与高德
 * {@code location} 参数格式完全一致，调用方不需要自己拼（也就不会拼反）。
 */
@Data
public class SelectedHotelVO {

    private String poiId;

    private String name;

    private String address;

    /** 经度。高德坐标是"经度,纬度"，经度在前 */
    private String longitude;

    /** 纬度 */
    private String latitude;

    /** 拼好的 "经度,纬度"，直接可用作高德 location 参数 */
    private String location;

    /** 数据来源，当前恒为 amap */
    private String source;
}
