package com.hanyou.brain.vo;

import java.util.List;

import lombok.Data;

/**
 * 乡村体验项目对外视图。
 *
 * <p>poiName 是 Service 批量补上的（一次 selectBatchIds，不是循环里逐个查）：
 * 产品列表要显示"来自「高山茶园采制一日」"，体验列表要显示所属乡村，
 * 让前端为了一个名字再发一次请求不划算。
 */
@Data
public class ExperienceVO {

    private String id;
    private String poiId;

    /** 所属乡村点名称。由 Service 批量补全 */
    private String poiName;

    private String name;

    /** TEA / PICKING / FOLK / HOMESTAY / FOOD_MAKING / NATURE */
    private String type;

    private Integer durationMin;
    private Double price;
    private String season;
    private Integer capacity;

    /** 数据库里是逗号分隔字符串，对外统一成数组 */
    private List<String> tags;

    /**
     * 体验说明。
     * 实体里字段叫 description（列名不能用 SQL 保留字 desc），对外仍是 desc——
     * web/src/types/index.ts 的 Experience.desc 与这里逐字对应，前端不用改。
     */
    private String desc;

    private String dataOrigin;
    private String sourceUrl;
}
