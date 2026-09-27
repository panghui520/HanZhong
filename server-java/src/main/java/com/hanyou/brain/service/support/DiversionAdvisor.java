package com.hanyou.brain.service.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hanyou.brain.common.GeoUtils;
import com.hanyou.brain.common.VoUtils;
import com.hanyou.brain.entity.Poi;

/**
 * 分流候选推荐（M5 续）。
 *
 * <p><b>它补的是 M5 缺的那一段。</b>M5 原来的链路是"规则命中 → 风险事件 → 工单"，
 * 但工单上的建议是**写死的模板**（"同步向未入园游客推送周边乡村替代方案"），
 * 一个具体点位名都没有 —— 运营打开工单得自己想"到底往哪分流"。
 * 本类把"往哪分流"算出来：给一个溢出的资源点，返回 2–3 个**具体的**候选点，
 * 每个都带距离、当前承载、相似度和一句理由。
 *
 * <p><b>★ 这是"判定"那一半，与"生成/解释"严格分开。</b>
 * 与 {@link RuleEngine} 同一条架构原则（方案第 14 节"确定性 → 概率性，不可颠倒"）：
 * 挑候选全程是确定性计算，**没有一步经过模型**。LLM 只负责把候选讲成人话（M7）。
 * 这样做答辩时能答"为什么推荐 B"—— 当场把距离、承载、相似度三个数指出来。
 *
 * <p><b>本类不碰数据库、不碰 Spring。</b>输入是值对象，输出是一个 List。
 * 与 RuleEngine 同一理由：候选打分是这套机制里最需要反复推演的部分
 * （权重调一调、半径改一改），做成纯函数就能离线喂一批数据直接看排序。
 *
 * <h3>候选池为什么从坐标直接算，而不是读 {@code poi_relation}</h3>
 * M1 的边不够用：{@code NEARBY} 每个点只留 6 条（15 km 内、**不分业态**），
 * 一个景区旁边若是几家餐馆和民宿，过滤到景区就一条都不剩；
 * 而 {@code DIVERSION} 只覆盖"景区 → 乡村"，给不出景区候选。
 * 更要紧的是：**"什么算可分流"是 M5 的口径，不该被 M1 的连边规则限制** ——
 * M1 哪天把 {@code NEARBY_MAX} 从 6 改成 10，分流推荐结果就会跟着变，
 * 这种隐式耦合在答辩时解释不清。所以本类自己按坐标算距离。
 * 距离公式与 M1 共用 {@link GeoUtils}，两处必须给出同一个数。
 */
public final class DiversionAdvisor {

    // ==================================================================
    // 参数（★ 全部是代码常量，不进数据库）
    //
    // 与 risk_rule 的阈值**刻意不同**的处理。阈值是运营口径（"超载线定 80% 还是 85%"），
    // 天天可能动，所以放表里。这里的几个数不是：
    //   ① 它们决定的是**推荐顺序**，不是"报不报警"，运营没有日常调它们的场景；
    //   ② 它们必须**跨环境可复现** —— 答辩时问"为什么推荐 B"，
    //      要能答出同一个数。放进可改的表里，A 机器和 B 机器的答案就不同了。
    // 先例：{@code RuleEngine.MIN_EXPERIENCE_SAMPLE} 同样是代码常量。
    // 真要做成可配的，应当连同"改了要重跑哪些验收"一起设计，不是加一行 seed。
    // ==================================================================

    /** 候选半径（km）。与 M1 的 {@code DIVERSION_KM} 取同一个值：分流候选不该比"可承接关系"更远 */
    private static final double MAX_KM = 90;

    /**
     * 候选的承载上限。**高于它不进候选** —— 一个自己都 80% 的点，
     * 推荐过去只是把拥挤搬了个地方。
     * 取值与前端详情页的分流过滤（{@code PoiDetail.vue}）保持一致：
     * 两处口径不同，就会出现"公告推荐了详情页不推荐的村子"。
     */
    private static final double USAGE_BAR = 0.75;

