package com.hanyou.brain.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.Experience;

/** 乡村体验项目。产品通过 experience_id 挂在它上面 */
@Mapper
public interface ExperienceMapper extends BaseMapper<Experience> {
}
