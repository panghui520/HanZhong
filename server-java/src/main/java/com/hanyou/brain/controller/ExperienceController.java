package com.hanyou.brain.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.ExperienceService;
import com.hanyou.brain.vo.ExperienceVO;

import lombok.RequiredArgsConstructor;

/** M2 乡村体验接口 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ExperienceController {

    private final ExperienceService experienceService;

    /**
     * 体验列表。
     *
     * <p>两个参数都可选：详情页传 poi_id 只看这个村能做什么，
     * 探索页与 M4 行程规划按 type 挑体验（比如"想安排一次采茶"）。
     * 参数名用 snake_case，与 JSON 字段风格一致。
     */
    @GetMapping("/experiences")
    public Result<List<ExperienceVO>> list(
            @RequestParam(name = "poi_id", required = false) String poiId,
            @RequestParam(name = "type", required = false) String type) {
        return Result.ok(experienceService.listExperiences(poiId, type));
    }

    /** 体验详情。M4 生成行程时按 id 取单条 */
    @GetMapping("/experiences/{id}")
    public Result<ExperienceVO> detail(@PathVariable String id) {
        return Result.ok(experienceService.getExperience(id));
    }
}
