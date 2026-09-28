package com.hanyou.brain.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.hanyou.brain.entity.Poi;
import com.hanyou.brain.entity.PoiVisitStat;
import com.hanyou.brain.entity.RiskEvent;
import com.hanyou.brain.entity.RiskRule;
import com.hanyou.brain.entity.WorkOrder;

import lombok.extern.slf4j.Slf4j;

/**
 * 风险规则引擎（M5）。
 *
 * <p><b>这是"判定"那一半，与"生成/解释"严格分开。</b>
 * 方案第 14 节的架构是"确定性 → 概率性，不可颠倒"：本类只做
 * 阈值比较、比值、分组统计，全部是确定性、可复现、可解释的判断；
 * LLM 只负责解释为什么与建议做什么（M7），**不参与判定**。
 * 这样做的直接好处是答辩时可以说："这条预警是规则算出来的，
 * 不是模型猜的" —— 而且能当场把指标值和阈值都指出来。
 *
 * <p><b>★ 本类不碰数据库、不碰 Spring 容器。</b>
 * 输入是一个 {@link Context} 值对象，输出是一批待落库的
 * {@link RiskEvent}。这样做的理由不是"架构好看"，而是：
 * 判定规则是这个模块里最容易改、也最需要反复推演的部分
 * （阈值调一调、窗口改一改），把它做成纯函数就能离线喂一批数据
 * 直接看结果，不用起服务、不用连库。IO 与落库在
 * {@code OpsServiceImpl} 里。
 *
 * <p>规则逻辑不写进数据库（{@code risk_rule.metric_expr} 那种做法）：
 * "比值 + 时间窗 + 分组比较"不是一句 SQL 能表达的，硬塞进去只会得到
 * 一列没人敢改的字符串。数据库里放的是**可调的阈值与开关**。
 */
@Slf4j
public final class RuleEngine {

    // ---- 规则编码，与 db/V8__m5_ops.sql 的种子、risk_rule.rule_id 一一对应 ----

    /** 景区客流超载（两档：>= threshold 预警，>= threshold_2 一级） */
    public static final String OVERLOAD = "OVERLOAD";
    /** 景区高位但周边乡村闲置。**本项目最核心的一条** —— 分流机制的触发点 */
    public static final String RURAL_IDLE = "RURAL_IDLE";
    /** 乡村体验到购买转化偏低 */
    public static final String LOW_CONVERSION = "LOW_CONVERSION";
    /** 负面评价激增 */
    public static final String REVIEW_SURGE = "REVIEW_SURGE";
    /** 热度突变 */
    public static final String HEAT_JUMP = "HEAT_JUMP";
    /** 乡村复购衰减 */
    public static final String REPURCHASE_DECAY = "REPURCHASE_DECAY";

    private static final String TYPE_SCENIC = "SCENIC";
    private static final String TYPE_RURAL = "RURAL_SPOT";

    private RuleEngine() {
    }

    /**
     * 一次扫描的全部输入。
     *
     * @param rules       启用的规则，按 rule_id 索引
     * @param pois        资源点，按 id 索引
     * @param seriesByPoi 每个资源点的日度统计，**按日期升序**
     * @param today       判定基准日。取数据里最新的一天，而不是 LocalDate.now()
     *                    —— 服务跨零点重启时两者会差一天，用 now() 会去查一个
     *                    还不存在的日期，得到"今天没有风险"这种假象
     */
    public record Context(
            Map<String, RiskRule> rules,
            Map<String, Poi> pois,
            Map<String, List<PoiVisitStat>> seriesByPoi,
            LocalDate today) {
    }

    /**
     * 跑全部规则，返回命中的事件（未落库）。
     *
     * <p>顺序固定为规则定义的顺序，这样每次扫描产出的事件列表是稳定的，
     * 界面上的排列不会在两次刷新之间跳来跳去。
     */
    public static List<RiskEvent> evaluate(Context ctx) {
        List<RiskEvent> out = new ArrayList<>();

        evalOverload(ctx, out);
        evalRuralIdle(ctx, out);
        evalLowConversion(ctx, out);
        evalReviewSurge(ctx, out);
        evalHeatJump(ctx, out);
        evalRepurchaseDecay(ctx, out);

        // 一级排前面。同为一级时按指标值降序 —— 最严重的先看到
        out.sort(Comparator
                .comparing((RiskEvent e) -> RiskEvent.LEVEL_HIGH.equals(e.getLevel()) ? 0 : 1)
                .thenComparing(RiskEvent::getMetricValue, Comparator.reverseOrder()));
        return out;
    }

