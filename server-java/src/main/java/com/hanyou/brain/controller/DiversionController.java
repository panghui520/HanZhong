package com.hanyou.brain.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.DiversionService;
import com.hanyou.brain.vo.DiversionNoticeVO;

import lombok.RequiredArgsConstructor;

/**
 * 游客端：分流公告（M5 续）。
 *
 * <p><b>这是本项目里第一个"系统主动对游客说话"的端点。</b>在此之前游客端
 * 全是"你点哪里我给你看哪里"；分流公告是"我们发现某个点挤了，主动告诉你
 * 可以换个去处"。所以它的安全边界要单独说清楚：
 *
 * <ul>
 *   <li><b>只有 GET。</b>发布、编辑、撤下全在 {@code /api/admin/diversion-notices}
 *       下，由 {@code ADMIN_PREFIX} 那条规则兜住。这里若不限定方法，
 *       等于把"发布公告"的通道开给了游客。</li>
 *   <li><b>只返回已发布且未过期的。</b>过滤写在 SQL 里
 *       （见 {@link DiversionService#listPublished()}），
 *       不是查出来再在 Java 里筛 —— 少一次"有人改了查询忘了加条件"的机会。</li>
 *   <li><b>候选带上当前承载。</b>公告发布后承载还会变，游客打开时要看到
 *       此刻的真实情况（快照与当前两组数都给，见 {@code DiversionCandidateVO}）。</li>
 * </ul>
 *
 * <p>返回的是数组而不是单条：同一时刻可能有两个景区都在高位，
 * 首页要能把两条都列出来。前端决定显示几条（当前是全部，通常 0–2 条）。
 */
@RestController
@RequestMapping("/api/diversion-notices")
@RequiredArgsConstructor
public class DiversionController {

    private final DiversionService diversionService;

    /** 当前生效的分流公告。未登录可访问；没有生效公告时返回空数组 */
    @GetMapping
    public Result<List<DiversionNoticeVO>> list() {
        return Result.ok(diversionService.listPublished());
    }
}
