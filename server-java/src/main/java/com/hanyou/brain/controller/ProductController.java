package com.hanyou.brain.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.ProductService;
import com.hanyou.brain.vo.ProductCategoryVO;
import com.hanyou.brain.vo.ProductVO;

import lombok.RequiredArgsConstructor;

/**
 * M2 乡村农产品接口。
 *
 * <p>这里刻意没有 /api/pois/{id}/products 这样的嵌套路径：它和
 * /api/products?poi_id=xxx 是同一份数据的两条入口，两处都要维护排序、
 * 过滤与名称补全，迟早会漂移。列表只有一个，靠查询参数区分场景。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 产品列表。
     *
     * <p>四个参数可任意组合。详情页用 poi_id，体验区块用 experience_id，
     * M4 行程规划按 category_code 挑"可以带走的伴手礼"，M7 运营检索用 keyword。
     */
    @GetMapping("/products")
    public Result<List<ProductVO>> list(
            @RequestParam(name = "poi_id", required = false) String poiId,
            @RequestParam(name = "experience_id", required = false) String experienceId,
            @RequestParam(name = "category_code", required = false) String categoryCode,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return Result.ok(productService.listProducts(poiId, experienceId, categoryCode, keyword));
    }

    /** 产品详情。M6 离境复购按 id 下单时要用 */
    @GetMapping("/products/{id}")
    public Result<ProductVO> detail(@PathVariable String id) {
        return Result.ok(productService.getProduct(id));
    }

    /** 分类列表（含各分类在售产品数）。首页筛选条用 */
    @GetMapping("/product-categories")
    public Result<List<ProductCategoryVO>> categories() {
        return Result.ok(productService.listCategories());
    }
}