    // ------------------------------------------------------------------
    // 规则 1：景区客流超载
    // ------------------------------------------------------------------

    /**
     * 当日承载率超阈值。两档：
     * <ul>
     *   <li>{@code >= threshold_2}（默认 1.00）→ 一级，已经超载</li>
     *   <li>{@code >= threshold}（默认 0.80）→ 预警，接近上限</li>
     * </ul>
     *
     * <p><b>预警档的阈值可以被单个景点覆盖</b>（M10 续）：
     * {@code poi.warning_threshold} 非空时用它，为空时回退 {@code risk_rule.threshold}。
     *
     * <p>一级档（超载）**不覆盖**，也不该覆盖 —— "装不下就是装不下"是物理事实，
     * 不是运营口径。允许把它调低只会让系统报出一批"其实还装得下"的一级告警。
     *
     * <p>两档写成一条规则而不是两条，是因为它们**同一个指标、同一个动作**，
     * 只是紧急程度不同。拆成两条规则会让界面出现"同一个景区两条几乎
     * 一样的告警"，而运营真正需要的是"这条要立刻处理，那条先盯着"。
     */
    private static void evalOverload(Context ctx, List<RiskEvent> out) {
        RiskRule rule = ctx.rules().get(OVERLOAD);
        if (rule == null) {
            return;
        }
        // 全局阈值（risk_rule.threshold）。景点没有自己配预警线时回退到它。
        BigDecimal globalWarn = rule.getThreshold();
        BigDecimal severe = rule.getThreshold2() != null ? rule.getThreshold2() : BigDecimal.ONE;

        for (PoiVisitStat s : todayStats(ctx)) {
            Poi poi = ctx.pois().get(s.getPoiId());
            if (poi == null || !TYPE_SCENIC.equals(poi.getBusinessType())) {
                continue;
            }

            // ★ 景点自己配了预警线就用它（M10 续，poi.warning_threshold）。
            //
            // 没配（null）就回退全局 —— 现有 42 条景点的这一列**全是 null**
            // （数据包不提供它，只有运营在管理端接管后编辑才会写），
            // 所以对既有数据的行为完全不变。这是这次改动唯一的安全前提，
            // 换城市时也要成立：新数据包里的景点同样不带这一列。
            //
            // 为什么让景点覆盖全局，而不是反过来：全局阈值回答的是
            // "这类资源一般多少算挤"，而每个景点的承载弹性差别很大
            // （峡谷栈道 9000 人与博物馆 3000 人不是一回事）。
            // 运营最了解自己管的那一个点。
            BigDecimal warn = poi.getWarningThreshold() != null
                    ? poi.getWarningThreshold()
                    : globalWarn;

            BigDecimal usage = s.getCapacityUsage();
            boolean over = usage.compareTo(severe) >= 0;
            if (!over && usage.compareTo(warn) < 0) {
                continue;
            }

            int pct = toPercent(usage);
            if (over) {
                out.add(event(rule, poi, ctx.today(), RiskEvent.LEVEL_HIGH, usage, severe,
                        "景区客流超载",
                        "当日承载 " + pct + "%，已超过设计上限 "
                                + nz(poi.getCapacity()) + " 人（到访 " + nz(s.getVisitors()) + " 人次）",
                        "启动分时预约与现场分流，同步向未入园游客推送周边乡村替代方案"));
            } else {
                out.add(event(rule, poi, ctx.today(), RiskEvent.LEVEL_MID, usage, warn,
                        "景区承载接近上限",
                        "当日承载 " + pct + "%，距设计上限 " + nz(poi.getCapacity()) + " 人仅余 "
                                + Math.max(0, nz(poi.getCapacity()) - nz(s.getVisitors())) + " 人",
                        "提前向未入园游客推送周边乡村点，削峰填谷，避免到门口才发现进不去"));
            }
        }
    }

