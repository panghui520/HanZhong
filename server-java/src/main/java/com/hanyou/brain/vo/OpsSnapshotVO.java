package com.hanyou.brain.vo;

import java.util.List;

import lombok.Data;

/**
 * 运营快照（管理端大屏）。
 *
 * <p><b>字段形状刻意与前端旧 mock（{@code web/src/mock/ops.ts} 的 OpsSnapshot）
 * 保持一致</b>：大屏的 6 组图表已经按那个形状写好并验收过了。换数据源时
 * 只改"从哪来"，不改"长什么样"—— 否则每换一个字段都要回去改一遍图表配置，
 * 而图表配置里的字段名写错是静默的（图上就是一条平线或空白，不报错）。
 *
 * <p>唯一改动的是 {@code ruralSalesTop}：旧 mock 叫 {@code productTop}、
 * 装的是"产品销量"。真实数据只支持到**乡村点级**（见 OpsService 的口径说明），
 * 所以改成乡村点销售额排行。字段名跟着语义一起改，不留一个名叫 productTop
 * 却装着乡村的字段。
 *
 * <p><b>本快照全部来自仿真数据</b>（{@code poi_visit_stats}，synthetic=1）。
 * 真实订单不混进来：混进来会让大屏的数字随演示过程中的每一次下单跳动，
 * 而"演示时数字自己变"是最难解释的一种现象。真实订单在运营端「订单处理」页看。
 */
@Data
public class OpsSnapshotVO {

    /**
     * 数据周期说明。前端直接显示，避免前端自己拼一句话而与实际口径不一致。
     *
     * <p><b>2026-10-04 起：全屏统一为一个统计区间。</b>在此之前客流类取当日、
     * 消费类取近 7 日，两类混在一屏上，这里必须逐个交代"哪个数是几天的"。
     * 现在由 {@link #range} 决定窗口，所有指标同区间，这句话改说
     * "统计区间 X ～ Y（N 天）"，并保留 {@code 仿真} 二字。
     */
    private String periodLabel;

    /** 是否仿真数据。恒为 true，界面上要标出来 */
    private Boolean synthetic;

    /**
     * 统计区间档位：{@code TODAY} / {@code LAST7} / {@code ALL}。
     *
     * <p>回显给前端，避免"前端以为自己选了近 7 日、后端按今日算"这种
     * 静默不一致 —— 数字对不上时没人能一眼看出是谁错了。
     */
    private String range;

    /** 统计区间起始（含），{@code yyyy-MM-dd}。与 {@link #rangeTo} 一起说明这段数字覆盖了哪几天 */
    private String rangeFrom;

    /** 统计区间结束（含），{@code yyyy-MM-dd} */
    private String rangeTo;

    /**
     * 当日**核心景区**到访人次。
     *
     * <p><b>不是"全市到访"</b>，也不是"独立游客数"：
     *   · 不含乡村、餐饮、住宿、交通 —— 那些是配套业态，
     *     它们的到访与景区到访高度重叠，加进来会重复计数；
     *   · 计数单位是**人次**，同一个人逛两个景区算两次。
     * 本项目的仿真数据只支持到"一个资源点 × 一天"，没有游客身份，
     * 所以"独立游客数"这个口径在当前数据下算不出来 —— 不要这样声称。
     */
    private Integer totalVisitors;

    /** 当日乡村点到访人次。与 totalVisitors 同口径（人次、当日） */
    private Integer ruralVisitors;

    /**
     * 乡村到访占"核心景区 + 乡村"的比例，0–1。
     *
     * <p>分母刻意只含这两类：本模块要讲的是"客流有没有从景区导向乡村"，
     * 餐饮住宿交通的到访不参与这个判断。所以它**不是**"乡村占全市到访的比例"。
     */
    private Double ruralRatio;