    /**
     * 排序分档线（0–1）：承载**过半**的候选整体后置。
     *
     * <p><b>为什么不是"纯按距离"：</b>分流的目的是"给客流找个接得住的地方"。
     * 一个 70% 的村子比一个 5% 的更近，但它离 {@link #USAGE_BAR} 只差一点，
     * 推过去大概率是"把拥挤搬了个地方"。所以先出"既近又宽裕"的，
     * 再出"近、但已经过半"的。
     *
     * <p><b>为什么是分档而不是权重：</b>0.5 这个数不来自数据，但它是一个
     * **能一句话说清的分界**（过半 = 已经不宽裕）。做成第三个权重的话，
     * 答辩被问"为什么余量占 40% 而不是 35%"就答不上来了 ——
     * 分档只回答"过半没有"，回答得完。
     */
    private static final double USAGE_HALF = 0.50;

    /** 相似度加分：同一套画面 / 同一个区县，都说明"这一趟不算白跑" */
    private static final double BONUS_SAME_SCENE = 0.20;
    private static final double BONUS_SAME_DISTRICT = 0.10;

    /** 候选配额：乡村先占 2 个名额，剩下的用景区补到 {@link #TOTAL_MAX} */
    private static final int RURAL_MAX = 2;
    private static final int TOTAL_MAX = 3;

    /** 通行时间估算速度（km/h），与 M1 的 {@code SPEED_KMH} 同值 */
    private static final double SPEED_KMH = 40;

    private static final String TYPE_SCENIC = "SCENIC";
    private static final String TYPE_RURAL = "RURAL_SPOT";

    private DiversionAdvisor() {
    }

    /**
     * 候选承载上限（0–1）。对外暴露是因为**服务层要用同一个数**判断
     * "这条公告里的候选现在还去不去得" —— 两处各写一遍 0.75，
     * 迟早出现"公告说可去、详情页说满了"。常量的值只在一个地方定义。
     */
    public static double usageBar() {
        return USAGE_BAR;
    }

    /** 候选半径（km），同样对外暴露给服务层做边界判断 */
    public static double maxKm() {
        return MAX_KM;
    }

    /**
     * 一个候选点。{@code km / usage / similarity} 全部保留下来，
     * 是为了让"为什么是它"可核对 —— 只给一个名字的推荐，运营不敢用。
     *
     * <p><b>刻意不给"综合得分"。</b>排序主键是距离（见 {@link #advise}），
     * 再挂一个不参与排序的分数在界面上，只会招来"那为什么第二个分更高却排在后面"。
     * 距离、承载、共同主题三个数各自能独立解释，不需要再合成一个。
     *
     * @param usage 当日承载占用率（0–1）。**进入候选的必定非空**：
     *              承载读不到的点不进列表，见 {@link #advise}
     * @param similarity 相似度（0–1）。只作**展示与解释**用，不参与排序：
     *                   它由 {@link #similarityOf} 算，用来在理由里点出共同主题
     */
    public record Candidate(
            String poiId,
            String name,
            String businessType,
            String district,
            double km,
            BigDecimal usage,
            double similarity,
            String reason) {

        /** 乡村候选排前面 —— 前端按这个分组展示，不靠猜 businessType */
        public boolean rural() {
            return TYPE_RURAL.equals(businessType);
        }
    }