    // ------------------------------------------------------------------
    // 规则 4：景区高位但周边乡村闲置（★ 核心规则）
    // ------------------------------------------------------------------

    /**
     * 同区县内「景区平均承载高」而「乡村平均承载低」。
     *
     * <p>这条规则是本项目创新点的落点：它把"监测预警"推进到"调度闭环"——
     * 命中后生成的是**分流工单**，而分流工单会同时影响管理端（下发推荐位）
     * 与游客端（行程规划时上调该区域乡村点的权重）。
     *
     * <p><b>按区县聚合而不是逐点比较</b>：分流是"把这一带的溢出客流导到
     * 这一带的村子"，它天然是区域级的判断。若逐点比，一个景区旁边 90 公里
     * 外的村子也会被算进来，而那个距离已经不是"顺路去看看"了。
     *
     * <p>事件的 poi_id 取该区县**承载最高的那个景区**：它是这一带压力的
     * 来源，也是运营打开详情时最该看到的那一个。区县另存在 district 列上，
     * 列表可以按区县筛。
     */
    private static void evalRuralIdle(Context ctx, List<RiskEvent> out) {
        RiskRule rule = ctx.rules().get(RURAL_IDLE);
        if (rule == null) {
            return;
        }
        BigDecimal scenicBar = rule.getThreshold();
        BigDecimal ruralBar = rule.getThreshold2() != null ? rule.getThreshold2() : BigDecimal.ZERO;

        // 区县 -> 该区县当日承载率
        Map<String, List<Map.Entry<Poi, BigDecimal>>> byDistrict = new LinkedHashMap<>();
        for (PoiVisitStat s : todayStats(ctx)) {
            Poi poi = ctx.pois().get(s.getPoiId());
            if (poi == null || poi.getDistrict() == null) {
                continue;
            }
            boolean wanted = TYPE_SCENIC.equals(poi.getBusinessType())
                    || TYPE_RURAL.equals(poi.getBusinessType());
            if (!wanted) {
                continue;
            }
            byDistrict.computeIfAbsent(poi.getDistrict(), k -> new ArrayList<>())
                    .add(Map.entry(poi, s.getCapacityUsage()));
        }

        for (Map.Entry<String, List<Map.Entry<Poi, BigDecimal>>> e : byDistrict.entrySet()) {
            List<Map.Entry<Poi, BigDecimal>> scenics = e.getValue().stream()
                    .filter(x -> TYPE_SCENIC.equals(x.getKey().getBusinessType()))
                    .toList();
            List<Map.Entry<Poi, BigDecimal>> rurals = e.getValue().stream()
                    .filter(x -> TYPE_RURAL.equals(x.getKey().getBusinessType()))
                    .toList();
            // 只有"这一带既有景区又有乡村"才谈得上分流
            if (scenics.isEmpty() || rurals.isEmpty()) {
                continue;
            }

            BigDecimal scenicAvg = average(scenics.stream().map(Map.Entry::getValue).toList());
            BigDecimal ruralAvg = average(rurals.stream().map(Map.Entry::getValue).toList());

            if (scenicAvg.compareTo(scenicBar) <= 0 || ruralAvg.compareTo(ruralBar) >= 0) {
                continue;
            }

            Poi busiest = scenics.stream()
                    .max(Comparator.comparing(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .orElse(scenics.get(0).getKey());

            out.add(event(rule, busiest, ctx.today(), RiskEvent.LEVEL_HIGH, scenicAvg, scenicBar,
                    "景区高位运行但周边乡村闲置",
                    e.getKey() + " 景区平均承载 " + toPercent(scenicAvg) + "、乡村平均承载 "
                            + toPercent(ruralAvg) + "（阈值 " + toPercent(ruralBar) + "）"
                            + "，乡村有 " + (100 - toPercent(ruralAvg)) + "% 的承接余量未被利用",
                    "生成乡村分流工单：上调该区域乡村点在行程规划中的推荐权重，"
                            + "并在游客端下发推荐位，把溢出客流导向乡村"));
        }
    }

    // ------------------------------------------------------------------
    // 规则 5：乡村体验到购买转化偏低
    // ------------------------------------------------------------------

    /**
     * 近 N 日「乡村体验到购买」的转化率低于阈值。
     *
     * <p>分母是**体验参与人次**而不是到访人次：产品的定位是"体验的可带走形态"，
     * 一个只是路过村子、没参加任何体验的游客没买东西，说明不了转化问题。
     * 拿"没参加体验的人"当分母，会让所有乡村点的转化率都被稀释到很低，
     * 规则全部误报。
     */
    private static void evalLowConversion(Context ctx, List<RiskEvent> out) {
        RiskRule rule = ctx.rules().get(LOW_CONVERSION);
        if (rule == null) {
            return;
        }
        int window = rule.getWindowDays() != null ? rule.getWindowDays() : 7;
        BigDecimal bar = rule.getThreshold();

        for (Map.Entry<String, List<PoiVisitStat>> e : ctx.seriesByPoi().entrySet()) {
            Poi poi = ctx.pois().get(e.getKey());
            if (poi == null || !TYPE_RURAL.equals(poi.getBusinessType())) {
                continue;
            }
            long visits = sum(e.getValue(), ctx.today(), window, PoiVisitStat::getExperienceVisits);
            long buys = sum(e.getValue(), ctx.today(), window, PoiVisitStat::getPurchases);
            // 样本太小不下判断：近 7 日只有 3 个人参加体验，转化率 0% 或 100%
            // 都说明不了问题，报出来只会让人把预警当噪音
            if (visits < MIN_EXPERIENCE_SAMPLE) {
                continue;
            }
            BigDecimal rate = ratio(buys, visits);
            if (rate.compareTo(bar) >= 0) {
                continue;
            }
            out.add(event(rule, poi, ctx.today(), RiskEvent.LEVEL_MID, rate, bar,
                    "乡村体验到购买转化偏低",
                    "近 " + window + " 日体验 " + visits + " 人次，仅 " + buys
                            + " 笔购买，转化 " + toPercent(rate) + "%（阈值 " + toPercent(bar) + "%）",
                    "调整该乡村关联产品的曝光位与组合方式（体验+产品打包），"
                            + "并在行程规划中提高其产品推荐权重"));
        }
    }

    /**
     * 转化率判定的最小样本。低于它不下判断 —— 与规则 2 的 min_sample 同理，
     * 但规则 5 的这个下限是**代码常量而非配置**：它是"统计上说不说得通"的
     * 边界，不是运营口径，调它需要重新想一遍，不该在界面上随手改。
     */
    private static final int MIN_EXPERIENCE_SAMPLE = 20;

    // ------------------------------------------------------------------
    // 规则 2：负面评价激增
    // ------------------------------------------------------------------

    /**
     * 近 1 日负面评价率超阈值，且样本量够。
     *
     * <p>{@code min_sample} 这个约束不能省：一个点位当天只有 2 条评价、
     * 其中 1 条是差评，负面率 50% —— 超过 30% 的阈值，但它什么也不说明。
     * 没有样本下限的比率型规则，在小样本上一定会误报。
     */
    private static void evalReviewSurge(Context ctx, List<RiskEvent> out) {
        RiskRule rule = ctx.rules().get(REVIEW_SURGE);
        if (rule == null) {
            return;
        }
        int window = rule.getWindowDays() != null ? rule.getWindowDays() : 1;
        int minSample = rule.getMinSample() != null ? rule.getMinSample() : 5;
        BigDecimal bar = rule.getThreshold();

        for (Map.Entry<String, List<PoiVisitStat>> e : ctx.seriesByPoi().entrySet()) {
            Poi poi = ctx.pois().get(e.getKey());
            if (poi == null) {
                continue;
            }
            long total = sum(e.getValue(), ctx.today(), window, PoiVisitStat::getReviewCount);
            long negative = sum(e.getValue(), ctx.today(), window, PoiVisitStat::getNegativeCount);
            if (total < minSample) {
                continue;
            }
            BigDecimal rate = ratio(negative, total);
            if (rate.compareTo(bar) <= 0) {
                continue;
            }
            out.add(event(rule, poi, ctx.today(), RiskEvent.LEVEL_MID, rate, bar,
                    "负面评价激增",
                    "近 " + window + " 日评价 " + total + " 条，其中负面 " + negative
                            + " 条，负面率 " + toPercent(rate) + "%（阈值 " + toPercent(bar)
                            + "%，样本下限 " + minSample + "）",
                    "派服务质量整改工单：核查现场服务、排队与配套设施，24 小时内给出处置说明"));
        }
    }

    // ------------------------------------------------------------------
    // 规则 3：热度突变
    // ------------------------------------------------------------------

    /**
     * 近 N 日客流相对**上一个同等长度窗口**的增幅超阈值。
     *
     * <p>比的是"近 7 日 vs 前 7 日"而不是"今天 vs 昨天"：单日客流受天气、
     * 星期几影响极大，日环比全是噪音。窗口对窗口才看得出趋势。
     *
     * <p>要求上一个窗口有量（{@code prev > 0}）：数据包只有 30 天时，
     * 前一个 7 日窗口可能整个是空的，此时比值无意义，跳过而不是当 0 处理
     * —— 把"没有数据"当成"原来是 0"，会把每一个点位都报成"暴涨"。
     */
    private static void evalHeatJump(Context ctx, List<RiskEvent> out) {
        RiskRule rule = ctx.rules().get(HEAT_JUMP);
        if (rule == null) {
            return;
        }
        int window = rule.getWindowDays() != null ? rule.getWindowDays() : 7;
        BigDecimal bar = rule.getThreshold();

        for (Map.Entry<String, List<PoiVisitStat>> e : ctx.seriesByPoi().entrySet()) {
            Poi poi = ctx.pois().get(e.getKey());
            if (poi == null) {
                continue;
            }
            long cur = sum(e.getValue(), ctx.today(), window, PoiVisitStat::getVisitors);
            long prev = sum(e.getValue(), ctx.today().minusDays(window), window, PoiVisitStat::getVisitors);
            if (prev <= 0) {
                continue;
            }
            BigDecimal growth = BigDecimal.valueOf(cur - prev)
                    .divide(BigDecimal.valueOf(prev), 4, RoundingMode.HALF_UP);
            if (growth.compareTo(bar) <= 0) {
                continue;
            }
            out.add(event(rule, poi, ctx.today(), RiskEvent.LEVEL_MID, growth, bar,
                    "热度突变",
                    "近 " + window + " 日客流 " + cur + " 人次，上一个 " + window + " 日为 " + prev
                            + " 人次，环比 +" + toPercent(growth) + "%（阈值 +" + toPercent(bar) + "%）",
                    "复核该点位承载力与周边配套：确认现有承载与接待能力是否撑得住当前热度"));
        }
    }

    // ------------------------------------------------------------------
    // 规则 6：乡村复购衰减
    // ------------------------------------------------------------------

    /**
     * 近 N 日复购量相对上一个同等长度窗口的降幅超阈值。
     *
     * <p><b>口径说明（与方案第 15 节规则 6 有一处收敛，需留意）：</b>
     * 方案原文是"某**产品** 30 日复购率环比下降 > 40%"，这里做成了
     * "某**乡村点**复购量环比下降"。收敛的原因是数据粒度：本模块的统计表
     * 是"资源点 × 天"，产品级的日度复购数据要再建一张表，而方案第 16 节
     * 自己定的红线是"19 张表，别再加"。
     *
     * <p>后果要说清楚：这条规则能回答"哪个村的复购在掉"，
     * **不能**回答"哪个产品的复购在掉"。对当前的动作（派触达建议工单）够用，
     * 但如果以后要做"按产品调推荐权重"，得补产品级统计。
     */
    private static void evalRepurchaseDecay(Context ctx, List<RiskEvent> out) {
        RiskRule rule = ctx.rules().get(REPURCHASE_DECAY);
        if (rule == null) {
            return;
        }
        int window = rule.getWindowDays() != null ? rule.getWindowDays() : 30;
        BigDecimal bar = rule.getThreshold();

        for (Map.Entry<String, List<PoiVisitStat>> e : ctx.seriesByPoi().entrySet()) {
            Poi poi = ctx.pois().get(e.getKey());
            if (poi == null || !TYPE_RURAL.equals(poi.getBusinessType())) {
                continue;
            }
            long cur = sum(e.getValue(), ctx.today(), window, PoiVisitStat::getRepurchases);
            long prev = sum(e.getValue(), ctx.today().minusDays(window), window, PoiVisitStat::getRepurchases);
            if (prev <= 0) {
                continue;
            }
            BigDecimal growth = BigDecimal.valueOf(cur - prev)
                    .divide(BigDecimal.valueOf(prev), 4, RoundingMode.HALF_UP);
            // 降幅超过阈值：growth 是负数，bar 是正数，所以比的是 -growth > bar
            if (growth.negate().compareTo(bar) <= 0) {
                continue;
            }
            out.add(event(rule, poi, ctx.today(), RiskEvent.LEVEL_MID, growth, bar,
                    "乡村复购衰减",
                    "近 " + window + " 日复购 " + cur + " 笔，上一个 " + window + " 日为 " + prev
                            + " 笔，环比 " + toPercent(growth) + "%（阈值 -" + toPercent(bar) + "%）",
                    "派触达建议工单：对体验过该乡村的用户做一次复购触达，"
                            + "并复核产品供给与定价是否有变化"));
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 各资源点**基准日**那一行。规则 1/4 判的是"当日"，不涉窗口 */
    private static List<PoiVisitStat> todayStats(Context ctx) {
        List<PoiVisitStat> out = new ArrayList<>();
        for (List<PoiVisitStat> series : ctx.seriesByPoi().values()) {
            for (PoiVisitStat s : series) {
                if (ctx.today().equals(s.getStatDate())) {
                    out.add(s);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 取以 {@code end} 结尾、长度为 {@code days} 天的窗口内某个字段的合计。
     *
     * <p>窗口是**闭区间** [end-days+1, end]。写成闭区间是为了让
     * "近 7 日"包含今天 —— 排他写法会让今天的客流永远不算在"近 7 日"里，
     * 而那正是最该被看到的一天。
     */
    private static long sum(List<PoiVisitStat> series, LocalDate end, int days,
            Function<PoiVisitStat, Integer> field) {
        LocalDate from = end.minusDays(days - 1L);
        long total = 0;
        for (PoiVisitStat s : series) {
            LocalDate d = s.getStatDate();
            if (!d.isBefore(from) && !d.isAfter(end)) {
                Integer v = field.apply(s);
                total += v == null ? 0 : v;
            }
        }
        return total;
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    /** 0.8842 -> 88（百分比取整）。界面上的"承载 88%"与这里算的是同一个数 */
    private static int toPercent(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static RiskEvent event(RiskRule rule, Poi poi, LocalDate date, String level,
            BigDecimal metric, BigDecimal threshold, String title, String detail, String suggestion) {
        RiskEvent e = new RiskEvent();
        e.setRuleId(rule.getRuleId());
        e.setPoiId(poi.getId());
        e.setPoiName(poi.getName());
        e.setDistrict(poi.getDistrict());
        e.setStatDate(date);
        e.setLevel(level);
        e.setMetricValue(metric);
        e.setThreshold(threshold);
        e.setTitle(title);
        e.setDetail(detail);
        e.setSuggestion(suggestion);
        e.setStatus(RiskEvent.STATUS_OPEN);
        return e;
    }

    /** 供 OpsServiceImpl 记录日志用：把事件按规则归类计数 */
    public static Map<String, Long> countByRule(List<RiskEvent> events) {
        return events.stream()
                .collect(Collectors.groupingBy(RiskEvent::getRuleId, HashMap::new, Collectors.counting()));
    }

    /** 工单类型由规则的动作类型决定，映射集中在这里，避免各处各写一份 */
    public static String workOrderType(RiskRule rule) {
        return rule == null || rule.getActionType() == null ? WorkOrder.TYPE_MONITOR : rule.getActionType();
    }
}
