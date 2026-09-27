package com.hanyou.brain.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.WorkOrder;

/** 处置工单。由风险事件一键建单，运营处置后回写 */
@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {
}
