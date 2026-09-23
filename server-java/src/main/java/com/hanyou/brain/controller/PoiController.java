package com.hanyou.brain.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.PoiService;
import com.hanyou.brain.vo.CityMetaVO;
import com.hanyou.brain.vo.PoiDetailVO;
import com.hanyou.brain.vo.PoiVO;

import lombok.RequiredArgsConstructor;

/** M1 统一资源接口。前端 /api 前缀由 Vite 代理转发到这里 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PoiController {

    private final PoiService poiService;

    /**
     * 资源列表。
     *
     * <p>三个查询参数目前前端没用到——42 条数据在浏览器里过滤比发请求更跟手。
     * 接口层先支持，是因为 M4 行程规划要按业态挑点、M5 运营分析要按区县聚合，
     * 那时不该再回头改接口。参数名用 snake_case，与 JSON 字段风格一致。
     */
    @GetMapping("/pois")
    public Result<List<PoiVO>> list(
            @RequestParam(name = "business_type", required = false) String businessType,
            @RequestParam(name = "district", required = false) String district,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return Result.ok(poiService.listPois(businessType, district, keyword));
    }

    /** 资源详情：含 nearby / support / sameVillage / diversion 四组关系 */
    @GetMapping("/pois/{id}")
    public Result<PoiDetailVO> detail(@PathVariable String id) {
        return Result.ok(poiService.getPoiDetail(id));
    }

    /** 城市档案：前端的来源声明与仿真数据免责说明取自这里 */
    @GetMapping("/city")
    public Result<CityMetaVO> city() {
        return Result.ok(poiService.getCityMeta());
    }
}
