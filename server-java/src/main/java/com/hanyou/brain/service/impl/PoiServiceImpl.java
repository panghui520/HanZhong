package com.hanyou.brain.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.RelationType;
import com.hanyou.brain.common.VoUtils;
import com.hanyou.brain.config.HanYouProperties;
import com.hanyou.brain.entity.CityProfile;
import com.hanyou.brain.entity.Poi;
import com.hanyou.brain.entity.PoiRelation;
import com.hanyou.brain.mapper.CityProfileMapper;
import com.hanyou.brain.mapper.PoiMapper;
import com.hanyou.brain.mapper.PoiRelationMapper;
import com.hanyou.brain.service.PoiService;
import com.hanyou.brain.vo.CityMetaVO;
import com.hanyou.brain.vo.PoiDetailVO;
import com.hanyou.brain.vo.PoiVO;
import com.hanyou.brain.vo.RelationVO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PoiServiceImpl implements PoiService {

    private final PoiMapper poiMapper;
    private final PoiRelationMapper poiRelationMapper;
    private final CityProfileMapper cityProfileMapper;
    private final HanYouProperties props;

    @Override
    public List<PoiVO> listPois(String businessType, String district, String keyword) {
        LambdaQueryWrapper<Poi> q = new LambdaQueryWrapper<Poi>()
                .eq(Poi::getCityCode, props.getCity())
                .eq(Poi::getStatus, 1)
                .eq(StringUtils.hasText(businessType), Poi::getBusinessType, businessType)
                .eq(StringUtils.hasText(district), Poi::getDistrict, district)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Poi::getName, keyword)
                        .or().like(Poi::getSummary, keyword)
                        .or().like(Poi::getTags, keyword))
                // 业态 + id 排序：同一业态的资源聚在一起，且顺序稳定（避免每次刷新列表跳动）
                .orderByAsc(Poi::getBusinessType)
                .orderByAsc(Poi::getId);

        return poiMapper.selectList(q).stream().map(PoiServiceImpl::toVO).collect(Collectors.toList());
    }

    @Override
    public PoiDetailVO getPoiDetail(String id) {
        Poi poi = poiMapper.selectById(id);
        if (poi == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不存在：" + id);
        }

        List<PoiRelation> relations = poiRelationMapper.selectList(
                new LambdaQueryWrapper<PoiRelation>()
                        .eq(PoiRelation::getFromPoiId, id)
                        .orderByAsc(PoiRelation::getDistanceKm));

        // 关系目标一次性查出来，不在循环里逐个 selectById（N+1）
        Set<String> targetIds = relations.stream()
                .map(PoiRelation::getToPoiId)
                .collect(Collectors.toSet());
        Map<String, Poi> targetMap = targetIds.isEmpty()
                ? Collections.emptyMap()
                : poiMapper.selectBatchIds(targetIds).stream()
                        .collect(Collectors.toMap(Poi::getId, p -> p));

        Map<String, List<RelationVO>> grouped = new HashMap<>();
        for (PoiRelation r : relations) {
            Poi target = targetMap.get(r.getToPoiId());
            // 目标被下架或删除时跳过。关系表里可能留着旧引用，
            // 不能让一条悬空引用把整个详情页打挂。
            if (target == null) {
                continue;
            }
            grouped.computeIfAbsent(r.getRelationType(), k -> new ArrayList<>()).add(toRelationVO(r, target));
        }

        PoiDetailVO vo = new PoiDetailVO();
        vo.setPoi(toVO(poi));
        vo.setNearby(grouped.getOrDefault(RelationType.NEARBY, List.of()));
        vo.setSupport(grouped.getOrDefault(RelationType.SUPPORT, List.of()));
        vo.setSameVillage(grouped.getOrDefault(RelationType.SAME_VILLAGE, List.of()));
        vo.setDiversion(grouped.getOrDefault(RelationType.DIVERSION, List.of()));
        return vo;
    }

    @Override
    public CityMetaVO getCityMeta() {
        CityProfile p = cityProfileMapper.selectById(props.getCity());
        if (p == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "城市档案不存在：" + props.getCity());
        }
        CityMetaVO v = new CityMetaVO();
        v.setCityCode(p.getCityCode());
        v.setName(p.getName());
        v.setProvince(p.getProvince());

        CityMetaVO.Center center = new CityMetaVO.Center();
        center.setLng(VoUtils.toDouble(p.getCenterLng()));
        center.setLat(VoUtils.toDouble(p.getCenterLat()));
        v.setCenter(center);

        v.setTagline(p.getTagline());
        v.setSummary(p.getSummary());
        v.setDataOrigin(p.getDataOrigin());
        v.setDisclaimer(p.getDisclaimer());
        v.setVersion(p.getVersion());
        return v;
    }

    // ------------------------------------------------------------------
    // 转换
    // ------------------------------------------------------------------

    private static PoiVO toVO(Poi p) {
        PoiVO v = new PoiVO();
        v.setId(p.getId());
        v.setName(p.getName());
        v.setBusinessType(p.getBusinessType());
        v.setDistrict(p.getDistrict());
        v.setLevel(p.getLevel());
        v.setLng(VoUtils.toDouble(p.getLng()));
        v.setLat(VoUtils.toDouble(p.getLat()));
        v.setTicketPrice(VoUtils.toDouble(p.getTicketPrice()));
        v.setOpenHours(p.getOpenHours());
        v.setDurationMin(p.getDurationMin());
        v.setCapacity(p.getCapacity());
        v.setTags(VoUtils.splitTags(p.getTags()));
        v.setSummary(p.getSummary());
        v.setScene(p.getScene());
        v.setDataOrigin(p.getDataOrigin());
        v.setSourceUrl(p.getSourceUrl());
        return v;
    }

    private static RelationVO toRelationVO(PoiRelation r, Poi target) {
        RelationVO v = new RelationVO();
        v.setId(target.getId());
        v.setName(target.getName());
        v.setBusinessType(target.getBusinessType());
        v.setDistrict(target.getDistrict());
        v.setScene(target.getScene());
        v.setDistanceKm(VoUtils.toDouble(r.getDistanceKm()));
        v.setTravelMin(r.getTravelMin());
        v.setWeight(VoUtils.toDouble(r.getWeight()));
        return v;
    }

    // splitTags / toDouble 已提到 VoUtils：M2 的体验与产品 Service 要用同一套转换规则，
    // 三个接口对同一种数据库类型的输出口径必须一致，否则前端要写三遍兼容代码。
}
