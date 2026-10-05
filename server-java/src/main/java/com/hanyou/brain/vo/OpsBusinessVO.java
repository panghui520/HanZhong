package com.hanyou.brain.vo;

import java.util.List;

import lombok.Data;

/**
 * 单个业态的运营分析（管理端四大业务页面用）。
 *
 * <p><b>为什么需要它。</b>{@link OpsSnapshotVO} 是给驾驶舱的"全局总览"——
 * 只有聚合值（核心景区到访、乡村占比、销售额……）和 Top6 乡村销售额。
 * 而四个业务页面需要的是**按业态 × 统计区间**的明细：每个资源点的排行、
 * 逐日趋势、区县分布、承载构成、冷热散点。这些在现有接口里没有：
 * {@code /stats/pois} 是公开端点（游客端读拥挤度）且只有"今日 + 近 7 日"、
 * 不接受 {@code range}。
 *
 * <p><b>它和 snapshot 是同一套算法。</b>{@code OpsServiceImpl.business()}
 * 复用 {@code loadContext} / {@code sumWindow} / {@code sumWindowAll} /
 * {@code avgUsageByPoi} / {@code avgPriceByPoi} —— 与 snapshot 逐字相同的
 * 那几个函数。**不是第二套统计**：同一个窗口、同一份求和、同一个均价口径。
 * 所以"驾驶舱 17.5 万、农产品页 17.5 万"必然相等，对不上就是 bug。
 *
 * <p><b>全部来自仿真数据</b>（{@code poi_visit_stats}，synthetic=1），
 * 与 snapshot 同源。真实订单不混进来。
 *
 * <h3>口径速查（改这个类之前先看）</h3>
 * <ul>
 *   <li><b>客流</b>：{@code poi_visit_stats.visitors}，窗口内合计，单位<b>人次</b></li>
 *   <li><b>承载</b>：{@code capacity_usage} 的窗口<b>平均</b>（不是最后一天的值），0–1，可 &gt;1 表示超载</li>
 *   <li><b>销售</b>：<b>乡村点级</b> = 该产地关联产品均价 × 该产地窗口内购买笔数。
 *       统计表只有"资源点 × 天"，没有逐单流水，所以<b>做不出单品级</b>销量</li>
 *   <li><b>复购率</b>：复购笔数 / 购买笔数。<b>笔数比，不是用户级</b></li>
 *   <li><b>购买 / 复购只有乡村点有值</b>：其余业态在 {@code poi_visit_stats} 里恒为 0</li>
 * </ul>
 */
@Data
public class OpsBusinessVO {

    /** 是否仿真数据。恒为 true，界面上要标出来 */
    private Boolean synthetic;

    /**
     * 业态：{@code scenic} / {@code rural} / {@code food} / {@code lodging} / {@code product}。
     *
     * <p>注意这是**请求侧**的短名，不是 {@code poi.business_type} 的原值：
     * {@code scenic} → {@code SCENIC}、{@code rural} → {@code RURAL_SPOT}。
     * {@code product} 是"农产品"这条业务线（跨多个乡村点聚合），不是 poi 业态。
     */
    private String type;

    /** 统计区间档位，回显给前端（避免"前端以为选了近 7 日、后端按今日算"的静默不一致） */
    private String range;

    /** 统计区间起始（含），{@code yyyy-MM-dd} */
    private String rangeFrom;

    /** 统计区间结束（含），{@code yyyy-MM-dd} */
    private String rangeTo;

    /** 周期文案。由后端给，前端直接显示 —— 前端自己拼会与实际窗口漂移 */
    private String periodLabel;

    /**
     * 顶部 KPI。<b>用有序列表而不是 4 个固定字段</b>：五个业态要的指标不一样
     * （景区要"高负荷数"、农产品要"购买笔数"），固定字段会逼出一堆 null。
     * 列表让前端直接 {@code v-for} 渲染，标签文案也由后端给，两边不会漂移。
     */
    private List<Kpi> kpis;

    /** 排行。景区/餐饮/住宿是"每个资源点"，农产品是"每个产地" */
    private List<Row> top;

    /** 逐日趋势。**点数下限 7**（与 snapshot 同一约定，见 OpsServiceImpl） */
    private List<Point> trend;

    /** 区县分布 */
    private List<Row> districts;

    /**
     * 冷热散点：x = 窗口内到访人次，y = 窗口平均承载率（0–1）。
     * 只有景区 / 乡村两个业态有意义 —— 它是"景区过热、乡村闲置"那张图的原料。
     */
    private List<ScatterPoint> scatter;

    /** 构成分布（承载档 / 产品分类） */
    private List<Row> distribution;

    /** 一个 KPI */
    @Data
    public static class Kpi {
        /** 稳定键，前端用来配色/加图标，不参与展示 */
        private String key;
        /** 展示标签。**带周期前缀**（"近 7 日到访"），由后端拼，跟着 range 走 */
        private String label;
        /** 已经格式化好的展示值（含千分位 / ¥ / %）。前端直接显示，不再加工 */
        private String value;
        private String unit;
    }

    /** 排行 / 分布的一行 */
    @Data
    public static class Row {
        private String id;
        private String name;
        /** 次级说明（区县 / 分类 / 档位），可为空 */
        private String sub;
        /** 数值。语义随上下文：人次 / 元 / 个 */
        private Double value;
    }

    /** 趋势上的一天 */
    @Data
    public static class Point {
        /** 展示用横轴标签。"周一"或 "MM-dd" —— **不唯一**，不能当身份用 */
        private String date;
        /** 真实日期 {@code yyyy-MM-dd}。唯一键，前端点柱子要拿它去查明细 */
        private String dateIso;
        private Integer visitors;
        private Integer purchases;
        private Integer repurchases;
        /** 当日销售额（乡村点级口径）。非农产品业态恒为 0 */
        private Double sales;
    }

    /** 冷热散点上的一个点 */
    @Data
    public static class ScatterPoint {
        private String id;
        private String name;
        private String district;
        /** 窗口内到访人次 */
        private Integer x;
        /** 窗口平均承载率，0–1 */
        private Double y;
        /** 气泡大小用：窗口内到访人次（与 x 同值，但语义独立，便于以后换口径） */
        private Integer size;
    }
}
