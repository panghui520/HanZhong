package com.hanyou.brain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 农产品分类。
 * parentCode 留给后续二级分类，当前数据只有一级。
 */
@Data
@TableName("product_category")
public class ProductCategory {

    @TableId(type = IdType.INPUT)
    private String code;

    private String cityCode;
    private String name;

    /** 父分类编码，null 表示一级分类 */
    private String parentCode;

    private Integer sort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
