package com.hanyou.brain.vo;

import java.util.List;

import lombok.Data;

/**
 * 乡村农产品对外视图。
 *
 * <p>category 与 categoryCode 都给：category 是分类中文名，前端卡片直接显示；
 * categoryCode 是稳定编码，M4 行程规划按"带点茶叶回去"挑产品、M7 按分类做
 * 销售分析时要用它。用中文名做筛选条件会在改一次分类名之后全部失效。
 *
 * <p>experienceName / poiName 同样由 Service 批量补全——"产品挂在哪次体验上"
 * 是这个项目区别于普通电商的核心信息，不能留给前端自己拼。
 */
@Data
public class ProductVO {

    private String id;
    private String poiId;

    /** 产地乡村点名称。由 Service 批量补全 */
    private String poiName;

    private String experienceId;

    /** 体验锚点名称。由 Service 批量补全 */
    private String experienceName;

    /** 分类编码（-> product_category.code），用于筛选 */
    private String categoryCode;

    /** 分类显示名，用于展示 */
    private String category;

    private String name;
    private String spec;
    private Double price;
    private String originVillage;
    private Integer stock;

    /** 数据库里是逗号分隔字符串，对外统一成数组 */
    private List<String> tags;

    private String story;
    private String scene;
    private String dataOrigin;
    private String sourceUrl;
}
