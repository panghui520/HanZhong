package com.hanyou.brain.service;

import java.util.List;

import com.hanyou.brain.vo.CityMetaVO;
import com.hanyou.brain.vo.PoiDetailVO;
import com.hanyou.brain.vo.PoiVO;

/** M1 统一资源：资源列表、资源详情（含关系）、城市档案 */
public interface PoiService {

    /**
     * 资源列表。三个条件都是可选的，传 null 表示不过滤。
     *
     * @param businessType 业态，如 SCENIC / RURAL_SPOT
     * @param district     区县
     * @param keyword      名称 / 简介 / 标签模糊匹配
     */
    List<PoiVO> listPois(String businessType, String district, String keyword);

    /** 资源详情，含 nearby / support / sameVillage / diversion 四组关系 */
    PoiDetailVO getPoiDetail(String id);

    /** 当前城市的档案（换城市只改 hanyou.city） */
    CityMetaVO getCityMeta();
}
