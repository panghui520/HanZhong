package com.hanyou.brain.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hanyou.brain.entity.DiversionNotice;

/** 分流公告。运营从风险事件生成、编辑后发布，游客端读已发布且未过期的 */
@Mapper
public interface DiversionNoticeMapper extends BaseMapper<DiversionNotice> {
}
