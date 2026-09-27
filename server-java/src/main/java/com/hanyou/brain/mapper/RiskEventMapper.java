package com.hanyou.brain.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.RiskEvent;

/** 风险事件。规则引擎写入，风险与工单页读取 */
@Mapper
public interface RiskEventMapper extends BaseMapper<RiskEvent> {
}
