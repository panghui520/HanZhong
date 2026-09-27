package com.hanyou.brain.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.RiskRule;

/** 风险规则配置。规则引擎每次扫描前读一遍 enabled=true 的规则 */
@Mapper
public interface RiskRuleMapper extends BaseMapper<RiskRule> {
}
