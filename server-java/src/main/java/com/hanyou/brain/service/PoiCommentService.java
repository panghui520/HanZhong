package com.hanyou.brain.service;

import java.util.List;
import java.util.Map;

import com.hanyou.brain.vo.AdminCommentVO;
import com.hanyou.brain.vo.CommentListVO;
import com.hanyou.brain.vo.CommentVO;

/**
 * 景点评论（M10 续）。
 *
 * <p>两组方法对应两个入口，权限要求完全不同：
 * <ul>
 *   <li>{@link #listForPoi} / {@link #create} —— 游客端。列表公开（未登录也能看），
 *       发表需要登录。</li>
 *   <li>{@link #listForAdmin} / {@link #updateStatus} / {@link #delete} —— 管理端。
 *       由 {@code SecurityConfig} 的 {@code /api/admin/**} 规则统一要求 OPERATOR 角色。</li>
 * </ul>
 *
 * <p><b>游客端只可能看到 APPROVED</b>，这条约束在实现里强制，不靠前端自觉 ——
 * 前端只负责渲染，不该承担正确性。
 */
public interface PoiCommentService {

    /**
     * 某个景点的评论列表 + 评分汇总（游客端）。
     *
     * @throws com.hanyou.brain.common.BizException 资源不存在或已下架时抛 1001。
     *         与 {@code PoiService.getPoiDetail} 同一判据 —— 否则一条下架景点
     *         的评论仍能靠直链读到，"用户端只展示上架状态"就只对详情页成立。
     */
    CommentListVO listForPoi(String poiId);

    /** 发表评论。默认状态 APPROVED，见 {@code PoiComment.STATUS_APPROVED} 的注释 */
    CommentVO create(String poiId, Long userId, Map<String, Object> body);

    /**
     * 管理端列表。
     *
     * @param poiId  按景点筛，null = 全部
     * @param status 按状态筛，null = 全部
     */
    List<AdminCommentVO> listForAdmin(String poiId, String status);

    /** 改状态：通过 / 隐藏 / 打回待审 */
    AdminCommentVO updateStatus(Long id, String status);

    /** 物理删除。与"隐藏"是两回事：隐藏可恢复，删除不可 */
    void delete(Long id);
}
