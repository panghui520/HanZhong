package com.hanyou.brain.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.ProductCategory;

/** 农产品分类。只有十来行，列表接口全量查回来 */
@Mapper
public interface ProductCategoryMapper extends BaseMapper<ProductCategory> {
}
