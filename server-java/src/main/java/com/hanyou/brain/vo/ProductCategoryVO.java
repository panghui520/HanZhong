package com.hanyou.brain.vo;

import lombok.Data;

/**
 * 农产品分类对外视图。
 *
 * <p>productCount 由后端算好：首页的分类筛选要显示"茶叶 3"，前端若自己遍历
 * 产品数组统计，就得先保证它拿到的产品列表和后端一致——多一条数据源就多一处
 * 可能对不上。计数与列表由同一处逻辑产出，才不会出现"标签写着 3 款、点进去 2 款"。
 */
@Data
public class ProductCategoryVO {

    private String code;
    private String name;

    /** 排序值，越小越靠前 */
    private Integer sort;

    /** 该分类下在售产品数 */
    private Integer productCount;
}
