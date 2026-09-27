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
     * <p><b>必须写清"哪些数是当日、哪些是近 7 日"</b>：本快照里两类混在一起 ——
     * 客流类（到访、占比、承载、区县对比）取**当日**，趋势与消费类
     * （销售额、复购率）取**近 7 日**。早先这里只写"近 7 日"，而页面上
     * 标着"今日全市到访"的数字其实是当日的，两个说法对不上。
     */
    private String periodLabel;

    /** 是否仿真数据。恒为 true，界面上要标出来 */
    private Boolean synthetic;

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

    /** 近 7 日农产品销售额（含乡村点到访当场带走与离境复购两类订单） */
    private Double productSales;

    /** 近 7 日离境复购率，0–1。分子是复购单数、分母是总单数 */
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
        private String date;
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
