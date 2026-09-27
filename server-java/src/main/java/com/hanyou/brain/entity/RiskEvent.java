package com.hanyou.brain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 风险事件。规则引擎一次扫描命中一条就在这里落一行。
 *
 * <p><b>幂等由唯一键保证</b>：{@code uk_rule_poi_date} 是
 * (rule_id, poi_id, stat_date)。规则引擎可以被反复触发（启动时一次、
 * 运营手动一次、以后可能加定时），没有这个唯一键就会把同一条风险
 * 重复落库，界面上出现 5 条一模一样的"兴汉胜境超载"。有了它，
 * 重复扫描是安全的——引擎只刷新指标值，不新增行。
 *
 * <p>{@code poiName} 是快照不是冗余：资源改名或下架后，历史事件仍要
 * 说得清当时说的是谁。与 M6 把挂靠关系快照到订单行上同一个理由。
 *
 * <p>{@code threshold} 也是快照：阈值后来被调过，历史事件仍说得清
 * 当时的判据是什么。
 *
 * <p><b>不带 city_code</b>：运行期产生的事实，重启不能丢。
 */
@Data
@TableName("risk_event")
public class RiskEvent {

    /** 事件状态：刚命中，还没处置 */
    public static final String STATUS_OPEN = "OPEN";
    /** 已转工单 */
    public static final String STATUS_HANDLED = "HANDLED";
    /** 工单已完结，事件闭环 */
    public static final String STATUS_CLOSED = "CLOSED";

    public static final String LEVEL_HIGH = "HIGH";
    public static final String LEVEL_MID = "MID";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ruleId;
    private String poiId;

    /** 资源点名称快照 */
    private String poiName;

    /** 区县。规则 4 的事件按区县聚合，列表要能按区县筛 */
    private String district;

    private LocalDate statDate;

    private String level;

    /** 实际指标值（如承载率 1.1050） */
    private BigDecimal metricValue;

    /** 命中时的阈值快照 */
    private BigDecimal threshold;

    private String title;
    private String detail;
    private String suggestion;

    private String status;

    /** 已生成的工单，未建单时为空 */
    private Long workOrderId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
