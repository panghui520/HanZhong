package com.hanyou.brain.controller;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.auth.AuthUser;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.PoiCommentService;
import com.hanyou.brain.vo.CommentListVO;
import com.hanyou.brain.vo.CommentVO;

import lombok.RequiredArgsConstructor;

/**
 * 景点评论（游客端，M10 续）。
 *
 * <p>挂在 {@code /api} 下、路径带 {@code /pois/{id}/comments}，
 * 而不是新开一个 {@code /api/comments}：评论是**从属于某个资源**的，
 * 路径里带上资源 id，读代码时不用回头查"这个 id 是什么"。
 *
 * <p><b>两个方法的权限不同，这不是笔误</b>：
 * <ul>
 *   <li>GET 公开 —— 与 {@code /api/pois/**} 同一条白名单规则。
 *       看评论和看景点是一回事，要求登录会把"这地方怎么样"变成会员信息。</li>
 *   <li>POST 需要登录 —— {@code SecurityConfig} 里 {@code /api/pois/**} 只放行了
 *       GET，POST 落到 {@code anyRequest().authenticated()}。发表要有作者，
 *       匿名评论在这个系统里没有归属。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PoiCommentController {

    private final PoiCommentService commentService;

    /** 某景点的评论列表 + 评分汇总。资源不存在或已下架时返回 1001 */
    @GetMapping("/pois/{id}/comments")
    public Result<CommentListVO> list(@PathVariable String id) {
        return Result.ok(commentService.listForPoi(id));
    }

    /** 发表评论。请求体 {@code {"rating": 5, "content": "..."}} */
    @PostMapping("/pois/{id}/comments")
    public Result<CommentVO> create(
            @PathVariable String id,
            @AuthenticationPrincipal AuthUser me,
            @RequestBody(required = false) Map<String, Object> body) {
        // 理论上到不了这里（SecurityConfig 要求 authenticated），但显式判一次：
        // 若哪天有人把 /api/pois/** 改成不限方法的 permitAll，me 会是 null，
        // 而 NPE 落到兜底就是 9000「服务内部错误」—— 一个权限配置问题
        // 被报成了服务故障，排查方向直接跑偏。
        if (me == null || me.userId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return Result.ok(commentService.create(id, me.userId(), body == null ? Map.of() : body));
    }
}
