package com.hanyou.brain.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hanyou.brain.common.BizException;
import com.hanyou.brain.common.BodyReader;
import com.hanyou.brain.common.ErrorCode;
import com.hanyou.brain.entity.AppUser;
import com.hanyou.brain.entity.Poi;
import com.hanyou.brain.entity.PoiComment;
import com.hanyou.brain.mapper.AppUserMapper;
import com.hanyou.brain.mapper.PoiCommentMapper;
import com.hanyou.brain.mapper.PoiMapper;
import com.hanyou.brain.service.PoiCommentService;
import com.hanyou.brain.vo.AdminCommentVO;
import com.hanyou.brain.vo.CommentListVO;
import com.hanyou.brain.vo.CommentVO;

import lombok.RequiredArgsConstructor;

/**
 * 景点评论实现（M10 续）。
 *
 * <p>三条贯穿本类的规则：
 *
 * <p><b>1. 游客端只可能拿到 APPROVED。</b>过滤写在查询条件里，不是拿到之后再筛 ——
 * 后者一旦有人在中间加了条 return，隐藏的评论就漏出去了。
 *
 * <p><b>2. 评论要挂在"看得见的资源"上。</b>发表与列表都先校验 poi 存在且
 * status=1。否则一条下架景点的评论仍能靠直链读到、甚至被继续写入，
 * "用户端只展示上架状态"就只对详情页成立。
 *
 * <p><b>3. 署名现取，不冗余。</b>与 M6 的 order_review 同一做法：
 * 昵称从 {@code app_user} 批量查出来再分发，不在循环里逐个查。
 */
@Service
@RequiredArgsConstructor
public class PoiCommentServiceImpl implements PoiCommentService {

    private static final Logger log = LoggerFactory.getLogger(PoiCommentServiceImpl.class);

    /**
     * 详情页最多返回多少条。
     *
     * <p>评分汇总仍然按**全部**已通过评论算（见 {@link #averageOf}），
     * 只有列表本身截断 —— 否则评论一多，"口碑评分"会悄悄变成
     * "最近 20 条的平均"，而页面上写的还是"口碑评分"。
     */
    private static final int MAX_ITEMS = 20;

    /** 与 db/V12 的 VARCHAR(500) 一致。数据库那一层才是最终防线，这里提前给出人话提示 */
    private static final int MAX_CONTENT = 500;

    /** 允许的状态。用 Set 而不是 if 链：加一个状态时只改这一处 */
    private static final Set<String> STATUSES = Set.of(
            PoiComment.STATUS_PENDING,
            PoiComment.STATUS_APPROVED,
            PoiComment.STATUS_HIDDEN);

    private final PoiCommentMapper commentMapper;
    private final PoiMapper poiMapper;
    private final AppUserMapper appUserMapper;

    // ============================================================
    // 游客端
    // ============================================================

    @Override
    public CommentListVO listForPoi(String poiId) {
        requireVisiblePoi(poiId);

        List<PoiComment> approved = commentMapper.selectList(new LambdaQueryWrapper<PoiComment>()
                .eq(PoiComment::getPoiId, poiId)
                .eq(PoiComment::getStatus, PoiComment.STATUS_APPROVED)
                // 时间倒序 + id 倒序：同一秒提交的两条也要有稳定顺序，
                // 否则刷新一次两条评论会互换位置
                .orderByDesc(PoiComment::getCreatedAt)
                .orderByDesc(PoiComment::getId));

        CommentListVO vo = new CommentListVO();
        vo.setTotal(approved.size());
        vo.setAverageRating(averageOf(approved));
        vo.setItems(toVOs(approved.stream().limit(MAX_ITEMS).toList()));
        return vo;
    }

