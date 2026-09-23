package com.hanyou.brain.service;

import java.util.List;

import com.hanyou.brain.vo.ExperienceVO;

/** M2 乡村体验。体验是整条消费链的锚点，产品挂在它上面 */
public interface ExperienceService {

    /**
     * 体验列表。
     *
     * @param poiId 只看某个乡村点的体验（详情页用），可为空
     * @param type  按类型过滤：TEA / PICKING / FOLK / HOMESTAY / FOOD_MAKING / NATURE，可为空
     */
    List<ExperienceVO> listExperiences(String poiId, String type);

    /** 体验详情。不存在时抛 1001 */
    ExperienceVO getExperience(String id);
}
