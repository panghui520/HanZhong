package com.hanyou.brain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 风险事件对外视图。
 *
 * <p>比 {@link com.hanyou.brain.entity.RiskEvent} 多一个 {@code type} 字段：
 * 类型（OVERLOAD / RURAL_IDLE / …）存在 {@code risk_rule} 表上而不是事件表上，
 * 因为它是规则的属性、不是某一次命中的属性。界面上要用它给事件打不同颜色的标签，
 * 所以在 VO 里补出来，前端不用再拿着 rule_id 去查一次规则表。
 *
 * <p>{@code metricValue} 与 {@code threshold} 都给出来，是为了让界面能显示
 * "实际 110.5% / 阈值 100%"这种可核对的句子 —— 只说"超载了"的预警，
 * 运营没法判断该不该立刻处理。
 *
 * <p><b>{@code candidates}（M5 续）是实时算的，不落 {@code risk_event} 表。</b>
 * 事件上的 {@code suggestion} 是规则内置的**模板**文案（"推送周边乡村替代方案"），
 * 一个具体点位名都没有；候选列表补上这一块，运营打开事件就能看到
 * "具体分流到哪几个点、为什么是它们"。
 *
 * <p>为什么**不把候选存进事件表**：候选依赖"当日承载"，而承载每天都在变。
 * 存快照会让运营看到一个几小时前的方案（甚至跨天的），
 * 而正确的行为是"你现在打开，就按现在的承载给你算"。
 * 真正需要留痕的是**发布出去的那一份**，那份存在
 * {@code diversion_notice.candidates_json} 里 —— 公告发过就要说得清当时为什么推荐它。
 * 顺带还避免了给一张已验收的表加列。
 */
@Data
public class RiskEventVO {

    private Long id;
    private String ruleId;

    /** 事件类型，取自规则的 type */
    private String type;

    private String level;
    private String title;

    private String poiId;
    private String poiName;
    private String district;

    private LocalDate statDate;

    private BigDecimal metricValue;
    private BigDecimal threshold;

    private String detail;
    private String suggestion;

    private String status;
    private Long workOrderId;

    /**
     * 分流候选（实时计算）。**只有规则 1 / 规则 4 的事件才有**：
     * 这两条规则的处置动作是"分流"（{@code risk_rule.action_type = DIVERSION}），
     * 其余规则是曝光 / 服务 / 关注，没有"换一个去处"的语义。
     * 为空列表表示"这条事件不涉及分流"或"周边暂无可承接的点"。
     */
    private List<DiversionCandidateVO> candidates;

    private LocalDateTime createdAt;
}