    /**
     * 算候选。
     *
     * <p><b>排序规则（运营定的口径，两层）：</b>
     * <ol>
     *   <li><b>分组</b>：乡村组在前、景区组在后。两者的**叙事不同** ——
     *       乡村承接是"把客流导向乡村"（本项目的立意），景区承接只是"换个地方看"。
     *       混排会让一个顺路的村子被一个更近的景区挤掉。</li>
     *   <li><b>组内</b>：承载未过半的在前，然后**按距离升序**。</li>
     * </ol>
     * 合起来就是"先给最近的、还接得住的乡村；再给最近的、还接得住的景区"。
     *
     * <p><b>为什么组内是距离，而不是一个加权总分：</b>游客真正问的是"我去哪儿"，
     * 距离是唯一能当场验证、也最影响决定的那个数。加权总分看着"科学"，
     * 但它排出来的顺序解释不了 —— 一个 57 km 的点排在一个 42 km 的点前面，
     * 运营的第一反应是系统坏了。距离升序没有这个问题。
     * 承载改用**分档**参与（过半 / 未过半），理由见 {@link #USAGE_HALF}。
     *
     * @param from        溢出的资源点（A 点）。实际调用里恒为景区：
     *                    规则 1 与规则 4 都锚在一个景区上
     * @param pois        该城市全部资源点，按 id 索引
     * @param usageByPoi  各资源点当日承载占用率。**缺失表示读不到**，不是 0
     * @return 候选，最多 {@link #TOTAL_MAX} 个；一个都没有时返回空列表（不是 null）
     */
    public static List<Candidate> advise(Poi from, Map<String, Poi> pois, Map<String, BigDecimal> usageByPoi) {
        if (from == null || pois == null || pois.isEmpty()) {
            return List.of();
        }
        Set<String> fromTags = new LinkedHashSet<>(VoUtils.splitTags(from.getTags()));

        List<Candidate> rural = new ArrayList<>();
        List<Candidate> scenic = new ArrayList<>();

        for (Poi p : pois.values()) {
            if (p.getId().equals(from.getId())) {
                continue;
            }
            boolean isRural = TYPE_RURAL.equals(p.getBusinessType());
            boolean isScenic = TYPE_SCENIC.equals(p.getBusinessType());
            // 只推荐"目的地"型资源。餐饮住宿不是分流目标 —— 分流是"换一个去处"，
            // 不是"换一家饭店"。与 M1 的 SUPPORT_TYPES 注释同一判断。
            if (!isRural && !isScenic) {
                continue;
            }

            BigDecimal usage = usageByPoi == null ? null : usageByPoi.get(p.getId());
            // ★ 承载读不到 → 不进候选。与前端详情页同一条口径：
            // 这块建议的说服力全在"当前宽裕"四个字上，拿一个读不到承载的点凑数，
            // 等于把一个可核对的建议变成不可核对的。
            if (usage == null) {
                continue;
            }
            // 自己都快满了，推荐过去只是把拥挤搬了个地方
            if (usage.doubleValue() >= USAGE_BAR) {
                continue;
            }

            double km = GeoUtils.haversineKm(from, p);
            if (km > MAX_KM) {
                continue;
            }

            double similarity = similarityOf(from, p, fromTags);

            Candidate c = new Candidate(
                    p.getId(), p.getName(), p.getBusinessType(), p.getDistrict(),
                    round2(km), usage, round4(similarity),
                    reasonOf(p, km, usage, fromTags));
            (isRural ? rural : scenic).add(c);
        }

        // 排序主键：① 承载是否过半（未过半的在前）② 距离升序
        //           ③ 相似度降序 ④ poiId
        // ③④ 是为了**确定性**：少了它们，同档同距的候选顺序会随 Map 的遍历顺序变化，
        // 两次刷新看到不同的推荐 —— 看着像 bug。相似度放这里，也让"风格类似"
        // 在距离完全相同时仍然说话。
        Comparator<Candidate> byNearest = Comparator
                .comparingInt((Candidate c) -> c.usage().doubleValue() >= USAGE_HALF ? 1 : 0)
                .thenComparingDouble(Candidate::km)
                .thenComparing(Comparator.comparingDouble(Candidate::similarity).reversed())
                .thenComparing(Candidate::poiId);

        rural.sort(byNearest);
        scenic.sort(byNearest);

        List<Candidate> out = new ArrayList<>();
        rural.stream().limit(RURAL_MAX).forEach(out::add);
        // 名额没用满就用景区补 —— 乡村候选可能一个都没有（周边没有村子，
        // 或村子都满了）。不补的话会出现"有 3 个空位却只推荐 1 个"。
        scenic.stream().limit(Math.max(0, TOTAL_MAX - out.size())).forEach(out::add);
        return out;
    }

