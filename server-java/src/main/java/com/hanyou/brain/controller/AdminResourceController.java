package com.hanyou.brain.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.AdminResourceService;
import com.hanyou.brain.vo.AdminPoiVO;
import com.hanyou.brain.vo.AdminProductVO;

import lombok.RequiredArgsConstructor;

/**
 * 资源管理（M10，运营端）。
 *
 * <p>挂在 {@code /api/admin/**} 下，权限由 {@code SecurityConfig} 里那条
 * {@code ADMIN_PREFIX -> hasRole("OPERATOR")} 统一兜住，本类不再逐个方法标注 ——
 * 与 M9 的 MediaAdminController、M6 的 OrderAdminController 同一约定。
 *
 * <p><b>为什么分成 /pois 与 /products 两组，而不是一个 /{type}：</b>
 * 两者的字段几乎没有交集（poi 有坐标、承载量、等级；product 有规格、库存、
 * 产地村、体验锚点），合并成一个端点的话，请求体的合法字段要靠 {@code type}
 * 才能确定，校验逻辑会长成一片 if-else，而返回值也只能是 {@code List<?>}。
 * 分成两组之后，"景点/美食"共用一套（它们本来就是同一张表），
 * "农产品"自成一套，各自的类型都是确定的。
 *
 * <p>景点的两个业态（景区 / 乡村旅游）用查询参数 {@code type} 区分，
 * 而不是各开一个端点：它们字段完全一样，差别只在一个过滤条件。
 *
 * <p>请求体一律用 {@code Map<String, Object>} 接，理由见 {@code BodyReader} 的类注释。
 */
@RestController
@RequestMapping("/api/admin/resources")
@RequiredArgsConstructor
public class AdminResourceController {

    private final AdminResourceService resourceService;

    // ============================================================
    // 景点 / 美食
    // ============================================================

    /**
     * 列表。
     *
     * @param type    {@code scenic} 景点（含乡村旅游）/ {@code food} 美食
     * @param keyword 名称、简介、标签、区县、编码，任一项命中
     * @param status  {@code 1} 上架 / {@code 0} 下架 / 不传 = 全部
     */
    @GetMapping("/pois")
    public Result<List<AdminPoiVO>> listPois(
            @RequestParam(name = "type") String type,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status) {
        return Result.ok(resourceService.listPois(type, keyword, parseStatus(status)));
    }

    /** 新增。{@code id} 由服务端生成，请求体里带 id 也不会被采纳 */
    @PostMapping("/pois")
    public Result<AdminPoiVO> createPoi(
            @RequestParam(name = "type") String type,
            @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(resourceService.createPoi(type, body == null ? Map.of() : body));
    }

    /** 编辑。只改请求体里出现的字段 */
    @PatchMapping("/pois/{id}")
    public Result<AdminPoiVO> updatePoi(
            @PathVariable String id,
            @RequestParam(name = "type") String type,
            @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(resourceService.updatePoi(type, id, body == null ? Map.of() : body));
    }

    /** 上架 / 下架。请求体 {@code {"status": 0}} */
    @PatchMapping("/pois/{id}/status")
    public Result<AdminPoiVO> updatePoiStatus(
            @PathVariable String id,
            @RequestParam(name = "type") String type,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return Result.ok(resourceService.updatePoiStatus(type, id, parseStatus(
                b.get("status") == null ? null : String.valueOf(b.get("status")))));
    }

    /** 删除。数据包带来的、以及被任何业务数据引用的，都会拒绝并说明原因 */
    @DeleteMapping("/pois/{id}")
    public Result<Map<String, Object>> deletePoi(
            @PathVariable String id,
            @RequestParam(name = "type") String type) {
        resourceService.deletePoi(type, id);
        return Result.ok(Map.of("deleted", true));
    }

    // ============================================================
    // 农产品
    // ============================================================

    @GetMapping("/products")
    public Result<List<AdminProductVO>> listProducts(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status) {
        return Result.ok(resourceService.listProducts(keyword, parseStatus(status)));
    }

    @PostMapping("/products")
    public Result<AdminProductVO> createProduct(
            @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(resourceService.createProduct(body == null ? Map.of() : body));
    }

    @PatchMapping("/products/{id}")
    public Result<AdminProductVO> updateProduct(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(resourceService.updateProduct(id, body == null ? Map.of() : body));
    }

    @PatchMapping("/products/{id}/status")
    public Result<AdminProductVO> updateProductStatus(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return Result.ok(resourceService.updateProductStatus(id, parseStatus(
                b.get("status") == null ? null : String.valueOf(b.get("status")))));
    }

    @DeleteMapping("/products/{id}")
    public Result<Map<String, Object>> deleteProduct(@PathVariable String id) {
        resourceService.deleteProduct(id);
        return Result.ok(Map.of("deleted", true));
    }

    /**
     * 解析上架状态。
     *
     * <p>用 String 接而不是直接声明成 Integer：前端把筛选框的"全部"选项
     * 序列化成空串（{@code ?status=}）是很容易发生的事，而 Spring 在
     * 空串转 Integer 时会抛 {@code MethodArgumentTypeMismatchException}，
     * 落到兜底报 9000「服务内部错误」—— 把一个"没选状态"说成了服务端故障。
     * 这里把空串当成"不筛选"，非数字才报参数错。
     */
    private static Integer parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.RESOURCE_STATUS_INVALID);
        }
    }
}
