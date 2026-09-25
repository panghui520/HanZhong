package com.hanyou.brain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.AuthEmailCode;

import org.apache.ibatis.annotations.Mapper;

/** 邮箱验证码（M8）。 */
@Mapper
public interface AuthEmailCodeMapper extends BaseMapper<AuthEmailCode> {
}
