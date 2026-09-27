package com.hanyou.brain.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 工单对外视图。
 *
 * <p>比实体多两个"来源"字段（{@code riskRuleId} / {@code riskPoiName}）：
 * 工单列表要显示"这张单是从哪条风险来的"，而工单表上只存了
 * {@code risk_event_id}。让前端拿着 id 再查一次事件是最省事的做法，
 * 但列表页有 N 张单就是 N 次请求；在后端一次补齐更合理，
 * 与 M2 的 {@code ProductVO.experience_name} 同一个处理方式。
 */
@Data
public class WorkOrderVO {

    private Long id;
    private String code;

    private Long riskEventId;
    /** 来源风险的规则编码，可能为空（事件被清理时） */
    private String riskRuleId;
    /** 来源风险的资源点名称，可能为空 */
    private String riskPoiName;
    /** 来源风险的判定日期 */
    private LocalDate riskStatDate;

    private String title;
    private String type;
    private String level;
    private String status;
    private String assignee;
    private String suggestion;
    private String result;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;
}