    @Override
    @Transactional
    public CommentVO create(String poiId, Long userId, Map<String, Object> body) {
        requireVisiblePoi(poiId);

        Integer rating = BodyReader.intOf(body, "rating");
        if (rating == null || rating < 1 || rating > 5) {
            throw new BizException(ErrorCode.COMMENT_RATING_INVALID);
        }

        String content = BodyReader.str(body, "content");
        content = content == null ? "" : content.trim();
        if (content.isEmpty()) {
            throw new BizException(ErrorCode.COMMENT_CONTENT_REQUIRED);
        }
        if (content.length() > MAX_CONTENT) {
            // 不用 COMMENT_CONTENT_REQUIRED：那条文案是"请填写评论内容"，
            // 而这里的成因恰恰相反（写太多了），给同一句话会把人带偏
            throw new BizException(ErrorCode.BAD_REQUEST, "评论内容最多 " + MAX_CONTENT + " 字");
        }

        PoiComment c = new PoiComment();
        c.setPoiId(poiId);
        c.setUserId(userId);
        c.setContent(content);
        c.setRating(rating);
        // 默认直接可见，理由见 PoiComment.STATUS_APPROVED 的注释
        c.setStatus(PoiComment.STATUS_APPROVED);
        commentMapper.insert(c);

        log.info("[M10] 新增评论 poi={} user={} rating={} id={}", poiId, userId, rating, c.getId());
        // 回读再返回：created_at 是数据库填的，不回读就是 null
        return toVOs(List.of(commentMapper.selectById(c.getId()))).get(0);
    }

    // ============================================================
    // 管理端
    // ============================================================

    @Override
    public List<AdminCommentVO> listForAdmin(String poiId, String status) {
        List<PoiComment> rows = commentMapper.selectList(new LambdaQueryWrapper<PoiComment>()
                .eq(StringUtils.hasText(poiId), PoiComment::getPoiId, poiId)
                .eq(StringUtils.hasText(status), PoiComment::getStatus, status)
                .orderByDesc(PoiComment::getCreatedAt)
                .orderByDesc(PoiComment::getId));
        return toAdminVOs(rows);
    }

    @Override
    @Transactional
    public AdminCommentVO updateStatus(Long id, String status) {
        requireComment(id);
        if (!StringUtils.hasText(status) || !STATUSES.contains(status)) {
            throw new BizException(ErrorCode.COMMENT_STATUS_INVALID);
        }

        // 只改一列，不必读-改-写整行
        PoiComment patch = new PoiComment();
        patch.setId(id);
        patch.setStatus(status);
        commentMapper.updateById(patch);

        log.info("[M10] 评论状态变更 id={} status={}", id, status);
        return toAdminVOs(List.of(commentMapper.selectById(id))).get(0);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        PoiComment c = requireComment(id);
        commentMapper.deleteById(id);
        log.info("[M10] 删除评论 id={} poi={}", id, c.getPoiId());
    }

    // ============================================================
    // 校验
    // ============================================================

