package com.hanyou.brain.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.Product;

/** 乡村农产品。查询永远带 city_code 与 status 条件，见 ProductServiceImpl */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
