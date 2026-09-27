package com.hanyou.brain.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.TripCheckin;

/**
 * 到访足迹（M6 到访消费链）。
 *
 * <p>只做单表读写：足迹的判定（同一天同目标算一次）在
 * {@code TripCheckinService} 里用 Java 写，不写进 XML ——
 * 规则集中在一处才看得懂，与 {@code PoiVisitStatMapper} 同一取舍。
 */
@Mapper
public interface TripCheckinMapper extends BaseMapper<TripCheckin> {
}
