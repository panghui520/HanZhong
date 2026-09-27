package com.hanyou.brain.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.hanyou.brain.entity.RiskEvent;
import com.hanyou.brain.entity.RiskRule;
import com.hanyou.brain.entity.WorkOrder;
import com.hanyou.brain.mapper.PoiMapper;
import com.hanyou.brain.mapper.PoiVisitStatMapper;
import com.hanyou.brain.mapper.ProductMapper;
import com.hanyou.brain.mapper.RiskEventMapper;
import com.hanyou.brain.mapper.RiskRuleMapper;
import com.hanyou.brain.mapper.WorkOrderMapper;
import com.hanyou.brain.service.OpsService;
import com.hanyou.brain.service.support.DiversionAdvisor;
import com.hanyou.brain.service.support.RuleEngine;
import com.hanyou.brain.vo.DiversionCandidateVO;
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

    /** 大屏趋势图的天数 */
    private static final int TREND_DAYS = 7;

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
        String cityCode = props.getCity();
        RuleEngine.Context ctx = loadContext(cityCode);

        OpsSnapshotVO vo = new OpsSnapshotVO();
        vo.setPeriodLabel("客流为当日 · 趋势与消费为近 " + TREND_DAYS + " 日（仿真）");
        vo.setSynthetic(true);
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
        Map<String, PoiVisitStat> todayStat = todayStats(ctx);

        // ---- 当日到访：按业态汇总 ----
        int scenicVisitors = sumToday(todayStat, pois, TYPE_SCENIC);
        int ruralVisitors = sumToday(todayStat, pois, TYPE_RURAL);
        vo.setTotalVisitors(scenicVisitors);
        vo.setRuralVisitors(ruralVisitors);
        vo.setRuralRatio(ratio(ruralVisitors, ruralVisitors + scenicVisitors));

        vo.setMix(List.of(
                mix("核心景区", scenicVisitors),
                mix("乡村旅游", ruralVisitors),
                mix("餐饮", sumToday(todayStat, pois, TYPE_FOOD)),
                mix("住宿", sumToday(todayStat, pois, TYPE_LODGING))));

        // ---- 区县冷热对比 ----
        List<OpsSnapshotVO.DistrictImbalance> imbalance = imbalance(ctx, todayStat);
        vo.setImbalance(imbalance);
        vo.setIdleDistricts(imbalance.stream()
                .filter(r -> r.getScenic() > HIGH_USAGE * 100 && r.getRural() < 20)
                .map(OpsSnapshotVO.DistrictImbalance::getName)
                .toList());

        // ---- 承载相关 ----
        List<PoiVisitStat> scenics = todayStat.values().stream()
                .filter(s -> isType(pois, s.getPoiId(), TYPE_SCENIC))
                .toList();
        vo.setHotScenicCount((int) scenics.stream()
                .filter(s -> s.getCapacityUsage().doubleValue() >= HIGH_USAGE)
                .count());
        List<PoiVisitStat> rurals = todayStat.values().stream()
                .filter(s -> isType(pois, s.getPoiId(), TYPE_RURAL))
                .toList();
        vo.setRuralAvgUsage(rurals.isEmpty() ? 0.0
                : rurals.stream().mapToDouble(s -> s.getCapacityUsage().doubleValue()).average().orElse(0));

        // ---- 趋势：近 7 日的核心景区客流与整体承载 ----
        BigDecimal scenicCapacity = BigDecimal.ZERO;
        for (Poi p : pois.values()) {
            if (TYPE_SCENIC.equals(p.getBusinessType()) && p.getCapacity() != null) {
                scenicCapacity = scenicCapacity.add(BigDecimal.valueOf(p.getCapacity()));
            }
        }
        List<OpsSnapshotVO.TrendPoint> trend = new ArrayList<>();
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long visitors = sumWindowAll(ctx.seriesByPoi(), d, 1, PoiVisitStat::getVisitors, pois, TYPE_SCENIC);
            OpsSnapshotVO.TrendPoint p = new OpsSnapshotVO.TrendPoint();
            p.setDate(weekdayCn(d.getDayOfWeek()));
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
        for (Poi p : pois.values()) {
            if (!TYPE_RURAL.equals(p.getBusinessType())) {
                continue;
            }
            List<PoiVisitStat> series = ctx.seriesByPoi().getOrDefault(p.getId(), List.of());
            long buys = sumWindow(series, today, TREND_DAYS, PoiVisitStat::getPurchases);
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

        // ---- 复购率：近 7 日复购笔数 / 购买笔数（乡村点）----
        long buysCur = 0;
        long repsCur = 0;
        long buysPrev = 0;
        long repsPrev = 0;
        for (Poi p : pois.values()) {
            if (!TYPE_RURAL.equals(p.getBusinessType())) {
                continue;
            }
            List<PoiVisitStat> series = ctx.seriesByPoi().getOrDefault(p.getId(), List.of());
            buysCur += sumWindow(series, today, TREND_DAYS, PoiVisitStat::getPurchases);
            repsCur += sumWindow(series, today, TREND_DAYS, PoiVisitStat::getRepurchases);
            buysPrev += sumWindow(series, today.minusDays(TREND_DAYS), TREND_DAYS, PoiVisitStat::getPurchases);
            repsPrev += sumWindow(series, today.minusDays(TREND_DAYS), TREND_DAYS, PoiVisitStat::getRepurchases);
        }
        double repurchaseRate = ratio(repsCur, buysCur);
        vo.setRepurchaseRate(repurchaseRate);

        // ---- 风险 ----
        List<RiskEvent> allEvents = riskEventMapper.selectList(
                new LambdaQueryWrapper<RiskEvent>().orderByDesc(RiskEvent::getStatDate));
        vo.setOpenRisks((int) allEvents.stream()
                .filter(e -> !RiskEvent.STATUS_CLOSED.equals(e.getStatus()))
                .count());
        Map<String, RiskRule> rules = ctx.rules();
        vo.setRisks(pickSnapshotRisks(allEvents, rules));

        // ---- 环比 ----
        long visitorsCur = sumWindowAll(ctx.seriesByPoi(), today, TREND_DAYS, PoiVisitStat::getVisitors, pois, TYPE_SCENIC);
        long visitorsPrev = sumWindowAll(ctx.seriesByPoi(), today.minusDays(TREND_DAYS), TREND_DAYS,
                PoiVisitStat::getVisitors, pois, TYPE_SCENIC);
        long ruralCur = sumWindowAll(ctx.seriesByPoi(), today, TREND_DAYS, PoiVisitStat::getVisitors, pois, TYPE_RURAL);
        long ruralPrev = sumWindowAll(ctx.seriesByPoi(), today.minusDays(TREND_DAYS), TREND_DAYS,
                PoiVisitStat::getVisitors, pois, TYPE_RURAL);

        OpsSnapshotVO.Deltas d = new OpsSnapshotVO.Deltas();
        d.setVisitorsPct(pctChange(visitorsCur, visitorsPrev));
        d.setRuralRatioPt(round1((ratio(ruralCur, ruralCur + visitorsCur)
                - ratio(ruralPrev, ruralPrev + visitorsPrev)) * 100));
        d.setSalesPct(pctChange(buysCur, buysPrev));
        d.setRepurchasePt(round1((repurchaseRate - ratio(repsPrev, buysPrev)) * 100));
        vo.setDeltas(d);

        return vo;
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

    private int sumToday(Map<String, PoiVisitStat> todayStat, Map<String, Poi> pois, String type) {
        int total = 0;
        for (Map.Entry<String, PoiVisitStat> e : todayStat.entrySet()) {
            if (isType(pois, e.getKey(), type)) {
                total += e.getValue().getVisitors() == null ? 0 : e.getValue().getVisitors();
            }
        }
        return total;
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

    /** 各区县的景区 / 乡村平均承载（百分比数值） */
    private List<OpsSnapshotVO.DistrictImbalance> imbalance(RuleEngine.Context ctx,
            Map<String, PoiVisitStat> todayStat) {
        Map<String, List<Double>> scenic = new LinkedHashMap<>();
        Map<String, List<Double>> rural = new LinkedHashMap<>();
        for (Map.Entry<String, PoiVisitStat> e : todayStat.entrySet()) {
            Poi p = ctx.pois().get(e.getKey());
            if (p == null || p.getDistrict() == null) {
                continue;
            }
            double u = e.getValue().getCapacityUsage().doubleValue();
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