    /**
     * 相似度：标签 Jaccard + 同画面 + 同区县，封顶 1.0。
     *
     * <p>用 Jaccard（交集 ÷ 并集）而不是交集个数：交集个数会偏爱标签多的点 ——
     * 一个打了 8 个标签的点跟谁都能"有 3 个共同标签"。
     * 除以并集之后，"标签多但都不搭"反而会得到低分。
     */
    private static double similarityOf(Poi from, Poi to, Set<String> fromTags) {
        Set<String> toTags = new LinkedHashSet<>(VoUtils.splitTags(to.getTags()));
        double jaccard = 0;
        if (!fromTags.isEmpty() && !toTags.isEmpty()) {
            long inter = toTags.stream().filter(fromTags::contains).count();
            long union = fromTags.size() + toTags.size() - inter;
            jaccard = union == 0 ? 0 : (double) inter / union;
        }
        double bonus = 0;
        if (from.getScene() != null && from.getScene().equals(to.getScene())) {
            bonus += BONUS_SAME_SCENE;
        }
        if (from.getDistrict() != null && from.getDistrict().equals(to.getDistrict())) {
            bonus += BONUS_SAME_DISTRICT;
        }
        return Math.min(1.0, jaccard + bonus);
    }

    /**
     * 一句可核对的理由。**不带形容词** —— "非常适合"这种话没有信息量，
     * 而"距 12.3 km、承载 23%、同「三国」主题"是运营能当场验证的。
     *
     * <p><b>没有共同主题时不说"不同"，改说它自己主打什么。</b>
     * 原先是"主题与该点不同"，但那句话在界面上的读感是"推荐了个不像的"，
     * 而实际情况常常只是"这一带没有同主题的点" —— 推荐本身没问题
     * （最近、又最空），没必要用一句否定把它说得像缺陷。
     * 说"主打「茶园、采茶」"既不给系统加戏，也让运营知道要把人送去哪。
     */
    private static String reasonOf(Poi to, double km, BigDecimal usage, Set<String> fromTags) {
        Set<String> toTags = new LinkedHashSet<>(VoUtils.splitTags(to.getTags()));
        List<String> shared = toTags.stream().filter(fromTags::contains).limit(2).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("距 ").append(round2(km)).append(" km（约 ")
                .append(Math.max(1, Math.round(km / SPEED_KMH * 60))).append(" 分钟车程）")
                .append("，当日承载 ").append(toPercent(usage));
        if (!shared.isEmpty()) {
            sb.append("，与该点同「").append(String.join("、", shared)).append("」主题");
        } else if (!toTags.isEmpty()) {
            // 没有共同标签就报它自己的主打：不硬凑"相似"，也不写成否定
            sb.append("，主打「").append(String.join("、", toTags.stream().limit(2).toList())).append("」");
        }
        return sb.toString();
    }

    /**
     * 把候选列表写成一段话，给公告正文与工单建议共用。
     *
     * <p>两条路用同一段文案是刻意的：运营在工单里看到的分流方案，
     * 与他将要发出去的公告，必须是同一份 —— 否则他会以为发出去的是别的东西。
     */
    public static String summary(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "暂无可承接的候选点（周边资源点均处于高位或缺少承载数据）";
        }
        StringBuilder sb = new StringBuilder("建议分流至：");
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            sb.append(i == 0 ? "" : "；")
                    .append("①②③".charAt(Math.min(i, 2)))
                    .append(c.name()).append("（").append(c.reason()).append("）");
        }
        return sb.toString();
    }

    private static String toPercent(BigDecimal v) {
        return Math.round(v.doubleValue() * 100) + "%";
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
