package com.hanyou.brain.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanyou.brain.common.BodyReader;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.Result;
import com.hanyou.brain.service.DiversionService;
import com.hanyou.brain.service.OpsService;
import com.hanyou.brain.service.support.AiClient;
import com.hanyou.brain.vo.OpsSnapshotVO;
import com.hanyou.brain.vo.RiskEventVO;
import com.hanyou.brain.vo.WorkOrderVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 运营端：承载力、风险事件与工单（M5）。
 *
 * <p><b>路径为什么挂在 {@code /api/admin/**} 下而不是方案里写的 {@code /api/ops/**}：</b>
 * {@code SecurityConfig.ADMIN_PREFIX} 这一条规则把整个 {@code /api/admin/**}
 * 统一交给 {@code hasRole("OPERATOR")}，新增运营接口不用逐个标注权限。
 * 方案第 16 节写的是 {@code /api/ops/snapshot} 这类路径，那是设计阶段的
 * 草案；实施时以 M8 定下的前缀约定为准（模块文档的"扩展点"一节也是这么写的）。
 * 挂在统一前缀下的直接好处是：**漏配权限的后果是"访问不了"，
 * 而不是"游客也能看到运营数据"**。
 *
 * <p>写操作只有两处：手动触发一次规则扫描、建单与处置。规则扫描做成
 * 手动接口而不是只靠启动时跑一次，是因为演示与排查都需要"我改了阈值，
 * 现在就想看到结果"，而不是重启服务。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class OpsAdminController {

    /**
     * 允许的解读维度。**必须与 {@code server-ai/app/ops_analysis.py} 的 FOCUSES 一致。**
     *
     * <p>两层都校验不是冗余：Java 这一层是**对外契约**（前端能点哪几个按钮），
     * Python 那一层是**能力边界**（它认识哪几个关注点）。两边不一致时各自都
     * 明确报错，而不是悄悄回落到总览 —— 见 {@link ErrorCode#AI_FOCUS_INVALID}。
     */
    private static final Set<String> FOCUS_KEYS =
            Set.of("overview", "trend", "mix", "imbalance", "sales", "risks");

    private final OpsService opsService;
    private final DiversionService diversionService;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    /** 运营快照：大屏的 6 组图表。全部为仿真数据，见 OpsService 的口径说明 */
    @GetMapping("/ops/snapshot")
    public Result<OpsSnapshotVO> snapshot() {
        return Result.ok(opsService.snapshot());
    }

    /**
     * 手动跑一次规则扫描。
     *
     * <p>幂等：同一条 (规则, 资源点, 日期) 只会有一条事件，重复触发只刷新指标。
     * 返回本次命中的事件数，便于"改完阈值立刻验证有没有按预期触发"。
     *
     * <p>顺手清理过期的分流公告 —— 与启动时那次扫描同一个节拍。
     * 放在这里而不是塞进 {@code OpsService.scan()}，是因为那会让
     * {@code OpsService} 依赖 {@code DiversionService}，而后者已经依赖前者
     * （公告的候选就是问 OpsService 要的）—— 那是个循环依赖。
     * 由这一层把两件事串起来，依赖方向保持单向。
     */
    @PostMapping("/ops/scan")
    public Result<Integer> scan() {
        int hit = opsService.scan();
        diversionService.expireOverdue();
        return Result.ok(hit);
    }

    /**
     * AI 运营解读（M7）：把快照里的一组指标讲成三段式
     * （发生了什么 / 为什么 / 建议做什么）。
     *
     * <p><b>路径为什么不是方案里写的 {@code /api/ai/analyze/ops}：</b>
     * {@code /api/ai/**} 在 {@code SecurityConfig} 里是整段 permitAll
     * （问答能力对未登录游客开放），而本接口返回的是**运营数据**的解读 ——
     * 三段正文与「依据」里带着销售额、复购率、待处置风险数。
     * 放进 permitAll 的前缀下，等于游客也能读到这些。
     * 挂在 {@code /api/admin/**} 下由 {@code ADMIN_PREFIX} 统一收进 OPERATOR，
     * 与 {@code /ops/snapshot} 落在同一条边界上。与 M5 的路径收敛同一处理。
     *
     * <p><b>指标由本层取，不接受前端传入：</b>Python 侧只负责解读、不做取数。
     * 让前端把指标传进来有两个问题 —— 一是"面板上的数"与"AI 解读的数"
     * 可以不是同一份（前端算错了也没人发现），二是这个端点会变成
     * 一个免费的通用文本生成入口。
     *
     * <p><b>降级：</b>AI 未启用 / 不可用分别返回 3001、3002。而"解读不出来"
     * （模型失败且没有可回放的缓存）**不是错误** —— Python 会返回
     * {@code mode=unavailable} 并附一段说明，前端照常展示即可。
     * 把这种情况报成 500 会让驾驶舱整块变红，而它其实只是这一小块暂时没内容。
     */
    @PostMapping("/ops/analyze")
    public Result<Map<String, Object>> analyzeOps(@RequestBody(required = false) Map<String, Object> body) {
        String focus = BodyReader.str(body == null ? Map.of() : body, "focus");
        if (focus == null || focus.isBlank()) {
            focus = "overview";
        }
        if (!FOCUS_KEYS.contains(focus)) {
            return Result.fail(ErrorCode.AI_FOCUS_INVALID);
        }
        if (!aiClient.isEnabled()) {
            return Result.fail(ErrorCode.AI_DISABLED);
        }

        OpsSnapshotVO snapshot = opsService.snapshot();
        // 转成 Map 而不是把 VO 直接塞进请求体：VO 的字段名（totalVisitors）
        // 与 Python 期望的键（total_visitors）差一个命名策略，而全局 Jackson
        // 已经配了 snake_case —— 走一次 convertValue 就自动对齐，
        // 手写一套 getter 拼接则会随字段增减悄悄漏字段。
        Map<String, Object> metrics = snapshot == null
                ? Map.of()
                : objectMapper.convertValue(snapshot, new TypeReference<Map<String, Object>>() {
                });
        if (metrics.isEmpty()) {
            return Result.fail(ErrorCode.AI_METRICS_UNAVAILABLE);
        }

        String raw = aiClient.postJson("/ai/analyze/ops", Map.of("focus", focus, "metrics", metrics));
        if (raw == null) {
            return Result.fail(ErrorCode.AI_UNAVAILABLE);
        }
        try {
            return Result.ok(objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            }));
        } catch (IOException e) {
            log.warn("[M7] 运营解读响应解析失败：{}", e.getMessage());
            return Result.fail(ErrorCode.AI_UNAVAILABLE);
        }
    }

    /** 风险事件列表 */
    @GetMapping("/risks")
    public Result<List<RiskEventVO>> risks(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "district", required = false) String district) {
        return Result.ok(opsService.listRisks(status, level, district));
    }

    /** 风险事件详情 */
    @GetMapping("/risks/{id}")
    public Result<RiskEventVO> risk(@PathVariable Long id) {
        return Result.ok(opsService.getRisk(id));
    }

    /**
     * 一键建单。同一条事件只能建一张单，重复建单会明确报错。
     *
     * <p>{@code assignee} 与 {@code suggestion} 都可以不传 —— 前者为空表示
     * "待认领"，后者为空则沿用规则自带的建议文案（由 RuleEngine 生成，
     * 比前端拼的模板更贴近该条规则的实际触发原因）。
     */
    @PostMapping("/risks/{id}/work-order")
    public Result<WorkOrderVO> createWorkOrder(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return Result.ok(opsService.createWorkOrder(id,
                BodyReader.str(b, "assignee"), BodyReader.str(b, "suggestion")));
    }

    /** 工单列表 */
    @GetMapping("/work-orders")
    public Result<List<WorkOrderVO>> workOrders(
            @RequestParam(name = "status", required = false) String status) {
        return Result.ok(opsService.listWorkOrders(status));
    }

    /**
     * 处置反馈：改派处置人、置处置中、或填反馈后完结。
     *
     * <p>请求体同样用 Map 接而不是 String，理由见 {@link BodyReader} 类注释：
     * 声明成 String 时客户端只要传个对象就整段解析失败。
     */
    @PatchMapping("/work-orders/{id}")
    public Result<WorkOrderVO> updateWorkOrder(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return Result.ok(opsService.updateWorkOrder(id,
                BodyReader.str(b, "status"), BodyReader.str(b, "assignee"), BodyReader.str(b, "result")));
    }
}