    /**
     * 校验资源存在且上架。
     *
     * <p>判据与 {@code PoiServiceImpl.getPoiDetail} 逐字一致（status=1）——
     * 两处必须同步，否则会出现"详情页 404、评论接口 200"这种
     * 说不清哪边对的状态。
     */
    private Poi requireVisiblePoi(String poiId) {
        Poi p = StringUtils.hasText(poiId) ? poiMapper.selectById(poiId) : null;
        if (p == null || !Integer.valueOf(1).equals(p.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不存在或已下架：" + poiId);
        }
        return p;
    }

    private PoiComment requireComment(Long id) {
        PoiComment c = id == null ? null : commentMapper.selectById(id);
        if (c == null) {
            throw new BizException(ErrorCode.COMMENT_NOT_FOUND);
        }
        return c;
    }

    // ============================================================
    // 转换
    // ============================================================

    private List<CommentVO> toVOs(List<PoiComment> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = nicknames(list.stream()
                .map(PoiComment::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));

        List<CommentVO> out = new ArrayList<>(list.size());
        for (PoiComment c : list) {
            CommentVO v = new CommentVO();
            v.setId(c.getId());
            v.setPoiId(c.getPoiId());
            String nick = lookup(names, c.getUserId());
            // 兜底：用户注销或昵称为空时，评论不能显示成空白署名
            v.setNickname(StringUtils.hasText(nick) ? nick : "游客");
            v.setRating(c.getRating());
            v.setContent(c.getContent());
            v.setCreatedAt(c.getCreatedAt());
            out.add(v);
        }
        return out;
    }

    private List<AdminCommentVO> toAdminVOs(List<PoiComment> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = nicknames(list.stream()
                .map(PoiComment::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));

        Set<String> poiIds = list.stream()
                .map(PoiComment::getPoiId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Map<String, String> poiNames = poiNames(poiIds);

        List<AdminCommentVO> out = new ArrayList<>(list.size());
        for (PoiComment c : list) {
            AdminCommentVO v = new AdminCommentVO();
            v.setId(c.getId());
            v.setPoiId(c.getPoiId());
            v.setPoiName(lookup(poiNames, c.getPoiId()));
            v.setUserId(c.getUserId());
            String nick = lookup(names, c.getUserId());
            v.setNickname(StringUtils.hasText(nick) ? nick : "游客");
            v.setRating(c.getRating());
            v.setContent(c.getContent());
            v.setStatus(c.getStatus());
            v.setStatusLabel(statusLabel(c.getStatus()));
            v.setCreatedAt(c.getCreatedAt());
            v.setUpdatedAt(c.getUpdatedAt());
            out.add(v);
        }
        return out;
    }

    /**
     * user_id -> 昵称。
     *
     * <p>刻意**不用** {@code Collectors.toMap}：昵称列可空（V3 里 nickname 没有
     * NOT NULL），而 toMap 的 value 为 null 时会抛 NullPointerException ——
     * 一个"某个演示账号没填昵称"就能让整个详情页 500。这里显式跳过空昵称，
     * 由调用方用"游客"兜底。
     */
    private Map<Long, String> nicknames(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> out = new HashMap<>();
        for (AppUser u : appUserMapper.selectBatchIds(userIds)) {
            if (u != null && u.getId() != null && StringUtils.hasText(u.getNickname())) {
                out.put(u.getId(), u.getNickname());
            }
        }
        return out;
    }

    /** poi_id -> 名称。资源被数据包重灌换掉时查不到，返回 null 由前端回落到编码 */
    private Map<String, String> poiNames(Set<String> poiIds) {
        if (poiIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new HashMap<>();
        for (Poi p : poiMapper.selectBatchIds(poiIds)) {
            if (p != null && StringUtils.hasText(p.getName())) {
                out.put(p.getId(), p.getName());
            }
        }
        return out;
    }

    /**
     * 平均分，保留一位小数。**无评论返回 null，不返回 0**。
     *
     * <p>0 分是"所有人都打了最低分"（一个结论），没有评论是"没有数据"
     * （另一个结论）。返回 0 会让前端把后者显示成前者。
     */
    private static Double averageOf(List<PoiComment> list) {
        if (list.isEmpty()) {
            return null;
        }
        int sum = 0;
        for (PoiComment c : list) {
            sum += c.getRating() == null ? 0 : c.getRating();
        }
        return BigDecimal.valueOf((double) sum / list.size())
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 中文名由服务端给出，前端不维护映射表（与 M6 / M10 的 sourceLabel 同一约定） */
    private static String statusLabel(String status) {
        if (PoiComment.STATUS_PENDING.equals(status)) {
            return "待审核";
        }
        if (PoiComment.STATUS_APPROVED.equals(status)) {
            return "已通过";
        }
        if (PoiComment.STATUS_HIDDEN.equals(status)) {
            return "已隐藏";
        }
        return status;
    }

    /**
     * 按 id 取名字，id 为 null 时直接返回 null。
     *
     * <p>不写成 {@code names.get(id)}：{@code Map.of()} 返回的不可变 Map 在
     * get(null) 时抛 NullPointerException。本类返回的空 Map 已经改成
     * {@code Collections.emptyMap()}（它允许 get(null)），这里再挡一层 ——
     * 正确性不该押在 Map 的实现细节上。
     */
    private static <T> String lookup(Map<T, String> names, T id) {
        return id == null ? null : names.get(id);
    }
}
