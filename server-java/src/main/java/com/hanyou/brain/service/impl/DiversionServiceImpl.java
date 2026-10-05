package com.hanyou.brain.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.entity.DiversionNotice;
import com.hanyou.brain.entity.RiskEvent;
import com.hanyou.brain.mapper.DiversionNoticeMapper;
import com.hanyou.brain.mapper.RiskEventMapper;
import com.hanyou.brain.service.DiversionService;
import com.hanyou.brain.service.OpsService;
import com.hanyou.brain.service.support.DiversionAdvisor;
import com.hanyou.brain.vo.DiversionCandidateVO;
import com.hanyou.brain.vo.DiversionNoticeVO;
import com.hanyou.brain.vo.PoiStatVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 分流公告的编排层。
 *
 * <p>三件事：算候选（**调 OpsService，不自己算**）、管状态流转、把快照与当前承载
 * 一起组装成视图。判定逻辑一行都不在这里 —— 候选打分在
 * {@link DiversionAdvisor}（纯函数），承载与事件在 {@link OpsService}。
 *
 * <h3>★ 两条口径约定</h3>
 *
 * <p><b>一、候选只算一次。</b>公告里的候选必须与运营在风险事件上看到的
 * 是同一份（都走 {@code OpsService.candidatesOf}）。各算一遍的后果是
 * 工单写"去 A 村"、公告推"去 B 村"，而两者都是从同一条事件生成的。
 *
 * <p><b>二、过期判断不依赖定时任务。</b>游客端查询把
 * {@code status='PUBLISHED' AND expire_at > NOW()} 写进 SQL，
 * {@link #expireOverdue()} 只是让运营列表看得准。服务停了一夜再启动，
 * 首页也不会挂着一条昨天的公告。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiversionServiceImpl implements DiversionService {

    /**
     * 候选快照的序列化器。用独立的 ObjectMapper 而不是容器里那个：
     * 这份 JSON 是**存进数据库的内部格式**，不该跟着 Web 层的命名策略走 ——
     * 哪天有人给 Web 配了别的前缀策略，历史快照就解析不出来了。
     * 与 CityPackImporter 用独立 ObjectMapper 读数据包同一个理由。
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final DateTimeFormatter CODE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DiversionNoticeMapper noticeMapper;
    private final RiskEventMapper riskEventMapper;
    private final OpsService opsService;

    // ==================================================================
    // 生成 / 编辑
    // ==================================================================

    @Override
    @Transactional
    public DiversionNoticeVO createDraft(Long riskEventId, String title, String message) {
        RiskEvent event = riskEventMapper.selectById(riskEventId);
        if (event == null) {
            throw new BizException(ErrorCode.RISK_NOT_FOUND);
        }
        List<DiversionNotice> existing = noticeMapper.selectList(new LambdaQueryWrapper<DiversionNotice>()
                .eq(DiversionNotice::getRiskEventId, riskEventId));
        DiversionNotice reuse = null;
        if (!existing.isEmpty()) {
            DiversionNotice old = existing.get(0);
            // ★ 判定必须**同时**看 status 和真实的 expire_at，只看 status 会留下死路。
            //
            // 草稿在创建时就写了 expire_at（见下面的 setExpireAt），但 expireOverdue()
            // 是**定时**跑的 —— 在它跑之前，一条早已超时的草稿 status 仍然是 DRAFT。此时：
            //   · 发布     → isOverdue 为真 → 8015
            //   · 重新生成 → status != EXPIRED → 8011
            // 两条路都堵死，运营只能去数据库删行，而那不是运营能做的动作。
            // 补上 isOverdue(old) 之后，恢复路径不再依赖"清理任务跑过没有"。
            if (!DiversionNotice.STATUS_EXPIRED.equals(old.getStatus()) && !isOverdue(old)) {
                // 与工单同一条口径：一条事件只发一条公告。静默返回已有那条会让运营
                // 以为"又发了一条"，而首页上仍然只有一条 —— 看着像发布没生效
                throw new BizException(ErrorCode.NOTICE_EXISTS);
            }
            // ★ 已过期的那条**允许重新生成**（"已过期"= status 已是 EXPIRED，或
            // expire_at 已过 —— 上面那个判断的两个分支），沿用同一行
            // （uk_event 只允许一行），换新编码、重算候选。
            //
            // 没有这一步会形成死路：过期后既不能重新发布（8015，候选快照是过期那天
            // 算的），又不能重新生成（8011，重复）—— 运营只能去数据库删行，
            // 而那不是运营能做的动作。8015 的提示语写的是"请重新生成后再发布"，
            // 这一段就是让那句话成立。
            reuse = old;
        }
        List<DiversionCandidateVO> candidates = opsService.candidatesOf(riskEventId);
        if (candidates.isEmpty()) {
            // 没有候选就不该有公告：一条"建议分流"却不说到哪去的公告，
            // 发到首页只会让游客困惑。宁可报错让运营看到原因
            throw new BizException(ErrorCode.NOTICE_CANDIDATE_EMPTY);
        }

        DiversionNotice n = reuse != null ? reuse : new DiversionNotice();
        n.setCode(nextCode());
        n.setRiskEventId(event.getId());
        n.setFromPoiId(event.getPoiId());
        n.setFromPoiName(event.getPoiName());
        n.setDistrict(event.getDistrict());
        n.setTitle(StringUtils.hasText(title) ? title.trim() : defaultTitle(event));
        n.setMessage(StringUtils.hasText(message) ? message.trim() : defaultMessage(event, candidates));
        n.setCandidatesJson(writeCandidates(candidates));
        n.setStatus(DiversionNotice.STATUS_DRAFT);
        // 草稿也给一个失效时间（列是 NOT NULL）。发布时会按发布时间重算，
        // 所以草稿放两天再发不会只剩几分钟寿命
        n.setExpireAt(LocalDateTime.now().plusHours(DiversionNotice.DEFAULT_TTL_HOURS));

        if (reuse == null) {
            noticeMapper.insert(n);
        } else {
            // 复用已过期那一行。**不能用 updateById** —— 它默认忽略 null，
            // 清不掉上一次的发布痕迹，会留下"草稿 + 有发布时间"这种自相矛盾的一行。
            // 事件、源点、区县都没变，所以只重写会变的那几列。
            noticeMapper.update(null, new LambdaUpdateWrapper<DiversionNotice>()
                    .eq(DiversionNotice::getId, n.getId())
                    .set(DiversionNotice::getCode, n.getCode())
                    .set(DiversionNotice::getTitle, n.getTitle())
                    .set(DiversionNotice::getMessage, n.getMessage())
                    .set(DiversionNotice::getCandidatesJson, n.getCandidatesJson())
                    .set(DiversionNotice::getStatus, n.getStatus())
                    .set(DiversionNotice::getExpireAt, n.getExpireAt())
                    .set(DiversionNotice::getPublishedBy, null)
                    .set(DiversionNotice::getPublishedAt, null));
        }

        // 回读：created_at 由列默认值生成，不回读则该字段整个缺失
        DiversionNotice saved = noticeMapper.selectById(n.getId());
        log.info("[M5] 分流公告草稿已生成 code={} 来源事件={} 候选={} 个{}",
                saved.getCode(), event.getId(), candidates.size(),
                reuse == null ? "" : "（顶替已过期的那条 id=" + reuse.getId() + "）");
        return toVO(saved, currentUsage());
    }

    @Override
    @Transactional
    public DiversionNoticeVO updateNotice(Long id, String title, String message, String status, String publishedBy) {
        DiversionNotice n = noticeMapper.selectById(id);
        if (n == null) {
            throw new BizException(ErrorCode.NOTICE_NOT_FOUND);
        }
        if (StringUtils.hasText(title)) {
            n.setTitle(title.trim());
        }
        if (StringUtils.hasText(message)) {
            n.setMessage(message.trim());
        }

        if (StringUtils.hasText(status)) {
            if (DiversionNotice.STATUS_PUBLISHED.equals(status)) {
                // ★ 判定用"是否真的过期"（expire_at 已过），**不看 status 有没有被
                // 定时任务改成 EXPIRED**。定时任务只是让运营列表看得准；它跑没跑，
                // 不该改变运营点同一个按钮的结果 —— 否则同一份数据会因为"清理跑没跑过"
                // 给出两种行为，这种差异在演示和答辩时解释不清。
                if (isOverdue(n)) {
                    // 过期公告不能直接重新发布：它的候选快照是**过期那天**算的，
                    // 直接发出去等于拿旧方案指导今天的行程。
                    //
                    // 出路是"重新生成"：createDraft 会顶替掉这一条，而它认的是
                    // "status == EXPIRED **或** isOverdue(old)"，所以**不依赖**
                    // expireOverdue 这个定时任务有没有跑过。
                    // 这一点是必须的 —— 早先 createDraft 只认 status，而超时的 DRAFT
                    // 又不会被清理任务收走，于是"出路"对超时草稿并不存在：公告一旦
                    // 放超时就成死路（发布 8015 / 重新生成 8011）。
                    throw new BizException(ErrorCode.NOTICE_EXPIRED);
                }
                if (!StringUtils.hasText(n.getTitle())) {
                    throw new BizException(ErrorCode.NOTICE_TITLE_REQUIRED);
                }
                n.setStatus(DiversionNotice.STATUS_PUBLISHED);
                if (n.getPublishedAt() == null) {
                    n.setPublishedAt(LocalDateTime.now());
                }
                // ★ 每次发布都重算失效时间。草稿可能放了两天才发，
                // 若沿用创建时的时间，公告一发布就只剩几小时寿命
                n.setExpireAt(LocalDateTime.now().plusHours(DiversionNotice.DEFAULT_TTL_HOURS));
                if (StringUtils.hasText(publishedBy)) {
                    n.setPublishedBy(publishedBy.trim());
                }
            } else if (DiversionNotice.STATUS_WITHDRAWN.equals(status)) {
                if (isOverdue(n)) {
                    throw new BizException(ErrorCode.NOTICE_STATUS_INVALID);
                }
                n.setStatus(DiversionNotice.STATUS_WITHDRAWN);
            } else {
                // 不允许运营把状态直接改成 EXPIRED —— 那是系统按时间置的。
                // 手工置会让人以为"过期了"，而实际是"被人关掉了"，追不了责
                throw new BizException(ErrorCode.NOTICE_STATUS_INVALID);
            }
        }
        noticeMapper.updateById(n);

        DiversionNotice saved = noticeMapper.selectById(id);
        log.info("[M5] 分流公告已更新 code={} status={}", saved.getCode(), saved.getStatus());
        return toVO(saved, currentUsage());
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Override
    public List<DiversionNoticeVO> listNotices(String status) {
        LambdaQueryWrapper<DiversionNotice> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            q.eq(DiversionNotice::getStatus, status);
        }
        q.orderByDesc(DiversionNotice::getId);
        Map<String, Double> usage = currentUsage();
        return noticeMapper.selectList(q).stream().map(n -> toVO(n, usage)).toList();
    }

    @Override
    public List<DiversionNoticeVO> listPublished() {
        // ★ 过滤条件写在 SQL 里，不是查出来再筛：少一次"有人改了查询忘了加条件"的机会
        LambdaQueryWrapper<DiversionNotice> q = new LambdaQueryWrapper<DiversionNotice>()
                .eq(DiversionNotice::getStatus, DiversionNotice.STATUS_PUBLISHED)
                .gt(DiversionNotice::getExpireAt, LocalDateTime.now())
                .orderByDesc(DiversionNotice::getPublishedAt);
        Map<String, Double> usage = currentUsage();
        return noticeMapper.selectList(q).stream().map(n -> toVO(n, usage)).toList();
    }

    @Override
    @Transactional
    public int expireOverdue() {
        DiversionNotice patch = new DiversionNotice();
        patch.setStatus(DiversionNotice.STATUS_EXPIRED);
        // ★ 条件是"**还没被标成过期** 且 expire_at 已过"，而不是"状态是 PUBLISHED"。
        //
        // 只看 PUBLISHED 会漏掉超时的 DRAFT：草稿创建时同样写了 expire_at，
        // 却永远等不到这次清理 —— 于是运营列表上一条早该失效的草稿一直挂着"草稿"，
        // 而它其实已经发不出去了（publish 会以 8015 拒掉）。
        //
        // `.ne(EXPIRED)` 保证已经 EXPIRED 的行不被重复处理：重复 UPDATE 虽然幂等，
        // 但会把 n 说大、让日志里的"清理了 N 条"与实际不符。
        int n = noticeMapper.update(patch, new LambdaUpdateWrapper<DiversionNotice>()
                .ne(DiversionNotice::getStatus, DiversionNotice.STATUS_EXPIRED)
                .le(DiversionNotice::getExpireAt, LocalDateTime.now()));
        if (n > 0) {
            log.info("[M5] 分流公告过期清理 {} 条", n);
        }
        return n;
    }

    // ==================================================================
    // 组装
    // ==================================================================

    /**
     * 当前各资源点的承载占用率。
     *
     * <p>{@code hasData=false} 时必须**丢掉**：{@code listPoiStats()} 在那种情况下
     * 给的是占位的 {@code 0.0}。留着会把"读不到承载"变成"很空"，
     * 于是公告会推荐一个其实不知道挤不挤的点 —— 这正是本轮反复防的那个错。
     */
    private Map<String, Double> currentUsage() {
        Map<String, Double> m = new HashMap<>();
        for (PoiStatVO s : opsService.listPoiStats()) {
            if (Boolean.TRUE.equals(s.getHasData()) && s.getCapacityUsage() != null) {
                m.put(s.getPoiId(), s.getCapacityUsage());
            }
        }
        return m;
    }

    private DiversionNoticeVO toVO(DiversionNotice n, Map<String, Double> usage) {
        DiversionNoticeVO vo = new DiversionNoticeVO();
        vo.setId(n.getId());
        vo.setCode(n.getCode());
        vo.setRiskEventId(n.getRiskEventId());
        vo.setFromPoiId(n.getFromPoiId());
        vo.setFromPoiName(n.getFromPoiName());
        vo.setDistrict(n.getDistrict());
        vo.setTitle(n.getTitle());
        vo.setMessage(n.getMessage());
        vo.setStatus(n.getStatus());
        vo.setExpireAt(n.getExpireAt());
        vo.setPublishedBy(n.getPublishedBy());
        vo.setPublishedAt(n.getPublishedAt());
        vo.setCreatedAt(n.getCreatedAt());
        vo.setSynthetic(true);

        List<DiversionCandidateVO> cs = readCandidates(n.getCandidatesJson());
        int available = 0;
        for (DiversionCandidateVO c : cs) {
            Double cur = usage.get(c.getPoiId());
            c.setCurrentUsage(cur);
            // 承载读不到 → 不可用。无法确认"现在宽裕"，就不该让游客跑一趟
            c.setAvailable(cur != null && cur < DiversionAdvisor.usageBar());
            if (Boolean.TRUE.equals(c.getAvailable())) {
                available++;
            }
        }
        vo.setCandidates(cs);
        vo.setAvailableCount(available);
        return vo;
    }

    /**
     * 读候选快照。
     *
     * <p><b>解析失败不抛异常</b>，只记 error 并返回空列表。
     * 理由：这是**读路径**，而且读的是首页要显示的东西。一份坏 JSON 让整页
     * 打不开，比少显示一条提示严重得多。日志里带上了公告 id，排查有抓手。
     */
    private List<DiversionCandidateVO> readCandidates(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<DiversionCandidateVO>>() {
            });
        } catch (Exception e) {
            log.error("[M5] 分流公告候选快照解析失败，本条公告将不显示候选。原始内容：{}", json, e);
            return List.of();
        }
    }

    private String writeCandidates(List<DiversionCandidateVO> candidates) {
        try {
            return JSON.writeValueAsString(candidates);
        } catch (Exception e) {
            // 写路径失败不能吞：存一份坏快照进去，等于让公告永远说不清"当时为什么推荐它"
            throw new BizException(ErrorCode.INTERNAL, "候选快照序列化失败");
        }
    }

    /**
     * 是否已过失效时间。
     *
     * <p><b>判定过期一律用它，不看 {@code status}。</b>理由见
     * {@link #updateNotice}：定时任务把 status 改成 EXPIRED 只是为了让运营列表
     * 看得准，属于**展示**；而"能不能重新发布"是**规则**。规则若依赖展示，
     * 定时任务跑没跑就会改变结果。
     *
     * <p>同时它也是游客端过滤的同一口径（SQL 里 {@code expire_at > NOW()}），
     * 两处必须一致 —— 否则会出现"游客看不到、运营却发不出去"的公告。
     */
    private static boolean isOverdue(DiversionNotice n) {
        return n.getExpireAt() != null && n.getExpireAt().isBefore(LocalDateTime.now());
    }

    private String defaultTitle(RiskEvent event) {
        return "「" + event.getPoiName() + "」当前客流较高，推荐这几个去处";
    }

    /**
     * 默认正文。
     *
     * <p>带"演示用仿真数据"这句是**硬要求**：公告是发给游客看的，
     * 而承载率来自合成数据。其他地方（管理端页面）用徽标标注就够了，
     * 但这条文案会被复制、会被截图，标注必须跟着文字走。
     * 运营可以改文案，但徽标由后端给（{@code synthetic} 字段），改不掉。
     */
    private String defaultMessage(RiskEvent event, List<DiversionCandidateVO> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("今日客流提示（演示用仿真数据）：「").append(event.getPoiName())
                .append("」当前承载较高，建议改往以下点位 —— ");
        for (int i = 0; i < candidates.size(); i++) {
            DiversionCandidateVO c = candidates.get(i);
            sb.append(i == 0 ? "" : "；")
                    .append("①②③".charAt(Math.min(i, 2)))
                    .append(c.getName()).append("（").append(c.getReason()).append("）");
        }
        return sb.toString();
    }

    /**
     * 生成公告编码 {@code DN-yyyyMMdd-NNN}。
     *
     * <p>与 {@code work_order.code} 同一做法与同一已知边界：序号按"当天已有多少条 + 1"
     * 算，单机演示够用，多实例并发会撞号（那时改用数据库序列或唯一键重试）。
     */
    private String nextCode() {
        String prefix = "DN-" + LocalDate.now().format(CODE_DAY) + "-";
        Long used = noticeMapper.selectCount(
                new LambdaQueryWrapper<DiversionNotice>().likeRight(DiversionNotice::getCode, prefix));
        return prefix + String.format("%03d", (used == null ? 0 : used) + 1);
    }
}
