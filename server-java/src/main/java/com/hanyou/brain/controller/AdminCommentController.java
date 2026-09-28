package com.hanyou.brain.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.common.BodyReader;
import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.PoiCommentService;
import com.hanyou.brain.vo.AdminCommentVO;

import lombok.RequiredArgsConstructor;

/**
 * 景点评论管理（运营端，M10 续）。
 *
 * <p>挂在 {@code /api/admin/**} 下，权限由 {@code SecurityConfig} 里那条
 * {@code ADMIN_PREFIX -> hasRole("OPERATOR")} 统一兜住，本类不再逐个方法标注 ——
 * 与 M9 的 MediaAdminController、M10 的 AdminResourceController 同一约定。
 *
 * <p><b>为什么把评论管理单独开一个 Controller，而不是塞进
 * AdminResourceController：</b>资源管理的路径是 {@code /resources/**}，
 * 而评论不属于"资源"这个名词（它是游客写的内容）。路径上分开之后，
 * 前端 {@code api/} 目录下也是两个文件，各自的职责边界与 URL 一致。
 *
 * <p>三个动作对应运营对一条评论能做的三件事：改状态（通过 / 隐藏）、
 * 删除。**没有"编辑评论内容"** —— 运营改游客写的话，那就不叫审核了。
 */
@RestController
@RequestMapping("/api/admin/comments")
@RequiredArgsConstructor
public class AdminCommentController {

    private final PoiCommentService commentService;

    /**
     * 评论列表。
     *
     * @param poiId  按景点筛，不传 = 全部
     * @param status 按状态筛（PENDING / APPROVED / HIDDEN），不传 = 全部
     */
    @GetMapping
    public Result<List<AdminCommentVO>> list(
            @RequestParam(name = "poi_id", required = false) String poiId,
            @RequestParam(name = "status", required = false) String status) {
        return Result.ok(commentService.listForAdmin(poiId, status));
    }

    /**
     * 改状态。请求体 {@code {"status": "HIDDEN"}}。
     *
     * <p>用 PATCH 而不是 PUT：只改状态这一列，其余字段原样不动。
     * 与 M10 的 {@code PATCH /pois/{id}/status} 是同一个约定。
     */
    @PatchMapping("/{id}")
    public Result<AdminCommentVO> updateStatus(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return Result.ok(commentService.updateStatus(id, BodyReader.str(b, "status")));
    }

    /** 物理删除。与"隐藏"是两回事：隐藏可恢复，删除不可 */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Long id) {
        commentService.delete(id);
        return Result.ok(Map.of("deleted", true));
    }
}
