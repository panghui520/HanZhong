package com.hanyou.brain.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.PoiVisitStat;

/**
 * 客流与经营日度统计（M5，合成数据）。
 *
 * <p>导入器按 city_code 全量重灌，规则引擎按 stat_date 读。
 * 复杂的窗口聚合（近 7 日 / 近 30 日）在 RuleEngine 里用 Java 算，
 * 不写进 XML —— 规则逻辑集中在一处才看得懂。
 */
@Mapper
public interface PoiVisitStatMapper extends BaseMapper<PoiVisitStat> {
}
