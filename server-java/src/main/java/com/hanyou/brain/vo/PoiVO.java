package com.hanyou.brain.vo;

import java.util.List;

import lombok.Data;

/**
 * 资源点对外视图。
 *
 * <p>Java 侧字段是驼峰，序列化时由 JacksonConfig 统一转成 snake_case
 * （businessType → business_type），与 web/src/types/index.ts 里的 Poi 定义逐字对应。
 * 这样前端不需要写任何字段转换代码，也不会出现"后端改了字段名前端静默变 undefined"。
 */
@Data
public class PoiVO {

    private String id;
    private String name;
    private String businessType;
    private String district;
    private String level;
    private Double lng;
    private Double lat;
    private Double ticketPrice;
    private String openHours;
    private Integer durationMin;
    private Integer capacity;

    /** 数据库里是逗号分隔字符串，对外统一成数组 */
    private List<String> tags;

    private String summary;
    private String scene;
    private String dataOrigin;
    private String sourceUrl;
}
