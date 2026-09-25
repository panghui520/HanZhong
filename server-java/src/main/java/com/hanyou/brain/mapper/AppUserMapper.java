package com.hanyou.brain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.AppUser;

import org.apache.ibatis.annotations.Mapper;

/** 用户账号（M8）。 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