    /**
     * 窗口内农产品销售额（`range` 决定窗口）。
     *
     * <p><b>口径是"乡村点级"而不是"单品级"：</b>统计表只有"资源点 × 天"的
     * 购买笔数，没有逐单流水，所以这里是
     * <i>该乡村点关联产品的均价 × 该点窗口内的购买笔数</i>。
     * 界面上那一栏标题写"按产地归集"，就是为了不让人读成单品排行。
     */
    private Double productSales;

    /**
     * 窗口内**乡村复购率**，0–1 = <b>复购笔数 / 购买笔数</b>。
     *
     * <p><b>这个字段曾经叫"离境复购率"，是过度声称，已改名：</b>
     * 那个名字会被读成"离境消费的用户里有多少又买了" —— 一个**用户级**
     * 复购率。而本表是"资源点 × 天"的汇总，<b>没有用户身份维度</b>，
     * 用户级复购率在当前数据模型下<b>算不出来</b>（`orders` 表虽有
     * `channel=REPURCHASE`，但只有几十行运行期数据，没有历史）。
     * 名字改了、算法没动 —— 算法本来就是对的，错的是名字。
     */
    private Double repurchaseRate;

    /** 当前未闭环的风险事件数（不含已闭环的历史事件） */
    private Integer openRisks;

    /** 近 7 日趋势。date 是"周一"这类星期名，前端直接当横轴标签用 */
    private List<TrendPoint> trend;

    /** 当日到访的业态构成 */
    private List<MixSlice> mix;

    /** 按区县的冷热对比 */
    private List<DistrictImbalance> imbalance;

    /** 乡村点销售额排行（近 7 日） */
    private List<SalesRow> ruralSalesTop;

    /** 大屏右侧只展示这几条，按"等级优先 + 类型多样"挑过 */
    private List<RiskEventVO> risks;

    /** 承载 >=80% 的景区数。AI 建议文案用，避免硬编码与实际数据打架 */
    private Integer hotScenicCount;

    /** 景区高位但乡村闲置的区县名 */
    private List<String> idleDistricts;

    /** 乡村点平均承载占用率，0–1 */
    private Double ruralAvgUsage;

    private Deltas deltas;

    /** 趋势图上的一天 */
    @Data
    public static class TrendPoint {
        /** 横轴标签。"周一"或 "MM-dd" —— 是**展示用**的，不能当身份用 */
        private String date;

        /**
         * 这一天的真实日期，{@code yyyy-MM-dd}。
         *
         * <p>为什么不复用 {@link #date}：它 7 天内是星期名、更长是 MM-dd，
         * 都**不唯一**（60 天里会有 8 个"周一"）。前端点柱子要拿"这一天"
         * 去查风险事件数、去显示日期，必须有一个无歧义的键。由后端给，
         * 而不是前端从 {@code range_to} 倒推 —— 倒推依赖"趋势必定是连续天"
         * 这个约定，哪天趋势改成按周聚合，前端会静默地算错日子。
         */
        private String dateIso;

        private Integer visitors;

        /** 当日整体承载占用率，百分比数值（88.4 表示 88.4%） */
        private Double usage;
    }

    /** 业态构成的一块 */
    @Data
    public static class MixSlice {
        private String name;
        private Integer value;
    }

    /** 一个区县的景区 / 乡村平均承载，单位是百分比数值 */
    @Data
    public static class DistrictImbalance {
        private String name;
        private Integer scenic;
        private Integer rural;
    }

    /** 销售额排行的一行 */
    @Data
    public static class SalesRow {
        private String name;
        private Double sales;
    }

    /**
     * 环比变化。由"上一个同等长度窗口"真实算出来，不是写死的字符串 ——
     * 否则界面上会出现"数据变了、涨跌幅不变"的自相矛盾。
     */
    @Data
    public static class Deltas {
        /** 到访人次环比，百分比数值 */
        private Double visitorsPct;
        /** 乡村占比环比，百分点 */
        private Double ruralRatioPt;
        /** 销售额环比，百分比数值 */
        private Double salesPct;
        /** 复购率环比，百分点 */
        private Double repurchasePt;
    }
}
