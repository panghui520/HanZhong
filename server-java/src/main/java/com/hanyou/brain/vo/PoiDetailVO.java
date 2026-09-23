package com.hanyou.brain.vo;

import java.util.List;

import lombok.Data;

/**
 * 资源详情。关系按类型分组返回，前端不需要再自己算距离。
 *
 * <p>这四个分组不是拍脑袋分的，分别对应四类业务动作：
 * nearby 同一线路串联、support 吃住行配套、sameVillage 乡村片区联动、
 * diversion 承载吃紧时往哪里引——最后一项是"客流下乡"在接口层的落点。
 */
@Data
public class PoiDetailVO {

    private PoiVO poi;

    /** 邻近资源（同城，距离近） */
    private List<RelationVO> nearby;

    /** 配套业态（吃住行，不同 business_type） */
    private List<RelationVO> support;

    /** 同片区乡村点 */
    private List<RelationVO> sameVillage;

    /** 可分流承接的乡村点（仅景区有） */
    private List<RelationVO> diversion;
}
