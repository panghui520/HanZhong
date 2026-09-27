package com.hanyou.brain.service;

import java.util.List;

import com.hanyou.brain.vo.DiversionCandidateVO;
import com.hanyou.brain.vo.OpsSnapshotVO;
import com.hanyou.brain.vo.PoiStatVO;
import com.hanyou.brain.vo.RiskEventVO;
import com.hanyou.brain.vo.WorkOrderVO;

/**
 * M5 承载力与乡村分流：规则引擎、运营快照、风险事件与工单。
 *
 * <p>分成两组能力，安全边界不同（见 SecurityConfig）：
 * <ul>
 *   <li>{@link #listPoiStats()} —— **游客端**用（探索页 / 行程规划 / 详情页
 *       都要显示承载率），公开可访问；</li>
 *   <li>其余全部是**运营端**能力，必须登录且是 OPERATOR。</li>
 * </ul>
 * 把这两组放在同一个接口里是因为它们读同一份数据、同一套口径；
 * 拆成两个 Service 会让"承载率"这个概念有两个入口，迟早漂移。
 */
public interface OpsService {

    /**
     * 跑一遍规则引擎并把命中的风险落库，返回本次命中的事件数。
     *
     * <p>可重复执行：同一条 (规则, 资源点, 日期) 只会有一条事件，
     * 重复扫描只刷新指标值，不新增行。见 {@code risk_event} 的唯一键。
     */
    int scan();

    /** 运营快照：管理端大屏的 6 组图表 */
    OpsSnapshotVO snapshot();

    /**
     * 风险事件列表。
     *
     * @param status   状态过滤（OPEN / HANDLED / CLOSED），null 表示全部
     * @param level    等级过滤（HIGH / MID），null 表示全部
     * @param district 区县过滤，null 表示全部
     */
    List<RiskEventVO> listRisks(String status, String level, String district);

    /** 风险事件详情 */
    RiskEventVO getRisk(Long id);

    /** 工单列表，按创建时间倒序 */
    List<WorkOrderVO> listWorkOrders(String status);

    /**
     * 一键建单：把风险事件转成工单，并把事件置为已建单。
     *
     * <p>一条事件只允许建一张单。重复建单会被拒绝（不是静默返回已有那张）——
     * 静默返回会让运营以为"又建了一张"，而界面上仍然只有一张。
     */
    WorkOrderVO createWorkOrder(Long riskEventId, String assignee, String suggestion);

    /**
     * 处置反馈。状态置为 DONE 时必须填 result。
     *
     * @param status    目标状态（PROCESSING / DONE）
     * @param assignee  处置人，null 表示不改
     * @param result    处置反馈，null 表示不改；置 DONE 时必填
     */
    WorkOrderVO updateWorkOrder(Long id, String status, String assignee, String result);

    /** 全部资源点的实时统计（游客端） */
    List<PoiStatVO> listPoiStats();

    /**
     * 某条风险事件的分流候选（**实时计算**，不落库）。
     *
     * <p>只有处置动作是 {@code DIVERSION} 的规则（规则 1 超载、规则 4 乡村闲置）
     * 才有候选，其余规则返回空列表 —— 它们没有"换一个去处"的语义。
     *
     * <p>给 {@code DiversionService} 用的：生成公告草稿时，候选必须与
     * 运营在事件上看到的是**同一份**，否则"公告里推荐的村子"和
     * "工单里写的村子"会对不上。所以候选只在一个地方算（本接口），
     * 公告不自己再算一遍。
     */
    List<DiversionCandidateVO> candidatesOf(Long riskEventId);
}
