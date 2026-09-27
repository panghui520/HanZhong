package com.hanyou.brain.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.BodyReader;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.entity.Experience;
import com.hanyou.brain.entity.Poi;
import com.hanyou.brain.entity.TripCheckin;
import com.hanyou.brain.mapper.ExperienceMapper;
import com.hanyou.brain.mapper.PoiMapper;
import com.hanyou.brain.mapper.TripCheckinMapper;
import com.hanyou.brain.vo.TripCheckinVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * M6 到访消费链：足迹。
 *
 * <p><b>这个类补的是消费链缺的那一段起点。</b>在此之前系统只有"离境复购"：
 * 游客回家后在乡村好物页下单。但"离境延伸"这四个字要成立，必须能回答
 * <b>"他去过哪儿"</b> —— 否则复购推荐的输入只能是商品类目，那就退化成
 * 普通电商推荐，与创新点二说的"推荐系统的输入是体验记忆"正好相反。
 *
 * <p>足迹落库之后，"下单时判 {@code orders.channel}"成为可核对的数据事实：
 * 到访后 3 天内有对应足迹 = {@code TRIP}（到访消费），否则 = {@code REPURCHASE}
 * （离境复购）。在此之前这一列是硬编码的常量，也就是说"到访消费"这条链
 * 在数据上从未产生过一单。
 *
 * <p><b>本轮未做（都写在验收记录里，答辩时不要讲）：</b>
 * <ul>
 *   <li><b>{@code source=DIVERSION} 还没有写入方。</b>方案 §15 链路③
 *       「分流产生的到访写入 trip_checkin，大屏显示分流贡献量」需要一条
 *       "游客接受了分流建议"的记录，本轮没建那张表，所以
 *       {@code source} 当前只可能被写成 {@code REAL}（{@code SIM} 是演示种子，
 *       也还没有生成脚本）。M5 验收记录里那条"讲这一句就是假话"的标记
 *       因此**仍然有效**。</li>
 *   <li><b>复购推荐还没有消费足迹。</b>"乡村好物"页当前的取数逻辑没变，
 *       没有按足迹排序。所以"推荐系统的输入是体验记忆"这句话现在只是
 *       数据基础就位，不是已实现的功能。</li>
 * </ul>
 * 这两条都是**下一个纵切**的事，本类不预留它们的接口 ——
 * 没人调的方法与没人读的列一样，只会让"这一块到底有没有用"变成
 * 每次 review 都要重新想一遍的问题（同 V7 文件头的取舍）。
 *
 * <p><b>为什么"同一天同一目标算一次"。</b>
 * 足迹是复购推荐的输入。若点一次打卡就加一行，输入就被点击次数污染了 ——
 * 一个把页面点开五次的用户，在推荐里会显得比真去了五个点的用户"经历更丰富"。
 * 判重放在服务层而不是靠唯一键：{@code poi_id} 与 {@code experience_id}
 * 都可以为 NULL，而 MySQL 的唯一键不约束 NULL，用唯一键兜不住只打卡
 * 体验项目的那一半情况。
 *
 * <p><b>本类不提供"删除足迹"。</b>足迹是发生过的事实，用户不该能把它抹掉；
 * 而且 {@code orders.channel} 的判定依赖它 —— 允许删足迹等于允许改写
 * 历史订单的归类。演示前要清数据，走 {@code db/reset-runtime-data.sql}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripCheckinService {

    /** 用户主动打卡。本类的对外接口只会写这一个值 */
    private static final String SOURCE_REAL = "REAL";

    /**
     * 分流引导产生的到访。
     *
     * <p><b>本轮没有任何代码会写它</b>（见类注释的「本轮未做」）——
     * 这里保留常量只是为了 {@link #sourceLabel} 能把历史/演示数据里的
     * 这个值翻成中文。等分流回流那一轮接上写入方时，判定规则写在那一边。
     */
    private static final String SOURCE_DIVERSION = "DIVERSION";

    /** "我的足迹"最多返回多少条。足迹是复购推荐的输入，不是无限滚动的时间线 */
    private static final int MAX_LIMIT = 100;

    private static final int DEFAULT_LIMIT = 50;

    private final TripCheckinMapper checkinMapper;
    private final TripService tripService;
    private final PoiMapper poiMapper;
    private final ExperienceMapper experienceMapper;

    // ------------------------------------------------------------------
    // 对外：我的足迹
    // ------------------------------------------------------------------

    /**
     * 我的足迹，按到访时间倒序。
     *
     * <p>倒序而不是正序：用户打开这个页面想看到的是"最近去过哪儿"，
     * 也就是复购推荐会拿什么当输入 —— 页面上看到的顺序，应当和
     * 推荐真正取用的顺序一致，否则用户没法理解推荐为什么推这个。
     */
    @Transactional(readOnly = true)
    public List<TripCheckinVO> listMine(Long userId, Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<TripCheckin> rows = checkinMapper.selectList(Wrappers.<TripCheckin>lambdaQuery()
                .eq(TripCheckin::getUserId, userId)
                .orderByDesc(TripCheckin::getCheckinAt)
                .orderByDesc(TripCheckin::getId)
                .last("LIMIT " + size));
        return rows.stream().map(TripCheckinService::toVO).toList();
    }

    // ------------------------------------------------------------------
    // 对外：打卡
    // ------------------------------------------------------------------

    /**
     * 到访打卡。挂到"当前行程"上（没有行程就懒创建，与 M4 一致）。
     *
     * <p>请求体只取 {@code poi_id} / {@code experience_id} / {@code note} 三个键。
     * {@code source} **不从请求体读**：它是"这条足迹是怎么来的"的标注，
     * 若由客户端决定，任何人都能把一条自己点的足迹标成 {@code DIVERSION}，
     * 于是大屏上的"分流贡献量"就成了可以自己填的数 —— 那一列存在的全部意义
     * 就是它是**系统判定**出来的。所以对外接口恒写 REAL。
     *
     * <p>名称（{@code poi_name} / {@code experience_name}）在**这一刻**从库里
     * 抄一份存进足迹行，之后不再更新。数据包换一版，足迹仍然说得清当时去的是哪儿。
     */
    @Transactional
    public TripCheckinVO checkin(Long userId, Map<String, Object> body) {
        String poiId = trimToNull(BodyReader.str(body, "poi_id"));
        String experienceId = trimToNull(BodyReader.str(body, "experience_id"));
        if (poiId == null && experienceId == null) {
            throw new BizException(ErrorCode.CHECKIN_TARGET_REQUIRED);
        }

        // 校验目标真实存在，并顺手取到名称快照。
        // 查不到一律按"不存在"处理（7005），不区分"这个 id 是编的"与"这个点已下架"——
        // 对游客来说两者要做的事完全一样（刷新页面重来）。
        String poiName = null;
        if (poiId != null) {
            Poi poi = poiMapper.selectById(poiId);
            if (poi == null) {
                throw new BizException(ErrorCode.CHECKIN_TARGET_NOT_FOUND);
            }
            poiName = poi.getName();
        }
        String experienceName = null;
        if (experienceId != null) {
            Experience exp = experienceMapper.selectById(experienceId);
            if (exp == null) {
                throw new BizException(ErrorCode.CHECKIN_TARGET_NOT_FOUND);
            }
            experienceName = exp.getName();
        }

        LocalDateTime now = LocalDateTime.now();
        TripCheckin existing = findSameDay(userId, poiId, experienceId, now);
        if (existing != null) {
            // 幂等：同一天对同一个目标重复点，返回已有那条而不是新增。
            // 返回已有行（而不是报错）是刻意的 —— 用户点第二次的意图
            // 通常是"我是不是没点上"，报错会让他以为操作失败了。
            log.info("[M6] 用户 {} 今日已打卡过 {} / {}，返回已有足迹 id={}",
                    userId, poiId, experienceId, existing.getId());
            return toVO(existing);
        }

        TripCheckin row = new TripCheckin();
        row.setUserId(userId);
        row.setTripId(tripService.ensureCurrentTripId(userId));
        row.setPoiId(poiId);
        row.setPoiName(poiName);
        row.setExperienceId(experienceId);
        row.setExperienceName(experienceName);
        row.setCheckinAt(now);
        row.setSource(SOURCE_REAL);
        row.setNote(truncate(trimToNull(BodyReader.str(body, "note")), 255));
        checkinMapper.insert(row);

        log.info("[M6] 用户 {} 打卡成功：{} / {}（足迹 id={}）",
                userId, poiId, experienceId, row.getId());
        return toVO(row);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 查"今天是否已经为同一目标打过卡"。
     *
     * <p>用 {@code [今天 00:00, 明天 00:00)} 而不是 {@code NOW() - 24h}：
     * 判重口径是**自然日**，与用户的理解一致（"今天来过"）。
     * 滚动 24 小时会让"昨晚 23:50 打卡、今早 8:00 再打"被算成同一次，
     * 而这两次在用户眼里显然是两天。
     */
    private TripCheckin findSameDay(Long userId, String poiId, String experienceId,
                                    LocalDateTime at) {
        LocalDateTime dayStart = at.toLocalDate().atStartOfDay();
        LocalDateTime dayEnd = at.toLocalDate().plusDays(1).atStartOfDay();
        List<TripCheckin> found = checkinMapper.selectList(Wrappers.<TripCheckin>lambdaQuery()
                .eq(TripCheckin::getUserId, userId)
                .eq(poiId != null, TripCheckin::getPoiId, poiId)
                .eq(experienceId != null, TripCheckin::getExperienceId, experienceId)
                // ★ 目标为 null 的那一半必须显式判 null：
                //   eq(condition=false, ...) 会把条件整个丢掉，于是"只打卡体验项目"
                //   会退化成"这个人今天所有的足迹"，判重范围被放大到全表 ——
                //   症状是打卡 A 体验后，打卡 B 体验也被当成重复。
                .isNull(poiId == null, TripCheckin::getPoiId)
                .isNull(experienceId == null, TripCheckin::getExperienceId)
                .ge(TripCheckin::getCheckinAt, dayStart)
                .lt(TripCheckin::getCheckinAt, dayEnd)
                .orderByAsc(TripCheckin::getId)
                .last("LIMIT 1"));
        return found.isEmpty() ? null : found.get(0);
    }

    private static TripCheckinVO toVO(TripCheckin row) {
        TripCheckinVO vo = new TripCheckinVO();
        vo.setId(row.getId());
        vo.setPoiId(row.getPoiId());
        vo.setPoiName(row.getPoiName());
        vo.setExperienceId(row.getExperienceId());
        vo.setExperienceName(row.getExperienceName());
        vo.setCheckinAt(row.getCheckinAt());
        vo.setSource(row.getSource());
        vo.setSourceLabel(sourceLabel(row.getSource()));
        vo.setNote(row.getNote());
        return vo;
    }

    /** source → 中文。集中在这里，前端不维护这张映射表 */
    private static String sourceLabel(String source) {
        if (SOURCE_DIVERSION.equals(source)) {
            return "分流到访";
        }
        if ("SIM".equals(source)) {
            return "演示数据";
        }
        return "到访打卡";
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
