package com.hanyou.brain.vo;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * 分流公告对外视图（游客端与运营端共用）。
 *
 * <p>游客端只拿得到 {@code PUBLISHED} 且未过期的（服务端过滤，不靠前端自觉）；
 * 运营端拿得到全部四态，用来编辑与撤下。
 *
 * <p>{@code availableCount} 是给界面用的一个判断：**当前还剩几个候选能去**。
 * 全部不可用时（发布后大家都满了），这条公告已经不该再显示在首页了 ——
 * 前端据此弱化或收起，运营列表据此提示"该撤下了"。
 * 服务端不在读取时自动撤下：那是 GET 里做写操作，而且撤不撤应当由人决定。
 */
@Data
public class DiversionNoticeVO {

    private Long id;
    private String code;

    /** 来源风险事件，可能为空 */
    private Long riskEventId;

    /** 溢出的资源点（A 点） */
    private String fromPoiId;
    private String fromPoiName;
    private String district;

    private String title;
    private String message;

    private String status;

    /** 失效时间。游客端查询一律带 expire_at > NOW() */
    private LocalDateTime expireAt;

    private String publishedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;

    /** 候选点。快照 + 当前两组数，见 {@link DiversionCandidateVO} */
    private List<DiversionCandidateVO> candidates;

    /** 当前仍可前往的候选数（0 表示这条公告该撤下了） */
    private Integer availableCount;

    /**
     * <b>恒为 true</b>：承载率来自 {@code poi_visit_stats}（合成数据）。
     *
     * <p>为什么要给这个字段而不是在前端写死一个"仿真数据"徽标：
     * 公告是**要发到游客端**的东西 —— 首页写着"XX景区今日已满、建议前往 XX"，
     * 而这是合成数据，就是这个项目里最严重的一处过度声称。
     * 徽标必须由后端给，这样将来接入真实客流时，改一处（这里是
     * {@code CityPackImporter} 写 {@code synthetic} 的地方）全站一致，
     * 不会出现"数据换成真的了、页面上还挂着'仿真'"或反过来。
     *
     * <p>（M5 第一轮的口径审计发现管理端那两个"仿真数据"徽标是**写死的**，
     * 这里不重复那个错误。）
     */
    private Boolean synthetic;
}
