package com.hanyou.brain.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hanyou.brain.common.BodyReader;
import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.DiversionService;
import com.hanyou.brain.vo.DiversionNoticeVO;

import lombok.RequiredArgsConstructor;

/**
 * 运营端：分流公告的下发（M5 续）。
 *
 * <p>三个动作，构成"从算出来到发出去"的完整人工环节：
 * <ol>
 *   <li>{@code POST /risks/{id}/notice} —— 从风险事件生成**草稿**。
 *       候选在这一步就算好存进快照，所以"当时为什么推荐它"永远可回看。</li>
 *   <li>{@code PATCH /notices/{id}} —— 改文案、发布、撤下。
 *       运营可以改文案，因为"对外说什么话"是运营的职责，不该由规则引擎定死；
 *       但**候选不能改** —— 那是算出来的，改了就说不清依据。</li>
 *   <li>{@code GET /notices} —— 四态全给（草稿也要看得到，否则草稿一存就找不到了）。</li>
 * </ol>
 *
 * <p><b>为什么必须有"人工发布"这一步，不做成全自动：</b>公告是发给游客看的，
 * 而承载率来自仿真数据。让系统自动对游客喊话，出错时没人拦得住 ——
 * 首页上一条没有依据的"建议改往 XX"，比不提示更糟。
 * 人在环里，代价只是运营点一下，收益是"发出去的话有人负责"。
 *
 * <p>路径挂在 {@code /api/admin/**} 下，自动继承 {@code hasRole("OPERATOR")}
 * （见 {@code SecurityConfig.ADMIN_PREFIX}），不用逐个接口标注权限。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class DiversionAdminController {

    private final DiversionService diversionService;

    /**
     * 从风险事件生成公告草稿。
     *
     * <p>{@code title} 与 {@code message} 都可以不传 —— 不传则由候选自动生成
     * （正文里会带"演示用仿真数据"这句，见 {@code DiversionServiceImpl}）。
     */
    @PostMapping("/risks/{id}/notice")
    public Result<DiversionNoticeVO> createDraft(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return Result.ok(diversionService.createDraft(id,
                BodyReader.str(b, "title"), BodyReader.str(b, "message")));
    }

    /** 公告列表（四态全给，按 id 倒序） */
    @GetMapping("/diversion-notices")
    public Result<List<DiversionNoticeVO>> notices(
            @RequestParam(name = "status", required = false) String status) {
        return Result.ok(diversionService.listNotices(status));
    }

    /**
     * 改文案 / 发布 / 撤下。
     *
     * <p>请求体用 Map 接而不是 String，理由见 {@link BodyReader} 类注释：
     * 声明成 String 时客户端只要传个对象就整段解析失败。
     */
    @PatchMapping("/diversion-notices/{id}")
    public Result<DiversionNoticeVO> updateNotice(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return Result.ok(diversionService.updateNotice(id,
                BodyReader.str(b, "title"), BodyReader.str(b, "message"),
                BodyReader.str(b, "status"), BodyReader.str(b, "published_by")));
    }
}
