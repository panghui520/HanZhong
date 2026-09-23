package com.hanyou.brain.service.impl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.VoUtils;
import com.hanyou.brain.config.HanYouProperties;
import com.hanyou.brain.entity.Experience;
import com.hanyou.brain.mapper.ExperienceMapper;
import com.hanyou.brain.service.ExperienceService;
import com.hanyou.brain.service.support.NameResolver;
import com.hanyou.brain.vo.ExperienceVO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ExperienceServiceImpl implements ExperienceService {

    private final ExperienceMapper experienceMapper;
    private final NameResolver nameResolver;
    private final HanYouProperties props;

    @Override
    public List<ExperienceVO> listExperiences(String poiId, String type) {
        LambdaQueryWrapper<Experience> q = new LambdaQueryWrapper<Experience>()
                .eq(Experience::getCityCode, props.getCity())
                .eq(Experience::getStatus, 1)
                .eq(StringUtils.hasText(poiId), Experience::getPoiId, poiId)
                .eq(StringUtils.hasText(type), Experience::getType, type)
                // 编码排序而不是按名称：E-001 这种编码天然稳定，
                // 按名称排会在改一次体验名之后让列表顺序跳动
                .orderByAsc(Experience::getId);

        return toVOs(experienceMapper.selectList(q));
    }

    @Override
    public ExperienceVO getExperience(String id) {
        Experience e = experienceMapper.selectById(id);
        if (e == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "体验不存在：" + id);
        }
        // 单个也走批量路径：poiName 的补全规则只有一处，
        // 不会出现"列表有所属乡村、详情没有"这种不一致
        return toVOs(List.of(e)).get(0);
    }

    /**
     * 批量转换。
     *
     * <p>所属乡村名一次查出来再分发，不在每条体验上查一次。
     */
    private List<ExperienceVO> toVOs(List<Experience> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<String, String> poiNames = nameResolver.poiNames(
                list.stream().map(Experience::getPoiId).collect(Collectors.toSet()));

        return list.stream().map(e -> {
            ExperienceVO v = new ExperienceVO();
            v.setId(e.getId());
            v.setPoiId(e.getPoiId());
            v.setPoiName(poiNames.get(e.getPoiId()));
            v.setName(e.getName());
            v.setType(e.getType());
            v.setDurationMin(e.getDurationMin());
            v.setPrice(VoUtils.toDouble(e.getPrice()));
            v.setSeason(e.getSeason());
            v.setCapacity(e.getCapacity());
            v.setTags(VoUtils.splitTags(e.getTags()));
            // 实体字段是 description（列名不能用保留字 desc），对外仍是 desc
            v.setDesc(e.getDescription());
            v.setDataOrigin(e.getDataOrigin());
            v.setSourceUrl(e.getSourceUrl());
            return v;
        }).collect(Collectors.toList());
    }
}
