package com.hanyou.brain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 风险规则配置。
 *
 * <p><b>规则逻辑不在这张表里</b>——逻辑在 {@code RuleEngine} 里，因为
 * "比值、时间窗、分组比较"不是一句 SQL 能表达的。本表负责的是
 * <b>可调的那部分：阈值与开关</b>。阈值是运营口径，写死在 Java 常量里
 * 会导致每次调阈值都要改代码重新部署。
 *
 * <p>四个阈值列覆盖 6 条规则，用不到的留空，对照见
 * {@code db/V8__m5_ops.sql} 文件头。
 *
 * <p><b>不带 city_code</b>：它是系统级配置，不能随数据包重灌而丢掉
 * 运营改过的阈值。
 */
@Data
@TableName("risk_rule")
public class RiskRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 稳定编码，与 RuleEngine 里的常量一一对应 */
    private String ruleId;

    private String name;
    private String type;

    /** 主阈值，含义随规则变 */
    private BigDecimal threshold;

    /**
     * 次阈值。只有 OVERLOAD（一级档）与 RURAL_IDLE（乡村上限）用到。
     *
     * <p><b>为什么必须显式写 {@code @TableField}：</b>MyBatis-Plus 的
     * 驼峰转下划线是在**大写字母**前插下划线，字段名 {@code threshold2}
     * 里没有大写字母，于是被直译成列名 {@code threshold2} —— 而表里是
     * {@code threshold_2}。启动扫描时直接报
     * {@code Unknown column 'threshold2' in 'field list'}。
     * 数字结尾的字段名都有这个坑（{@code threshold2} / {@code level2} 之类），
     * 项目里另一个同源例子是 {@code Poi.level} 的保留字转义。
     *
     * <p>没有把列名改成 {@code threshold2} 来迁就它：表里其余列全是
     * snake_case（{@code rule_id} / {@code window_days} / {@code min_sample}），
     * 为一个字段破例会让后面看表的人以为命名不统一是随意的。
     */
    @TableField("threshold_2")
    private BigDecimal threshold2;

    /** 统计窗口（天）。OVERLOAD / RURAL_IDLE 判"当日"，留空 */
    private Integer windowDays;

    /** 最小样本量。只有 REVIEW_SURGE 用到 */
    private Integer minSample;

    private String actionType;
    private String level;
    private Boolean enabled;
    private Integer sort;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
