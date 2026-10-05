package com.hanyou.brain.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.common.VoUtils;
import com.hanyou.brain.config.HanYouProperties;
import com.hanyou.brain.entity.Poi;
import com.hanyou.brain.entity.PoiVisitStat;
import com.hanyou.brain.entity.Product;
import com.hanyou.brain.entity.ProductCategory;
import com.hanyou.brain.entity.RiskEvent;
import com.hanyou.brain.entity.RiskRule;
import com.hanyou.brain.entity.WorkOrder;
import com.hanyou.brain.mapper.PoiMapper;
import com.hanyou.brain.mapper.PoiVisitStatMapper;
import com.hanyou.brain.mapper.ProductMapper;
import com.hanyou.brain.mapper.ProductCategoryMapper;
import com.hanyou.brain.mapper.RiskEventMapper;
import com.hanyou.brain.mapper.RiskRuleMapper;
import com.hanyou.brain.mapper.WorkOrderMapper;
import com.hanyou.brain.service.OpsService;
import com.hanyou.brain.service.support.DiversionAdvisor;
import com.hanyou.brain.service.support.RuleEngine;
import com.hanyou.brain.vo.DiversionCandidateVO;
import com.hanyou.brain.vo.OpsBusinessVO;
import com.hanyou.brain.vo.OpsSnapshotVO;
import com.hanyou.brain.vo.PoiStatVO;
import com.hanyou.brain.vo.RiskEventVO;
import com.hanyou.brain.vo.WorkOrderVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * M5 运营能力的编排层：读数据 → 交给 {@link RuleEngine} 判定 → 落库 → 组装视图。
 *
 * <p>判定逻辑一行都不在这里，全在 RuleEngine（纯函数）。本类只做 IO 与编排，
 * 这样"规则"那部分可以脱离 Spring 与数据库单独推演。
 *
 * <h3>★ 两处必须知道的口径约定</h3>
 *
 * <p><b>一、大屏的数字全部来自仿真数据，不混真实订单。</b>
 * 真实订单（M6）是运行期的、会随演示过程中的每一次下单而变。把它混进
 * 大屏，会出现"演示到一半，销售额自己跳了一下"—— 这是最难解释的一类现象。
 * 所以大屏读 {@code poi_visit_stats}（synthetic=1），真实订单在运营端
 * 「订单处理」页看。两边各说各的，且都说得清。
 *
 * <p><b>二、销售额与销量排行是"乡村点级"，不是"产品级"。</b>
 * 方案第 7.3 节原本要"农产品销量/销售额"，但本模块的统计表粒度是
 * "资源点 × 天"，产品级日度数据需要再建一张表，而方案第 16 节自己定的
 * 红线是"19 张表，别再加"。于是口径收敛到乡村点级：销售额 =
 * 该乡村点近 7 日购买笔数 × 该乡村点关联产品的均价。
 * 后果：能回答"哪个村在卖货"，不能回答"哪个产品好卖"。
 * 这与规则 6 的收敛同源，两处都记在 {@code docs/验收记录-M5.md} 里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsServiceImpl implements OpsService {

    private static final String TYPE_SCENIC = "SCENIC";
    private static final String TYPE_RURAL = "RURAL_SPOT";
    private static final String TYPE_FOOD = "FOOD";
    private static final String TYPE_LODGING = "LODGING";

    /** 承载高位线。与 risk_rule 的 OVERLOAD 默认阈值一致，但这里用于**展示**（打标"高位"），不参与判定 */
    private static final double HIGH_USAGE = 0.80;

    /** 大屏趋势图的天数（也是"近 7 日"档的窗口长度） */
    private static final int TREND_DAYS = 7;

    /** 统计区间档位。与前端驾驶舱顶部的日期控件一一对应 */
    private static final String RANGE_TODAY = "TODAY";
    private static final String RANGE_LAST7 = "LAST7";
    private static final String RANGE_LAST30 = "LAST30";
    private static final String RANGE_ALL = "ALL";

    /** "近 30 天"档的窗口长度 */
    private static final int LAST30_DAYS = 30;

    /** 长区间（>7 天）横轴改用 MM-dd：60 天里会出现 8 个"周一"，星期名没有区分度 */
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    /** 大屏右侧风险卡片的条数 */
    private static final int SNAPSHOT_RISK_LIMIT = 5;

    /** 销售额排行的条数 */
    private static final int SALES_TOP_LIMIT = 6;

    private static final DateTimeFormatter CODE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String[] WEEKDAY_CN = { "周一", "周二", "周三", "周四", "周五", "周六", "周日" };

    private final RiskRuleMapper riskRuleMapper;
    private final RiskEventMapper riskEventMapper;
    private final WorkOrderMapper workOrderMapper;
    private final PoiVisitStatMapper poiVisitStatMapper;
    private final PoiMapper poiMapper;
    private final ProductMapper productMapper;
    private final ProductCategoryMapper productCategoryMapper;
    private final HanYouProperties props;

    // ==================================================================
    // 规则扫描
    // ==================================================================

    @Override
    @Transactional
    public int scan() {
        String cityCode = props.getCity();
        RuleEngine.Context ctx = loadContext(cityCode);
        if (ctx.today() == null) {
            log.warn("[M5] 没有客流统计数据（city={}），规则引擎跳过。"
                    + "请确认 citypack/{}/visit_stats.json 存在且导入成功", cityCode, cityCode);
            return 0;
        }

        List<RiskEvent> candidates = RuleEngine.evaluate(ctx);

        // 已有事件按 (规则, 资源点, 日期) 索引 —— 与数据库唯一键同一组字段
        Map<String, RiskEvent> existing = riskEventMapper.selectList(null).stream()
                .collect(Collectors.toMap(OpsServiceImpl::eventKey, e -> e, (a, b) -> a, LinkedHashMap::new));

        Set<String> hit = new HashSet<>();
        int inserted = 0;
        int refreshed = 0;
        for (RiskEvent c : candidates) {
            String key = eventKey(c);
            hit.add(key);
            RiskEvent old = existing.get(key);
            if (old == null) {
                riskEventMapper.insert(c);
                inserted++;
                continue;
            }
            // 只刷新"由数据算出来的"字段。status / workOrderId 是运营动作的结果，
            // 不能被一次重扫覆盖掉 —— 否则建过单的事件会退回"待处置"，
            // 界面上工单还在、事件却显示没建单。
            old.setLevel(c.getLevel());
            old.setMetricValue(c.getMetricValue());
            old.setThreshold(c.getThreshold());
            old.setTitle(c.getTitle());
            old.setDetail(c.getDetail());
            old.setSuggestion(c.getSuggestion());
            riskEventMapper.updateById(old);
            refreshed++;
        }

        // 对账：当前数据已不再支持的**未建单**事件要清掉。
        // 为什么要清：数据包换一份、或服务隔天重启，日期窗口整体平移，
        // 旧日期的事件会永远留在列表里成为幽灵条目，而列表页看起来一切正常。
        // 为什么保留已建单的：那已经是运营动作的记录（谁在处置、处置到哪一步），
        // 不是"当前的发现"，不能因为重扫就抹掉。
        int removed = 0;
        for (RiskEvent old : existing.values()) {
            if (hit.contains(eventKey(old)) || old.getWorkOrderId() != null) {
                continue;
            }
            riskEventMapper.deleteById(old.getId());
            removed++;
        }

        Map<String, Long> byRule = RuleEngine.countByRule(candidates);
        log.info("[M5] 规则扫描完成 基准日={} 命中={}（新增 {} / 刷新 {}）清理幽灵事件={} 明细={}",
                ctx.today(), candidates.size(), inserted, refreshed, removed, byRule);
        return candidates.size();
    }

    private static String eventKey(RiskEvent e) {
        return e.getRuleId() + "|" + e.getPoiId() + "|" + e.getStatDate();
    }

    // ==================================================================
    // 运营快照
    // ==================================================================

    @Override
    public OpsSnapshotVO snapshot() {
        return snapshot(RANGE_TODAY);
    }

    /**
     * 运营快照，按统计区间算。
     *
     * <p><b>2026-10-04 的口径收敛（原写"客流当日 + 消费近 7 日"，见验收记录）：</b>
     * 之前全屏混着两个窗口 —— 客流类是当日、消费类是近 7 日，`period_label`
     * 得逐个交代"哪个数是几天的"。现在由 `range` 决定**一个**窗口，所有指标
     * 与图表共用它，界面上标"统计区间 X ～ Y（N 天）"即可。
     *
     * <p>窗口长度的推导全部复用已有的 {@link #sumWindow} / {@link #sumWindowAll}
     * —— 它们本来就以"以 end 结尾、长度 days 的闭区间"为签名，这里只是把
     * 常量 `TREND_DAYS` 换成按档位算出来的天数。取数逻辑一行没改。
     *
     * <p>无法识别的档位回落到 {@code TODAY}，不抛异常：一个拼错的 query 参数
     * 不该让整块大屏变成错误页。
     */
    @Override
    public OpsSnapshotVO snapshot(String range) {
        String cityCode = props.getCity();
        RuleEngine.Context ctx = loadContext(cityCode);
        String r = normalizeRange(range);

        OpsSnapshotVO vo = new OpsSnapshotVO();
        vo.setSynthetic(true);
        vo.setRange(r);
        vo.setRisks(List.of());
        vo.setTrend(List.of());
        vo.setMix(List.of());
        vo.setImbalance(List.of());
        vo.setRuralSalesTop(List.of());
        vo.setIdleDistricts(List.of());
        vo.setDeltas(new OpsSnapshotVO.Deltas());

        if (ctx.today() == null) {
            // 没有客流数据时返回一份"零值但结构完整"的快照，而不是 500。
            // 大屏上会出现一排 0 与空图，配合 periodLabel 能看出是数据缺失，
            // 而不是"系统坏了"—— 页面上给不出解释的错误最难排查。
            vo.setPeriodLabel("暂无客流统计（仿真）");
            vo.setRangeFrom("");
            vo.setRangeTo("");
            vo.setTotalVisitors(0);
            vo.setRuralVisitors(0);
            vo.setRuralRatio(0.0);
            vo.setProductSales(0.0);
            vo.setRepurchaseRate(0.0);
            vo.setOpenRisks(0);
            vo.setHotScenicCount(0);
            vo.setRuralAvgUsage(0.0);
            return vo;
        }

        LocalDate today = ctx.today();
        Map<String, Poi> pois = ctx.pois();

        // ---- 统计窗口：全屏共用这一个（天数由档位决定）----
        int days = windowDays(r, ctx, today);
        LocalDate from = today.minusDays(days - 1L);
        vo.setRangeFrom(from.toString());
        vo.setRangeTo(today.toString());
        vo.setPeriodLabel(periodLabel(from, today, days));

        // 窗口内每个资源点的**平均承载率**。区县冷热、高位景区数、乡村均值共用它 ——
        // 单日取一行在多天窗口下不成立（"近 7 日的承载"必须是一段均值，不是最后一天的值）
        Map<String, Double> usageAvg = avgUsageByPoi(ctx, today, days);

        // ---- 到访：按业态汇总（窗口内合计）----
        int scenicVisitors = (int) sumWindowAll(ctx.seriesByPoi(), today, days,
                PoiVisitStat::getVisitors, pois, TYPE_SCENIC);
        int ruralVisitors = (int) sumWindowAll(ctx.seriesByPoi(), today, days,
                PoiVisitStat::getVisitors, pois, TYPE_RURAL);
        vo.setTotalVisitors(scenicVisitors);
        vo.setRuralVisitors(ruralVisitors);
        vo.setRuralRatio(ratio(ruralVisitors, ruralVisitors + scenicVisitors));

        vo.setMix(List.of(
                mix("核心景区", scenicVisitors),
                mix("乡村旅游", ruralVisitors),
                mix("餐饮", (int) sumWindowAll(ctx.seriesByPoi(), today, days,
                        PoiVisitStat::getVisitors, pois, TYPE_FOOD)),
                mix("住宿", (int) sumWindowAll(ctx.seriesByPoi(), today, days,
                        PoiVisitStat::getVisitors, pois, TYPE_LODGING))));

        // ---- 区县冷热对比（窗口平均承载）----
        List<OpsSnapshotVO.DistrictImbalance> imbalance = imbalance(ctx, usageAvg);
        vo.setImbalance(imbalance);
        vo.setIdleDistricts(imbalance.stream()
                .filter(row -> row.getScenic() > HIGH_USAGE * 100 && row.getRural() < 20)
                .map(OpsSnapshotVO.DistrictImbalance::getName)
                .toList());

        // ---- 承载相关（窗口平均）----
        vo.setHotScenicCount((int) pois.values().stream()
                .filter(p -> TYPE_SCENIC.equals(p.getBusinessType()))
                .map(p -> usageAvg.get(p.getId()))
                .filter(Objects::nonNull)
                .filter(u -> u >= HIGH_USAGE)
                .count());
        List<Double> ruralUsages = pois.values().stream()
                .filter(p -> TYPE_RURAL.equals(p.getBusinessType()))
                .map(p -> usageAvg.get(p.getId()))
                .filter(Objects::nonNull)
                .toList();
        vo.setRuralAvgUsage(ruralUsages.isEmpty() ? 0.0 : avg(ruralUsages));

        // ---- 趋势：窗口内每日核心景区客流与整体承载 ----
        // 点数下限 7：选"今日"时若只画一根柱子，图上看不出任何走势。
        // 这也是"指标是当日、趋势是近 7 日"这唯一一处窗口不一致的由来 ——
        // 面板副标题会写明，不藏着。
        int trendDays = Math.max(days, TREND_DAYS);
        BigDecimal scenicCapacity = BigDecimal.ZERO;
        for (Poi p : pois.values()) {
            if (TYPE_SCENIC.equals(p.getBusinessType()) && p.getCapacity() != null) {
                scenicCapacity = scenicCapacity.add(BigDecimal.valueOf(p.getCapacity()));
            }
        }
        List<OpsSnapshotVO.TrendPoint> trend = new ArrayList<>();
        for (int i = trendDays - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long visitors = sumWindowAll(ctx.seriesByPoi(), d, 1, PoiVisitStat::getVisitors, pois, TYPE_SCENIC);
            OpsSnapshotVO.TrendPoint p = new OpsSnapshotVO.TrendPoint();
            p.setDate(trendDays <= TREND_DAYS ? weekdayCn(d.getDayOfWeek()) : d.format(MONTH_DAY));
            p.setDateIso(d.toString());
            p.setVisitors((int) visitors);
            p.setUsage(scenicCapacity.signum() == 0 ? 0.0
                    : BigDecimal.valueOf(visitors).multiply(BigDecimal.valueOf(100))
                            .divide(scenicCapacity, 1, RoundingMode.HALF_UP).doubleValue());
            trend.add(p);
        }
        vo.setTrend(trend);

        // ---- 销售额（乡村点级，见类注释的口径说明）----
        Map<String, BigDecimal> priceByPoi = avgPriceByPoi(cityCode);
        List<OpsSnapshotVO.SalesRow> sales = new ArrayList<>();
        BigDecimal salesTotal = BigDecimal.ZERO;
        long buysCur = 0;
        long repsCur = 0;
        for (Poi p : pois.values()) {
            if (!TYPE_RURAL.equals(p.getBusinessType())) {
                continue;
            }
            List<PoiVisitStat> series = ctx.seriesByPoi().getOrDefault(p.getId(), List.of());
            long buys = sumWindow(series, today, days, PoiVisitStat::getPurchases);
            buysCur += buys;
            repsCur += sumWindow(series, today, days, PoiVisitStat::getRepurchases);
            BigDecimal price = priceByPoi.getOrDefault(p.getId(), BigDecimal.ZERO);
            BigDecimal amount = price.multiply(BigDecimal.valueOf(buys));
            salesTotal = salesTotal.add(amount);
            if (amount.signum() > 0) {
                OpsSnapshotVO.SalesRow row = new OpsSnapshotVO.SalesRow();
                row.setName(p.getName());
                row.setSales(amount.setScale(2, RoundingMode.HALF_UP).doubleValue());
                sales.add(row);
            }
        }
        sales.sort(Comparator.comparing(OpsSnapshotVO.SalesRow::getSales).reversed());
        vo.setRuralSalesTop(sales.stream().limit(SALES_TOP_LIMIT).toList());
        vo.setProductSales(salesTotal.setScale(2, RoundingMode.HALF_UP).doubleValue());

        // ---- 复购率：窗口内复购笔数 / 购买笔数（乡村点）----
        // 上期窗口 = 紧邻当前窗口之前、等长的一段。取不到（如"全部"档没有更早的数据）
        // 时两期都为 0，`pctChange` 会返回 0 —— 显示成"0.0% 环比"，前端在全部档会改写文案
        LocalDate prevEnd = today.minusDays(days);
        long buysPrev = 0;
        long repsPrev = 0;
        for (Poi p : pois.values()) {
            if (!TYPE_RURAL.equals(p.getBusinessType())) {
                continue;
            }
            List<PoiVisitStat> series = ctx.seriesByPoi().getOrDefault(p.getId(), List.of());
            buysPrev += sumWindow(series, prevEnd, days, PoiVisitStat::getPurchases);
            repsPrev += sumWindow(series, prevEnd, days, PoiVisitStat::getRepurchases);
        }
        double repurchaseRate = ratio(repsCur, buysCur);
        vo.setRepurchaseRate(repurchaseRate);

        // ---- 风险：待处置数与右侧列表都按**统计区间**筛 ----
        // 不筛的话，切到"今日"时"待处置 17 件"与列表里的行数会对不上 ——
        // 而列表本来就是按风险统计日筛的（见 Risks.vue），两处必须同轴。
        List<RiskEvent> allEvents = riskEventMapper.selectList(
                new LambdaQueryWrapper<RiskEvent>().orderByDesc(RiskEvent::getStatDate));
        List<RiskEvent> eventsInRange = allEvents.stream()
                .filter(e -> e.getStatDate() != null
                        && !e.getStatDate().isBefore(from) && !e.getStatDate().isAfter(today))
                .toList();
        vo.setOpenRisks((int) eventsInRange.stream()
                .filter(e -> !RiskEvent.STATUS_CLOSED.equals(e.getStatus()))
                .count());
        Map<String, RiskRule> rules = ctx.rules();
        vo.setRisks(pickSnapshotRisks(eventsInRange, rules));

        // ---- 环比：当前窗口 vs 紧邻其前的等长窗口 ----
        long visitorsPrev = sumWindowAll(ctx.seriesByPoi(), prevEnd, days,
                PoiVisitStat::getVisitors, pois, TYPE_SCENIC);
        long ruralPrev = sumWindowAll(ctx.seriesByPoi(), prevEnd, days,
                PoiVisitStat::getVisitors, pois, TYPE_RURAL);

        OpsSnapshotVO.Deltas d = new OpsSnapshotVO.Deltas();
        d.setVisitorsPct(pctChange(scenicVisitors, visitorsPrev));
        d.setRuralRatioPt(round1((ratio(ruralVisitors, ruralVisitors + scenicVisitors)
                - ratio(ruralPrev, ruralPrev + visitorsPrev)) * 100));
        d.setSalesPct(pctChange(buysCur, buysPrev));
        d.setRepurchasePt(round1((repurchaseRate - ratio(repsPrev, buysPrev)) * 100));
        vo.setDeltas(d);

        return vo;
    }

    // ==================================================================
    // 单业态运营分析（管理端四大业务页面）
    // ==================================================================

    /** 业务页的业态短名 → {@code poi.business_type} 原值。product 不在这里：它是跨产地聚合，不是 poi 业态 */
    private static final Map<String, String> BIZ_TYPE_MAP = Map.of(
            "scenic", TYPE_SCENIC,
            "rural", TYPE_RURAL,
            "food", TYPE_FOOD,
            "lodging", TYPE_LODGING);

    private static final String BIZ_PRODUCT = "product";

    /** 承载档的中位线。高位线就是 {@link #HIGH_USAGE}（0.80），两者同源 */
    private static final double MID_USAGE = 0.50;

    /**
     * 单业态运营分析。与 {@link #snapshot(String)} 共用同一套取数函数 ——
     * 见 {@code OpsService.business} 的说明。
     */
    @Override
    public OpsBusinessVO business(String type, String range) {
        String t = normalizeBizType(type);
        RuleEngine.Context ctx = loadContext(props.getCity());
        String r = normalizeRange(range);

        OpsBusinessVO vo = new OpsBusinessVO();
        vo.setSynthetic(true);
        vo.setType(t);
        vo.setRange(r);
        vo.setKpis(List.of());
        vo.setTop(List.of());
        vo.setTrend(List.of());
        vo.setDistricts(List.of());
        vo.setScatter(List.of());
        vo.setDistribution(List.of());

        if (ctx.today() == null) {
            // 与 snapshot 同样返回"结构完整的零值"，而不是 500 —— 页面上给不出解释的错误最难排查
            vo.setPeriodLabel("暂无客流统计（仿真）");
            vo.setRangeFrom("");
            vo.setRangeTo("");
            return vo;
        }

        LocalDate today = ctx.today();
        int days = windowDays(r, ctx, today);
        LocalDate from = today.minusDays(days - 1L);
        vo.setRangeFrom(from.toString());
        vo.setRangeTo(today.toString());
        vo.setPeriodLabel(periodLabel(from, today, days));

        // 窗口内每个资源点的平均承载率。与 snapshot 同一个函数、同一个口径
        Map<String, Double> usageAvg = avgUsageByPoi(ctx, today, days);

        if (BIZ_PRODUCT.equals(t)) {
            fillProduct(vo, ctx, props.getCity(), today, days, r);
        } else {
            fillPoiBiz(vo, ctx, t, today, days, from, r, usageAvg);
        }
        return vo;
    }

    /** 业态短名归一化。**不认识就报错，不回落** —— 业务页拿错业态的数据比报错更糟 */
    private static String normalizeBizType(String type) {
        if (!StringUtils.hasText(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少业态参数 type");
        }
        String t = type.trim().toLowerCase();
        if (BIZ_TYPE_MAP.containsKey(t) || BIZ_PRODUCT.equals(t)) {
            return t;
        }
        throw new BizException(ErrorCode.BAD_REQUEST,
                "未知的业态：" + type + "（支持 scenic / rural / food / lodging / product）");
    }

    /** 景区 / 乡村 / 餐饮 / 住宿：都是"一批同业态的资源点" */
    private void fillPoiBiz(OpsBusinessVO vo, RuleEngine.Context ctx, String biz,
            LocalDate today, int days, LocalDate from, String range, Map<String, Double> usageAvg) {
        String poiType = BIZ_TYPE_MAP.get(biz);
        List<Poi> mine = ctx.pois().values().stream()
                .filter(p -> poiType.equals(p.getBusinessType()))
                .toList();

        int totalVisitors = 0;
        int highCount = 0;
        double usageSum = 0;
        int usageN = 0;
        List<OpsBusinessVO.Row> top = new ArrayList<>();
        List<OpsBusinessVO.ScatterPoint> scatter = new ArrayList<>();
        Map<String, Double> byDistrict = new LinkedHashMap<>();

        for (Poi p : mine) {
            List<PoiVisitStat> series = ctx.seriesByPoi().getOrDefault(p.getId(), List.of());
            int visitors = (int) sumWindow(series, today, days, PoiVisitStat::getVisitors);
            totalVisitors += visitors;

            OpsBusinessVO.Row row = new OpsBusinessVO.Row();
            row.setId(p.getId());
            row.setName(p.getName());
            row.setSub(p.getDistrict());
            row.setValue((double) visitors);
            top.add(row);

            Double u = usageAvg.get(p.getId());
            if (u != null) {
                usageSum += u;
                usageN++;
                if (u >= HIGH_USAGE) {
                    highCount++;
                }
            }

            OpsBusinessVO.ScatterPoint sp = new OpsBusinessVO.ScatterPoint();
            sp.setId(p.getId());
            sp.setName(p.getName());
            sp.setDistrict(p.getDistrict());
            sp.setX(visitors);
            sp.setY(u == null ? 0.0 : round4(u));
            sp.setSize(visitors);
            scatter.add(sp);

            if (p.getDistrict() != null) {
                byDistrict.merge(p.getDistrict(), (double) visitors, Double::sum);
            }
        }

        top.sort(Comparator.comparing(OpsBusinessVO.Row::getValue).reversed());
        vo.setTop(top);
        vo.setScatter(scatter);
        vo.setDistricts(sortedRows(byDistrict));
        vo.setDistribution(usageDistribution(mine, usageAvg));
        vo.setTrend(buildTrend(ctx, mine.stream().map(Poi::getId).collect(Collectors.toSet()),
                today, days, null));

        double avgUsage = usageN == 0 ? 0.0 : usageSum / usageN;
        String p = rangePrefix(range);
        List<OpsBusinessVO.Kpi> kpis = new ArrayList<>();
        if (TYPE_SCENIC.equals(poiType) || TYPE_RURAL.equals(poiType)) {
            kpis.add(kpi("visitors", p + "到访", num(totalVisitors), "人次"));
            kpis.add(kpi("usage", p + "平均承载率", percent(avgUsage), "%"));
            kpis.add(kpi("high", p + "高负荷资源", String.valueOf(highCount), "个"));
            kpis.add(kpi("risks", p + "风险事件",
                    String.valueOf(countRisks(mine, from, today)), "件"));
        } else {
            // 餐饮 / 住宿：这两个业态没有购买数据（poi_visit_stats 里恒为 0），
            // 所以 KPI 讲的是"有多少家、来了多少人、挤不挤"，不讲销售
            kpis.add(kpi("resources", p + "资源数", String.valueOf(mine.size()), "个"));
            kpis.add(kpi("visitors", p + "客流", num(totalVisitors), "人次"));
            kpis.add(kpi("usage", p + "承载均值", percent(avgUsage), "%"));
            kpis.add(kpi("high", p + "高负荷资源", String.valueOf(highCount), "个"));
        }
        vo.setKpis(kpis);
    }

    /** 农产品：跨全部乡村产地聚合。销售是**产地级**口径，见 OpsBusinessVO 的类注释 */
    private void fillProduct(OpsBusinessVO vo, RuleEngine.Context ctx, String cityCode,
            LocalDate today, int days, String range) {
        Map<String, BigDecimal> priceByPoi = avgPriceByPoi(cityCode);
        List<Poi> rural = ctx.pois().values().stream()
                .filter(p -> TYPE_RURAL.equals(p.getBusinessType()))
                .toList();

        BigDecimal total = BigDecimal.ZERO;
        long buys = 0;
        long reps = 0;
        List<OpsBusinessVO.Row> top = new ArrayList<>();
        Map<String, Double> byDistrict = new LinkedHashMap<>();

        for (Poi p : rural) {
            List<PoiVisitStat> series = ctx.seriesByPoi().getOrDefault(p.getId(), List.of());
            long b = sumWindow(series, today, days, PoiVisitStat::getPurchases);
            buys += b;
            reps += sumWindow(series, today, days, PoiVisitStat::getRepurchases);

            BigDecimal amount = priceByPoi.getOrDefault(p.getId(), BigDecimal.ZERO)
                    .multiply(BigDecimal.valueOf(b));
            total = total.add(amount);
            if (amount.signum() > 0) {
                OpsBusinessVO.Row row = new OpsBusinessVO.Row();
                row.setId(p.getId());
                row.setName(p.getName());
                row.setSub(p.getDistrict());
                row.setValue(amount.setScale(2, RoundingMode.HALF_UP).doubleValue());
                top.add(row);
                if (p.getDistrict() != null) {
                    byDistrict.merge(p.getDistrict(), amount.doubleValue(), Double::sum);
                }
            }
        }

        top.sort(Comparator.comparing(OpsBusinessVO.Row::getValue).reversed());
        vo.setTop(top);
        vo.setDistricts(sortedRows(byDistrict));
        vo.setDistribution(categoryDistribution(cityCode, ctx, today, days, priceByPoi));
        vo.setTrend(buildTrend(ctx, rural.stream().map(Poi::getId).collect(Collectors.toSet()),
                today, days, priceByPoi));

        String p = rangePrefix(range);
        List<OpsBusinessVO.Kpi> kpis = new ArrayList<>();
        kpis.add(kpi("sales", p + "农产品销售额", money(total), "元"));
        kpis.add(kpi("buys", p + "购买笔数", num(buys), "笔"));
        kpis.add(kpi("repurchase", p + "乡村复购率", percent(ratio(reps, buys)), "%"));
        kpis.add(kpi("origins", p + "产地数", String.valueOf(priceByPoi.size()), "个"));
        vo.setKpis(kpis);
    }

    /**
     * 逐日趋势。
     *
     * <p><b>点数下限 7</b>（{@code Math.max(days, TREND_DAYS)}）—— 与
     * {@code snapshot} 逐字相同的约定：选"今日"时只画一根柱子看不出走势。
     * 这是全系统唯一一处"窗口不一致"，界面上由 {@code period_label} 交代清楚。
     *
     * @param priceByPoi 非 null 时才算销售额（只有农产品需要）
     */
    private List<OpsBusinessVO.Point> buildTrend(RuleEngine.Context ctx, Set<String> poiIds,
            LocalDate today, int days, Map<String, BigDecimal> priceByPoi) {
        int trendDays = Math.max(days, TREND_DAYS);
        List<OpsBusinessVO.Point> out = new ArrayList<>();
        for (int i = trendDays - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            int visitors = 0;
            int purchases = 0;
            int repurchases = 0;
            BigDecimal sales = BigDecimal.ZERO;
            for (String id : poiIds) {
                List<PoiVisitStat> series = ctx.seriesByPoi().getOrDefault(id, List.of());
                visitors += (int) sumWindow(series, d, 1, PoiVisitStat::getVisitors);
                int b = (int) sumWindow(series, d, 1, PoiVisitStat::getPurchases);
                purchases += b;
                repurchases += (int) sumWindow(series, d, 1, PoiVisitStat::getRepurchases);
                if (priceByPoi != null) {
                    sales = sales.add(priceByPoi.getOrDefault(id, BigDecimal.ZERO)
                            .multiply(BigDecimal.valueOf(b)));
                }
            }
            OpsBusinessVO.Point pt = new OpsBusinessVO.Point();
            pt.setDate(trendDays <= TREND_DAYS ? weekdayCn(d.getDayOfWeek()) : d.format(MONTH_DAY));
            pt.setDateIso(d.toString());
            pt.setVisitors(visitors);
            pt.setPurchases(purchases);
            pt.setRepurchases(repurchases);
            pt.setSales(sales.setScale(2, RoundingMode.HALF_UP).doubleValue());
            out.add(pt);
        }
        return out;
    }

    /** 承载档构成：充裕 / 适中 / 高位。档线用窗口**平均**承载率判 */
    private List<OpsBusinessVO.Row> usageDistribution(List<Poi> mine, Map<String, Double> usageAvg) {
        int low = 0;
        int mid = 0;
        int high = 0;
        for (Poi p : mine) {
            Double u = usageAvg.get(p.getId());
            if (u == null) {
                continue;
            }
            if (u >= HIGH_USAGE) {
                high++;
            } else if (u >= MID_USAGE) {
                mid++;
            } else {
                low++;
            }
        }
        List<OpsBusinessVO.Row> out = new ArrayList<>();
        out.add(countRow("充裕（<50%）", low));
        out.add(countRow("适中（50%~80%）", mid));
        out.add(countRow("高位（≥80%）", high));
        return out;
    }

    /**
     * 销售结构（按产品分类）。
     *
     * <p><b>口径必须说清，否则会被读成"单品销量"：</b>统计表只有"产地 × 天"的
     * 购买笔数，没有逐单流水，所以<b>无法知道"这一笔买的是哪一款"</b>。
     * 做法是把该产地的销售额**按产地内各分类的产品款数均摊**
     * （产地有 3 款茶、1 款米 → 销售额的 3/4 记茶叶、1/4 记粮油）。
     * 这是一个**明确的假设**，不是实测值：这张图能回答"哪个品类在贡献收入"，
     * **不能**回答"哪一款最好卖"。界面上要标出来。
     */
    private List<OpsBusinessVO.Row> categoryDistribution(String cityCode, RuleEngine.Context ctx,
            LocalDate today, int days, Map<String, BigDecimal> priceByPoi) {
        List<Product> products = productMapper.selectList(
                new LambdaQueryWrapper<Product>().eq(Product::getCityCode, cityCode));
        // 产品存的是分类**编码**（category_code），显示名在 product_category 里。
        // 编码是稳定键（中文名会被改），但图上要给人看，所以在这里换一次名。
        Map<String, String> catName = new HashMap<>();
        for (ProductCategory c : productCategoryMapper.selectList(
                new LambdaQueryWrapper<ProductCategory>().eq(ProductCategory::getCityCode, cityCode))) {
            catName.put(c.getCode(), c.getName());
        }

        Map<String, Map<String, Integer>> catCount = new LinkedHashMap<>();
        for (Product pr : products) {
            if (pr.getPoiId() == null) {
                continue;
            }
            String code = StringUtils.hasText(pr.getCategoryCode()) ? pr.getCategoryCode() : "";
            String cat = catName.getOrDefault(code, StringUtils.hasText(code) ? code : "其他");
            catCount.computeIfAbsent(pr.getPoiId(), k -> new LinkedHashMap<>()).merge(cat, 1, Integer::sum);
        }

        Map<String, Double> byCat = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : catCount.entrySet()) {
            int kinds = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
            if (kinds == 0) {
                continue;
            }
            List<PoiVisitStat> series = ctx.seriesByPoi().getOrDefault(e.getKey(), List.of());
            long b = sumWindow(series, today, days, PoiVisitStat::getPurchases);
            double sales = priceByPoi.getOrDefault(e.getKey(), BigDecimal.ZERO)
                    .multiply(BigDecimal.valueOf(b)).doubleValue();
            if (sales <= 0) {
                continue;
            }
            e.getValue().forEach((cat, n) -> byCat.merge(cat, sales * n / kinds, Double::sum));
        }
        return sortedRows(byCat);
    }

    /** 窗口内、这批资源点上、未闭环的风险事件数。口径与 snapshot 的 open_risks 一致 */
    private long countRisks(List<Poi> mine, LocalDate from, LocalDate to) {
        if (mine.isEmpty()) {
            return 0;
        }
        Set<String> ids = mine.stream().map(Poi::getId).collect(Collectors.toSet());
        return riskEventMapper.selectList(new LambdaQueryWrapper<RiskEvent>()
                .ge(RiskEvent::getStatDate, from)
                .le(RiskEvent::getStatDate, to)).stream()
                .filter(e -> !RiskEvent.STATUS_CLOSED.equals(e.getStatus()))
                .filter(e -> e.getPoiId() != null && ids.contains(e.getPoiId()))
                .count();
    }

    /** 与 Python 侧 {@code _range_prefix} 同一份文案，两端口径不会漂移 */
    private static String rangePrefix(String range) {
        if (RANGE_LAST7.equals(range)) {
            return "近 7 日";
        }
        if (RANGE_LAST30.equals(range)) {
            return "近 30 日";
        }
        if (RANGE_ALL.equals(range)) {
            return "全部";
        }
        return "今日";
    }

    private static List<OpsBusinessVO.Row> sortedRows(Map<String, Double> byName) {
        List<OpsBusinessVO.Row> out = new ArrayList<>();
        byName.forEach((name, v) -> {
            OpsBusinessVO.Row row = new OpsBusinessVO.Row();
            row.setName(name);
            row.setValue(Math.round(v * 100.0) / 100.0);
            out.add(row);
        });
        out.sort(Comparator.comparing(OpsBusinessVO.Row::getValue).reversed());
        return out;
    }

    private static OpsBusinessVO.Row countRow(String name, int n) {
        OpsBusinessVO.Row row = new OpsBusinessVO.Row();
        row.setName(name);
        row.setValue((double) n);
        return row;
    }

    private static OpsBusinessVO.Kpi kpi(String key, String label, String value, String unit) {
        OpsBusinessVO.Kpi k = new OpsBusinessVO.Kpi();
        k.setKey(key);
        k.setLabel(label);
        k.setValue(value);
        k.setUnit(unit);
        return k;
    }

    /** 千分位。用 {@code Locale.ROOT} 固定分隔符 —— 默认 locale 变了会让同一份数据渲染出不同的字符串 */
    private static String num(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private static String money(BigDecimal v) {
        return "¥" + String.format(Locale.ROOT, "%,.2f", v.setScale(2, RoundingMode.HALF_UP));
    }

    /** 0–1 的比率 → 百分比数值字符串（0.1644 → "16.4"）。前端直接加 % */
    private static String percent(double ratio) {
        return BigDecimal.valueOf(ratio * 100).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /** 档位归一化。不认识的取值回落到 TODAY，不抛异常 */
    private static String normalizeRange(String range) {
        if (RANGE_LAST7.equalsIgnoreCase(range)) {
            return RANGE_LAST7;
        }
        if (RANGE_LAST30.equalsIgnoreCase(range)) {
            return RANGE_LAST30;
        }
        if (RANGE_ALL.equalsIgnoreCase(range)) {
            return RANGE_ALL;
        }
        return RANGE_TODAY;
    }

    /**
     * 档位 → 窗口长度（天）。
     *
     * <p>{@code ALL} 的天数**从数据里现算**（最早统计日 → 基准日），不写死 60：
     * 数据包换成 30 天或 90 天时，这里不改一行，窗口自动跟着数据走。
     *
     * <p>{@code LAST30} 在数据不足 30 天时**不缩水成实际天数**，仍返回 30 ——
     * {@link #sumWindow} 是按日期区间求和的，区间里没有数据就是 0，
     * 不会因为"取不满 30 天"而算出偏高的均值。
     */
    private int windowDays(String range, RuleEngine.Context ctx, LocalDate today) {
        if (RANGE_LAST7.equals(range)) {
            return TREND_DAYS;
        }
        if (RANGE_LAST30.equals(range)) {
            return LAST30_DAYS;
        }
        if (RANGE_ALL.equals(range)) {
            LocalDate min = null;
            for (List<PoiVisitStat> series : ctx.seriesByPoi().values()) {
                for (PoiVisitStat s : series) {
                    if (min == null || s.getStatDate().isBefore(min)) {
                        min = s.getStatDate();
                    }
                }
            }
            if (min == null) {
                return 1;
            }
            return (int) Math.max(1, Math.min(ChronoUnit.DAYS.between(min, today) + 1, 3660));
        }
        return 1;
    }

    /** 周期文案。由后端给，前端直接显示 —— 前端自己拼会与实际窗口漂移 */
    private static String periodLabel(LocalDate from, LocalDate to, int days) {
        String span = from.equals(to) ? from.toString() : from + " ～ " + to;
        return "统计区间 " + span + "（" + days + " 天 · 仿真）";
    }

    /**
     * 窗口内每个资源点的**平均承载率**（0–1）。
     *
     * <p>原来的 {@code todayStats} 取的是基准日那一行 —— 单日口径下够用，
     * 但多天窗口里"承载率"必须是一段均值，否则"近 7 日的区县冷热"会变成
     * "第 7 天那一天的样子"，与旁边写着"近 7 日"的数字对不上。
     */
    private Map<String, Double> avgUsageByPoi(RuleEngine.Context ctx, LocalDate end, int days) {
        LocalDate from = end.minusDays(days - 1L);
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, List<PoiVisitStat>> e : ctx.seriesByPoi().entrySet()) {
            double sum = 0;
            int n = 0;
            for (PoiVisitStat s : e.getValue()) {
                LocalDate d = s.getStatDate();
                if (d.isBefore(from) || d.isAfter(end) || s.getCapacityUsage() == null) {
                    continue;
                }
                sum += s.getCapacityUsage().doubleValue();
                n++;
            }
            if (n > 0) {
                out.put(e.getKey(), sum / n);
            }
        }
        return out;
    }

    /**
     * 大屏右侧只展示 5 条，挑法有讲究。
     *
     * <p>若只按等级排序，前 5 条会被"景区超载"占满 —— 而「乡村闲置」
     * 才是本项目的核心机制，却反而看不见。做法：先取 2 条最高优先级，
     * 再按类型轮转补齐，保证不同类型都能露脸，最后用剩下的填满。
     */
    private List<RiskEventVO> pickSnapshotRisks(List<RiskEvent> events, Map<String, RiskRule> rules) {
        List<RiskEvent> sorted = events.stream()
                .filter(e -> !RiskEvent.STATUS_CLOSED.equals(e.getStatus()))
                .sorted(Comparator
                        .comparing((RiskEvent e) -> RiskEvent.LEVEL_HIGH.equals(e.getLevel()) ? 0 : 1)
                        .thenComparing(RiskEvent::getMetricValue, Comparator.reverseOrder()))
                .toList();

        List<RiskEvent> shown = new ArrayList<>(sorted.stream().limit(2).toList());
        Set<String> seenTypes = shown.stream().map(e -> typeOf(rules, e)).collect(Collectors.toSet());
        for (RiskEvent e : sorted.subList(Math.min(2, sorted.size()), sorted.size())) {
            if (shown.size() >= SNAPSHOT_RISK_LIMIT) {
                break;
            }
            if (seenTypes.add(typeOf(rules, e))) {
                shown.add(e);
            }
        }
        for (RiskEvent e : sorted) {
            if (shown.size() >= SNAPSHOT_RISK_LIMIT) {
                break;
            }
            if (!shown.contains(e)) {
                shown.add(e);
            }
        }
        return shown.stream().map(e -> toVO(e, rules)).toList();
    }

    // ==================================================================
    // 风险事件
    // ==================================================================

    @Override
    public List<RiskEventVO> listRisks(String status, String level, String district) {
        LambdaQueryWrapper<RiskEvent> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            q.eq(RiskEvent::getStatus, status);
        }
        if (StringUtils.hasText(level)) {
            q.eq(RiskEvent::getLevel, level);
        }
        if (StringUtils.hasText(district)) {
            q.eq(RiskEvent::getDistrict, district);
        }
        // 一级在前、同日按指标值降序：运营打开列表第一眼看到的就是最该处理的
        q.orderByAsc(RiskEvent::getLevel).orderByDesc(RiskEvent::getStatDate).orderByDesc(RiskEvent::getMetricValue);

        Map<String, RiskRule> rules = rulesById();
        List<RiskEventVO> vos = riskEventMapper.selectList(q).stream().map(e -> toVO(e, rules)).toList();
        attachCandidates(vos, rules);
        return vos;
    }

    @Override
    public RiskEventVO getRisk(Long id) {
        RiskEvent e = riskEventMapper.selectById(id);
        if (e == null) {
            throw new BizException(ErrorCode.RISK_NOT_FOUND);
        }
        Map<String, RiskRule> rules = rulesById();
        RiskEventVO vo = toVO(e, rules);
        attachCandidates(List.of(vo), rules);
        return vo;
    }

    @Override
    public List<DiversionCandidateVO> candidatesOf(Long riskEventId) {
        RiskEvent e = riskEventMapper.selectById(riskEventId);
        if (e == null) {
            throw new BizException(ErrorCode.RISK_NOT_FOUND);
        }
        Map<String, RiskRule> rules = rulesById();
        RiskEventVO vo = toVO(e, rules);
        attachCandidates(List.of(vo), rules);
        return vo.getCandidates() == null ? List.of() : vo.getCandidates();
    }

    /**
     * 给"处置动作是分流"的事件补上候选点（规则 1 / 规则 4）。
     *
     * <p>两条规则的 {@code action_type} 都是 {@code DIVERSION}，其余是
     * 曝光 / 服务 / 关注 —— 那些没有"换一个去处"的语义，不该出现候选列表。
     *
     * <p><b>一次装数据、循环里算：</b>{@code loadContext} 要读全城 60 天的客流
     * （约 250KB），放在循环里就是 17 次全表读。所以先判断"有没有分流事件"，
     * 没有就直接返回，不做任何查询 —— 风险列表最常被筛到只剩几条非分流事件。
     */
    private void attachCandidates(List<RiskEventVO> vos, Map<String, RiskRule> rules) {
        List<RiskEventVO> targets = vos.stream()
                .filter(v -> {
                    RiskRule r = rules.get(v.getRuleId());
                    return r != null && WorkOrder.TYPE_DIVERSION.equals(r.getActionType());
                })
                .toList();
        if (targets.isEmpty()) {
            return;
        }

        RuleEngine.Context ctx = loadContext(props.getCity());
        if (ctx.today() == null) {
            // 没有客流数据就没有承载，候选一个都算不出来。
            // 给空列表而不是留 null：前端拿到 null 会以为是"这条事件不涉及分流"
            for (RiskEventVO v : targets) {
                v.setCandidates(List.of());
            }
            return;
        }
        Map<String, BigDecimal> usageByPoi = usageByPoi(ctx);
        for (RiskEventVO v : targets) {
            Poi from = ctx.pois().get(v.getPoiId());
            List<DiversionAdvisor.Candidate> cs = DiversionAdvisor.advise(from, ctx.pois(), usageByPoi);
            v.setCandidates(cs.stream().map(c -> toCandidateVO(c, usageByPoi.get(c.poiId()))).toList());
        }
    }

    /** 基准日各资源点的承载占用率，按 poi_id 索引 */
    private Map<String, BigDecimal> usageByPoi(RuleEngine.Context ctx) {
        Map<String, BigDecimal> out = new HashMap<>();
        todayStats(ctx).forEach((poiId, s) -> {
            if (s.getCapacityUsage() != null) {
                out.put(poiId, s.getCapacityUsage());
            }
        });
        return out;
    }

    /** 领域候选（给工单建议用）。与 {@code attachCandidates} 走同一个纯函数 */
    private List<DiversionAdvisor.Candidate> rawCandidates(RiskEvent event) {
        RuleEngine.Context ctx = loadContext(props.getCity());
        if (ctx.today() == null) {
            return List.of();
        }
        return DiversionAdvisor.advise(ctx.pois().get(event.getPoiId()), ctx.pois(), usageByPoi(ctx));
    }

    /**
     * 工单的默认处置建议 = 规则内置文案 + **具体候选点**。
     *
     * <p><b>这是本轮补的那个缺口。</b>在此之前 {@code event.suggestion} 是
     * 规则里的模板文案（"同步向未入园游客推送周边乡村替代方案"），
     * 一个具体点位名都没有 —— 运营打开工单得自己想"到底往哪分流"。
     * 拼上候选之后，工单从"一句话原则"变成"一份可审的方案"：
     * 运营的工作从"自己想"变成"审一个方案"，这是质变。
     *
     * <p>只有处置动作是 {@code DIVERSION} 的规则才拼。曝光 / 服务 / 关注类的
     * 工单没有"换一个去处"的语义，硬塞候选会让人以为要去分流。
     *
     * <p>候选算不出来时（周边没有可承接的点、或没有客流数据）退回原模板文案 ——
     * 拼一句"建议分流至：（空）"比不拼更糟。
     */
    private String defaultSuggestion(RiskEvent event, RiskRule rule) {
        String base = event.getSuggestion();
        if (rule == null || !WorkOrder.TYPE_DIVERSION.equals(rule.getActionType())) {
            return base;
        }
        List<DiversionAdvisor.Candidate> candidates = rawCandidates(event);
        if (candidates.isEmpty()) {
            return base;
        }
        return base + " ｜ " + DiversionAdvisor.summary(candidates);
    }

    /**
     * 候选 → VO。
     *
     * <p>这里快照与当前**是同一个数**：运营是在"此刻"打开事件的，
     * 按此刻的承载算出来的方案就是此刻的方案，两者没有区别。
     * 两个字段真正分开是在**公告**上 —— 公告发布之后承载还会变，
     * 那时"发布时 23% / 当前 91%"才是两组不同的数。见 {@link DiversionCandidateVO}。
     */
    private DiversionCandidateVO toCandidateVO(DiversionAdvisor.Candidate c, BigDecimal currentUsage) {
        DiversionCandidateVO vo = new DiversionCandidateVO();
        vo.setPoiId(c.poiId());
        vo.setName(c.name());
        vo.setBusinessType(c.businessType());
        vo.setDistrict(c.district());
        vo.setKm(c.km());
        vo.setUsage(VoUtils.toDouble(c.usage()));
        vo.setSimilarity(c.similarity());
        vo.setReason(c.reason());
        vo.setCurrentUsage(VoUtils.toDouble(currentUsage));
        // 承载读不到时 available=false：无法确认"现在宽裕"，就不该让游客跑一趟
        vo.setAvailable(currentUsage != null
                && currentUsage.doubleValue() < DiversionAdvisor.usageBar());
        return vo;
    }

    // ==================================================================
    // 工单
    // ==================================================================

    @Override
    public List<WorkOrderVO> listWorkOrders(String status) {
        LambdaQueryWrapper<WorkOrder> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            q.eq(WorkOrder::getStatus, status);
        }
        q.orderByDesc(WorkOrder::getCreatedAt).orderByDesc(WorkOrder::getId);

        List<WorkOrder> orders = workOrderMapper.selectList(q);
        Map<Long, RiskEvent> events = orders.stream()
                .map(WorkOrder::getRiskEventId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(riskEventMapper::selectById)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(RiskEvent::getId, e -> e, (a, b) -> a));

        return orders.stream().map(o -> toVO(o, events.get(o.getRiskEventId()))).toList();
    }

    @Override
    @Transactional
    public WorkOrderVO createWorkOrder(Long riskEventId, String assignee, String suggestion) {
        RiskEvent event = riskEventMapper.selectById(riskEventId);
        if (event == null) {
            throw new BizException(ErrorCode.RISK_NOT_FOUND);
        }
        if (event.getWorkOrderId() != null) {
            // 明确报错而不是返回已有那张：静默返回会让运营以为又建了一张，
            // 而列表里仍然只有一张，看着像"建单没成功"
            throw new BizException(ErrorCode.WORK_ORDER_EXISTS);
        }

        RiskRule rule = rulesById().get(event.getRuleId());

        WorkOrder o = new WorkOrder();
        o.setCode(nextWorkOrderCode());
        o.setRiskEventId(event.getId());
        o.setTitle(event.getTitle());
        o.setType(RuleEngine.workOrderType(rule));
        o.setLevel(event.getLevel());
        o.setStatus(WorkOrder.STATUS_PENDING);
        o.setAssignee(StringUtils.hasText(assignee) ? assignee : null);
        // 默认带出事件的建议；运营可以改写 —— 所以工单上的这份是独立存储的
        o.setSuggestion(StringUtils.hasText(suggestion) ? suggestion : defaultSuggestion(event, rule));
        workOrderMapper.insert(o);

        event.setWorkOrderId(o.getId());
        event.setStatus(RiskEvent.STATUS_HANDLED);
        riskEventMapper.updateById(event);

        // 回读：created_at 由列默认值生成，不回读则该字段整个缺失
        WorkOrder saved = workOrderMapper.selectById(o.getId());
        log.info("[M5] 建单完成 code={} 来源事件={} 类型={}", saved.getCode(), event.getId(), saved.getType());
        return toVO(saved, event);
    }

    @Override
    @Transactional
    public WorkOrderVO updateWorkOrder(Long id, String status, String assignee, String result) {
        WorkOrder o = workOrderMapper.selectById(id);
        if (o == null) {
            throw new BizException(ErrorCode.WORK_ORDER_NOT_FOUND);
        }
        if (StringUtils.hasText(status)) {
            if (!WorkOrder.STATUS_PROCESSING.equals(status) && !WorkOrder.STATUS_DONE.equals(status)) {
                throw new BizException(ErrorCode.WORK_ORDER_STATUS_INVALID);
            }
            if (WorkOrder.STATUS_DONE.equals(status) && !StringUtils.hasText(result)
                    && !StringUtils.hasText(o.getResult())) {
                // 完结必须留下处置说明：没有说明的"已完结"等于把工单关掉什么都没做，
                // 而这恰恰是这个模块要避免的"管控靠经验"
                throw new BizException(ErrorCode.WORK_ORDER_RESULT_REQUIRED);
            }
            o.setStatus(status);
            if (WorkOrder.STATUS_DONE.equals(status) && o.getHandledAt() == null) {
                o.setHandledAt(LocalDateTime.now());
            }
        }
        if (StringUtils.hasText(assignee)) {
            o.setAssignee(assignee);
        }
        if (StringUtils.hasText(result)) {
            o.setResult(result);
        }
        workOrderMapper.updateById(o);

        // 工单完结时把来源事件一起闭环 —— 否则大屏的"未处置事件数"永远降不下来，
        // 而运营明明已经把单处理完了
        RiskEvent event = o.getRiskEventId() == null ? null : riskEventMapper.selectById(o.getRiskEventId());
        if (event != null && WorkOrder.STATUS_DONE.equals(o.getStatus())) {
            event.setStatus(RiskEvent.STATUS_CLOSED);
            riskEventMapper.updateById(event);
        }

        WorkOrder saved = workOrderMapper.selectById(id);
        return toVO(saved, event);
    }

    /**
     * 生成工单编码 {@code WO-yyyyMMdd-NNN}。
     *
     * <p>序号按"当天已有多少张单 + 1"算。单机单用户演示场景下够用；
     * 多实例并发时会撞号 —— 那时应改用数据库序列或唯一键重试。
     * 这里写下来是因为它是个**已知的边界**，不是没想到。
     */
    private String nextWorkOrderCode() {
        String day = LocalDate.now().format(CODE_DAY);
        String prefix = "WO-" + day + "-";
        Long used = workOrderMapper.selectCount(
                new LambdaQueryWrapper<WorkOrder>().likeRight(WorkOrder::getCode, prefix));
        return prefix + String.format("%03d", (used == null ? 0 : used) + 1);
    }

    // ==================================================================
    // 游客端：资源点统计
    // ==================================================================

    @Override
    public List<PoiStatVO> listPoiStats() {
        String cityCode = props.getCity();
        RuleEngine.Context ctx = loadContext(cityCode);
        if (ctx.today() == null) {
            return List.of();
        }
        Map<String, PoiVisitStat> todayStat = todayStats(ctx);

        List<PoiStatVO> out = new ArrayList<>();
        for (Map.Entry<String, List<PoiVisitStat>> e : ctx.seriesByPoi().entrySet()) {
            PoiStatVO vo = new PoiStatVO();
            vo.setPoiId(e.getKey());
            PoiVisitStat t = todayStat.get(e.getKey());
            if (t == null) {
                vo.setHasData(false);
                vo.setCapacityUsage(0.0);
                vo.setTodayVisitors(0);
                vo.setHigh(false);
                vo.setWeekVisitors(List.of());
                out.add(vo);
                continue;
            }
            double usage = t.getCapacityUsage().doubleValue();
            vo.setHasData(true);
            vo.setCapacityUsage(usage);
            vo.setTodayVisitors(t.getVisitors());
            vo.setHigh(usage >= HIGH_USAGE);

            // 近 7 日不含今天：今天的值已经单独给了，重复计入会让迷你柱图
            // 最后两根一样高，看着像数据错了
            List<Integer> week = new ArrayList<>();
            for (int i = TREND_DAYS; i >= 1; i--) {
                LocalDate d = ctx.today().minusDays(i);
                week.add((int) sumWindow(e.getValue(), d, 1, PoiVisitStat::getVisitors));
            }
            vo.setWeekVisitors(week);
            out.add(vo);
        }
        return out;
    }

    // ==================================================================
    // 内部：数据装载与组装
    // ==================================================================

    /**
     * 装载一次扫描所需的全部数据。
     *
     * <p>一次性全读进内存（60 天 × 42 点 = 2520 行，约 250KB）而不是逐条查库：
     * 规则要反复做窗口求和，每条规则每次去查库会变成几百次往返，
     * 而这个数据量在内存里遍历是微秒级的。数据包规模再大一个数量级也撑得住。
     */
    private RuleEngine.Context loadContext(String cityCode) {
        Map<String, RiskRule> rules = rulesById();

        Map<String, Poi> pois = poiMapper.selectList(
                new LambdaQueryWrapper<Poi>().eq(Poi::getCityCode, cityCode)).stream()
                .collect(Collectors.toMap(Poi::getId, p -> p, (a, b) -> a, LinkedHashMap::new));

        List<PoiVisitStat> stats = poiVisitStatMapper.selectList(
                new LambdaQueryWrapper<PoiVisitStat>()
                        .eq(PoiVisitStat::getCityCode, cityCode)
                        .orderByAsc(PoiVisitStat::getStatDate));

        Map<String, List<PoiVisitStat>> series = new LinkedHashMap<>();
        LocalDate today = null;
        for (PoiVisitStat s : stats) {
            series.computeIfAbsent(s.getPoiId(), k -> new ArrayList<>()).add(s);
            if (today == null || s.getStatDate().isAfter(today)) {
                today = s.getStatDate();
            }
        }
        return new RuleEngine.Context(rules, pois, series, today);
    }

    private Map<String, RiskRule> rulesById() {
        return riskRuleMapper.selectList(new LambdaQueryWrapper<RiskRule>().eq(RiskRule::getEnabled, true)).stream()
                .collect(Collectors.toMap(RiskRule::getRuleId, r -> r, (a, b) -> a, LinkedHashMap::new));
    }

    /** 基准日那一行，按 poi_id 索引 */
    private Map<String, PoiVisitStat> todayStats(RuleEngine.Context ctx) {
        Map<String, PoiVisitStat> out = new HashMap<>();
        for (Map.Entry<String, List<PoiVisitStat>> e : ctx.seriesByPoi().entrySet()) {
            for (PoiVisitStat s : e.getValue()) {
                if (ctx.today().equals(s.getStatDate())) {
                    out.put(e.getKey(), s);
                    break;
                }
            }
        }
        return out;
    }

    private boolean isType(Map<String, Poi> pois, String poiId, String type) {
        Poi p = pois.get(poiId);
        return p != null && type.equals(p.getBusinessType());
    }

    /** 某一天、某业态全部资源点的某个字段合计 */
    private long sumWindowAll(Map<String, List<PoiVisitStat>> seriesByPoi, LocalDate end, int days,
            Function<PoiVisitStat, Integer> field, Map<String, Poi> pois, String type) {
        long total = 0;
        for (Map.Entry<String, List<PoiVisitStat>> e : seriesByPoi.entrySet()) {
            if (!isType(pois, e.getKey(), type)) {
                continue;
            }
            total += sumWindow(e.getValue(), end, days, field);
        }
        return total;
    }

    /** 以 end 结尾、长度 days 的闭区间窗口求和。与 RuleEngine 同一口径 */
    private long sumWindow(List<PoiVisitStat> series, LocalDate end, int days,
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

    /** 各区县的景区 / 乡村平均承载（百分比数值）。入参是**窗口平均**承载率，见 avgUsageByPoi */
    private List<OpsSnapshotVO.DistrictImbalance> imbalance(RuleEngine.Context ctx,
            Map<String, Double> usageAvg) {
        Map<String, List<Double>> scenic = new LinkedHashMap<>();
        Map<String, List<Double>> rural = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : usageAvg.entrySet()) {
            Poi p = ctx.pois().get(e.getKey());
            if (p == null || p.getDistrict() == null) {
                continue;
            }
            double u = e.getValue();
            if (TYPE_SCENIC.equals(p.getBusinessType())) {
                scenic.computeIfAbsent(p.getDistrict(), k -> new ArrayList<>()).add(u);
            } else if (TYPE_RURAL.equals(p.getBusinessType())) {
                rural.computeIfAbsent(p.getDistrict(), k -> new ArrayList<>()).add(u);
            }
        }

        List<OpsSnapshotVO.DistrictImbalance> out = new ArrayList<>();
        for (String d : scenic.keySet()) {
            // 只有既有景区又有乡村的区县才谈得上"冷热失衡"
            if (!rural.containsKey(d)) {
                continue;
            }
            OpsSnapshotVO.DistrictImbalance row = new OpsSnapshotVO.DistrictImbalance();
            row.setName(d);
            row.setScenic((int) Math.round(avg(scenic.get(d)) * 100));
            row.setRural((int) Math.round(avg(rural.get(d)) * 100));
            out.add(row);
        }
        out.sort(Comparator.comparing(OpsSnapshotVO.DistrictImbalance::getScenic).reversed());
        return out;
    }

    /** 乡村点 -> 关联产品均价。产品挂在 poi_id（产地）上，按产地分组取均价 */
    private Map<String, BigDecimal> avgPriceByPoi(String cityCode) {
        List<Product> products = productMapper.selectList(
                new LambdaQueryWrapper<Product>().eq(Product::getCityCode, cityCode));
        Map<String, List<BigDecimal>> byPoi = new HashMap<>();
        for (Product p : products) {
            if (p.getPoiId() == null || p.getPrice() == null) {
                continue;
            }
            byPoi.computeIfAbsent(p.getPoiId(), k -> new ArrayList<>()).add(p.getPrice());
        }
        Map<String, BigDecimal> out = new HashMap<>();
        byPoi.forEach((poiId, prices) -> {
            BigDecimal sum = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            out.put(poiId, sum.divide(BigDecimal.valueOf(prices.size()), 2, RoundingMode.HALF_UP));
        });
        return out;
    }

    private RiskEventVO toVO(RiskEvent e, Map<String, RiskRule> rules) {
        RiskEventVO vo = new RiskEventVO();
        vo.setId(e.getId());
        vo.setRuleId(e.getRuleId());
        RiskRule rule = rules.get(e.getRuleId());
        vo.setType(rule != null ? rule.getType() : e.getRuleId());
        vo.setLevel(e.getLevel());
        vo.setTitle(e.getTitle());
        vo.setPoiId(e.getPoiId());
        vo.setPoiName(e.getPoiName());
        vo.setDistrict(e.getDistrict());
        vo.setStatDate(e.getStatDate());
        vo.setMetricValue(e.getMetricValue());
        vo.setThreshold(e.getThreshold());
        vo.setDetail(e.getDetail());
        vo.setSuggestion(e.getSuggestion());
        vo.setStatus(e.getStatus());
        vo.setWorkOrderId(e.getWorkOrderId());
        vo.setCreatedAt(e.getCreatedAt());
        return vo;
    }

    private String typeOf(Map<String, RiskRule> rules, RiskEvent e) {
        RiskRule rule = rules.get(e.getRuleId());
        return rule != null ? rule.getType() : e.getRuleId();
    }

    private WorkOrderVO toVO(WorkOrder o, RiskEvent source) {
        WorkOrderVO vo = new WorkOrderVO();
        vo.setId(o.getId());
        vo.setCode(o.getCode());
        vo.setRiskEventId(o.getRiskEventId());
        if (source != null) {
            vo.setRiskRuleId(source.getRuleId());
            vo.setRiskPoiName(source.getPoiName());
            vo.setRiskStatDate(source.getStatDate());
        }
        vo.setTitle(o.getTitle());
        vo.setType(o.getType());
        vo.setLevel(o.getLevel());
        vo.setStatus(o.getStatus());
        vo.setAssignee(o.getAssignee());
        vo.setSuggestion(o.getSuggestion());
        vo.setResult(o.getResult());
        vo.setHandledAt(o.getHandledAt());
        vo.setCreatedAt(o.getCreatedAt());
        return vo;
    }

    private static OpsSnapshotVO.MixSlice mix(String name, int value) {
        OpsSnapshotVO.MixSlice m = new OpsSnapshotVO.MixSlice();
        m.setName(name);
        m.setValue(value);
        return m;
    }

    private static double avg(List<Double> values) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0 : round4((double) numerator / denominator);
    }

    /** 环比变化，百分比数值。上期为 0 时返回 0 而不是无穷大 */
    private static double pctChange(long cur, long prev) {
        if (prev <= 0) {
            return 0;
        }
        return round1((double) (cur - prev) / prev * 100);
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String weekdayCn(DayOfWeek dow) {
        return WEEKDAY_CN[dow.getValue() - 1];
    }
}
